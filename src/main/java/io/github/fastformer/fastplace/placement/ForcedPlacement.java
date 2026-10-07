package io.github.fastformer.fastplace.placement;

import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.OperationManager;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;

/** Places creative-mode blocks and their companions through one reversible write. */
public final class ForcedPlacement {
   private static final boolean TRACE = Boolean.getBoolean("fastformer.tracePlacement");
   private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private ForcedPlacement() { }

   public static boolean use(ServerPlayer player, BlockHitResult hit) {
      var settings = FastPlaceSettings.load(player);
      if (!player.isCreative() || !settings.enabled() || !settings.forcePlacement()
         || !PlaceableItems.isPlaceable(player.getMainHandItem())
         || !io.github.fastformer.server.input.IdleInteractionSnapshots.idle(player)) return false;
      if (ServerInputDispatcher.interactionBlocked(player)) return true;
      var level = player.serverLevel();
      var snapshot = PlacementContextSnapshot.capture(level, player, player.getMainHandItem(), hit, false);
      var pos = snapshot.placementPosition();
      if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
         || !level.mayInteract(player, pos) || !player.canInteractWithBlock(pos, 0)) return true;
      var state = PlaceableItems.placementState(player.getMainHandItem(), player, snapshot).orElse(null);
      if (state == null) return true;
      Map<BlockPos, BlockState> changes = placementChanges(pos, state);
      for (BlockPos target : changes.keySet()) {
         if (!level.isInWorldBounds(target) || !level.hasChunkAt(target) || !level.getWorldBorder().isWithinBounds(target)
            || !level.mayInteract(player, target)) return true;
         if (!target.equals(pos) && !level.getBlockState(target).canBeReplaced()
            && !level.getBlockState(target).equals(changes.get(target))) return true;
      }
      var outcome = BlockEditTransaction.apply(player, level, changes,
         PlacementUpdateMode.CLIENT_ONLY.flags(), PlacementUpdateMode.CLIENT_ONLY.flags());
      if (TRACE) LOGGER.info("Placement input: server time={}, tick={}, hit={}, target={}, state={}, outcome={}",
         System.nanoTime(), level.getGameTime(), hit.getBlockPos(), pos, state, outcome);
      BlockEditTransaction.report(outcome, player);
      if (outcome == BlockEditTransaction.Outcome.APPLIED)
         io.github.fastformer.server.input.IdleInteractionSnapshots.placed(player, pos);
      return true;
   }

   private static Map<BlockPos, BlockState> placementChanges(BlockPos pos, BlockState state) {
      if (!(state.getBlock() instanceof DoorBlock)) return Map.of(pos, state);
      Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
      changes.put(pos, state.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
      changes.put(pos.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
      return changes;
   }
}
