package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import io.github.fastformer.fastplace.selection.SmartSelectionSearch;
import io.github.fastformer.fastplace.selection.SmartSelectionSimilarity;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Owns the incremental client scan. Incomplete scans never change the workspace. */
public final class SmartSelectionCapture {
   private Job pending;
   public boolean pending() { return pending != null; }
   public void cancel() { pending = null; }

   public static ClientBlockSnapshot captureCell(net.minecraft.world.level.Level level, BlockPos position) {
      if (level == null || level.isOutsideBuildHeight(position) || !level.hasChunkAt(position)) return null;
      if (SourceMaskRenderFilter.instance().hides(position)) {
         return new ClientBlockSnapshot(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null);
      }
      var state = level.getBlockState(position);
      var entity = state.isAir() ? null : level.getBlockEntity(position);
      return new ClientBlockSnapshot(state, entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
   }

   public boolean start(BlockPos seed, boolean family, BooleanSupplier owned,
      Consumer<Map<BlockPos, ClientBlockSnapshot>> complete, Consumer<String> failed) {
      var minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.level == null || pending != null) return false;
      ClientLevel level = minecraft.level;
      if (!level.hasChunkAt(seed) || level.getBlockState(seed).isAir()
         || SourceMaskRenderFilter.instance().positions().contains(seed)) return false;
      pending = new Job(level, new SmartSelectionSearch(seed),
         SmartSelectionSimilarity.fromSeed(level.getBlockState(seed), family), owned, complete, failed,
         Set.copyOf(SourceMaskRenderFilter.instance().positions()), new LinkedHashMap<>());
      return true;
   }

   public void tick(long deadline) {
      Job job = pending;
      if (job == null) return;
      if (Minecraft.getInstance().level != job.level || !job.owned.getAsBoolean()) { cancel(); return; }
      try {
         boolean done = job.search.step(position -> read(job, position), SelectionBlockCapture.CELLS_PER_TICK, deadline);
         if (done) { pending = null; job.complete.accept(Map.copyOf(job.blocks)); }
      } catch (SmartSelectionSearch.LimitExceeded exception) {
         pending = null;
         job.failed.accept("fastformer.message.smart_limit");
      } catch (UnloadedBoundary exception) {
         pending = null;
         job.failed.accept("fastformer.message.smart_unloaded");
      } catch (RuntimeException exception) {
         pending = null;
         org.slf4j.LoggerFactory.getLogger(SmartSelectionCapture.class).warn("Smart selection capture failed", exception);
         job.failed.accept("fastformer.message.operation_capture_failed");
      }
   }

   private static boolean read(Job job, BlockPos position) {
      if (job.level.isOutsideBuildHeight(position) || job.hidden.contains(position)) return false;
      if (!job.level.hasChunkAt(position)) throw new UnloadedBoundary();
      BlockState state = job.level.getBlockState(position);
      if (!job.matches.test(state)) return false;
      var entity = job.level.getBlockEntity(position);
      job.blocks.put(position, new ClientBlockSnapshot(state,
         entity == null ? null : entity.saveWithFullMetadata(job.level.registryAccess())));
      return true;
   }

   private record Job(ClientLevel level, SmartSelectionSearch search, Predicate<BlockState> matches,
      BooleanSupplier owned, Consumer<Map<BlockPos, ClientBlockSnapshot>> complete, Consumer<String> failed,
      Set<BlockPos> hidden, Map<BlockPos, ClientBlockSnapshot> blocks) { }
   private static final class UnloadedBoundary extends RuntimeException { }
}
