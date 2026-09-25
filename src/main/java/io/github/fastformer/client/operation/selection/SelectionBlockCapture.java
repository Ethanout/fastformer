package io.github.fastformer.client.operation.selection;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Owns one cancellable world scan. All world access runs on the client tick. */
public final class SelectionBlockCapture {
   public static final int CELLS_PER_TICK = 4096;
   public static final long NANOS_PER_TICK = 2_000_000;
   public static final long MAX_SCAN_CELLS = 2_000_000;
   private final Supplier<SnapshotSource> source;
   private Job pending;
   private long scannedCells;
   private long peakTickNanos;
   private int peakBlocks;
   public SelectionBlockCapture() { this(SelectionBlockCapture::currentWorld); }
   SelectionBlockCapture(Supplier<SnapshotSource> source) { this.source = source; }
   public boolean pending() { return pending != null; }
   public void cancel() { pending = null; }
   public long scannedCells() { return scannedCells; }
   public long peakTickNanos() { return peakTickNanos; }
   public int peakBlocks() { return peakBlocks; }

   public boolean start(OperationSelectionVolume selection, ClientSelectionPart baseline,
      BooleanSupplier owned, Consumer<Map<BlockPos, ClientBlockSnapshot>> complete, Consumer<String> failed) {
      cancel();
      scannedCells = 0; peakTickNanos = 0; peakBlocks = 0;
      var world = source.get();
      if (world == HEADLESS) { complete.accept(Map.of()); return true; }
      if (world == null || selection == null) { failed.accept("fastformer.message.operation_capture_failed"); return false; }
      ScanCursor cursor;
      try { cursor = new ScanCursor(selection.bounds(), MAX_SCAN_CELLS); }
      catch (IllegalArgumentException exception) { failed.accept("fastformer.message.operation_capture_too_large"); return false; }
      pending = new Job(world, selection, baseline, cursor, owned, complete, failed);
      return true;
   }

   public void tick() {
      tick(System.nanoTime() + NANOS_PER_TICK);
   }

   public void tick(long deadline) {
      Job job = pending;
      if (job == null) return;
      var current = source.get();
      if (current == null || current.identity() != job.world.identity() || !job.owned.getAsBoolean()) { cancel(); return; }
      long started = System.nanoTime();
      int scanned = 0;
      try {
         scanned = ScanSlice.run(job.cursor, CELLS_PER_TICK, deadline, System::nanoTime, pos -> {
            if (!job.selection.intersects(new AABB(pos))) return;
            if (!job.world.loaded(pos)) throw new IllegalStateException("Selection includes an unloaded chunk");
            ClientBlockSnapshot previous = job.baseline == null ? null : job.baseline.blocks().get(pos);
            if (previous != null) { job.blocks.put(pos, previous); return; }
            if (job.baseline != null && job.baseline.source() != ClientSelectionPart.Source.WORLD) return;
            var snapshot = job.world.read(pos);
            if (snapshot != null) job.blocks.put(pos, snapshot);
         });
         scannedCells += scanned;
         peakBlocks = Math.max(peakBlocks, job.blocks.size());
         if (!job.cursor.hasNext()) {
            pending = null;
            job.complete.accept(job.blocks.build());
         }
      } catch (RuntimeException exception) {
         pending = null;
         job.failed.accept("fastformer.message.operation_capture_failed");
      } finally { peakTickNanos = Math.max(peakTickNanos, System.nanoTime() - started); }
   }

   interface SnapshotSource {
      Object identity();
      boolean loaded(BlockPos position);
      ClientBlockSnapshot read(BlockPos position);
   }

   private static final SnapshotSource HEADLESS = new SnapshotSource() {
      public Object identity() { return this; }
      public boolean loaded(BlockPos position) { return true; }
      public ClientBlockSnapshot read(BlockPos position) { return null; }
   };

   private static SnapshotSource currentWorld() {
      var minecraft = Minecraft.getInstance();
      if (minecraft == null) return HEADLESS;
      var level = minecraft.level;
      if (level == null) return null;
      return new SnapshotSource() {
         public Object identity() { return level; }
         public boolean loaded(BlockPos position) { return level.hasChunkAt(position); }
         public ClientBlockSnapshot read(BlockPos position) {
            var state = level.getBlockState(position);
            if (state.isAir()) return null;
            var entity = level.getBlockEntity(position);
            return new ClientBlockSnapshot(state, entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
         }
      };
   }

   private record Job(SnapshotSource world, OperationSelectionVolume selection,
      ClientSelectionPart baseline, ScanCursor cursor, BooleanSupplier owned,
      Consumer<Map<BlockPos, ClientBlockSnapshot>> complete, Consumer<String> failed,
      io.github.fastformer.fastplace.geometry.BlockPositionMaps.Builder<ClientBlockSnapshot> blocks) {
      Job(SnapshotSource world, OperationSelectionVolume selection, ClientSelectionPart baseline,
         ScanCursor cursor, BooleanSupplier owned, Consumer<Map<BlockPos, ClientBlockSnapshot>> complete, Consumer<String> failed) {
         this(world, selection, baseline, cursor, owned, complete, failed, new io.github.fastformer.fastplace.geometry.BlockPositionMaps.Builder<>());
      }
   }
}
