package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Authoritative Axiom-style replacement using the held block's placement state. */
public final class QuickReplaceManager {
   private QuickReplaceManager() {
   }

   public static boolean replaceCrosshair(ServerPlayer player) {
      if (!admitted(player)) {
         return false;
      }
      ServerLevel level = player.serverLevel();
      if (!QuickReplaceDedupe.accept(player.getUUID(), level.getGameTime())) {
         return false;
      }
      BlockHitResult hit = ServerInputDispatcher.raycastBlocks(player, ServerInputDispatcher.EXTENDED_REACH);
      if (hit.getType() != HitResult.Type.BLOCK) {
         return false;
      }
      BlockPos pos = hit.getBlockPos().immutable();
      Optional<BlockState> placement = PlaceableItems.placementState(
         player.getMainHandItem(), player, replacingContext(level, player, hit)
      );
      if (placement.isEmpty()) {
         return false;
      }
      BlockState beforeState = level.getBlockState(pos);
      BlockState afterState = copySharedProperties(beforeState, placement.orElseThrow());
      if (beforeState.equals(afterState)) {
         return false;
      }
      // A short transaction checks its own recovery result and keeps the lease
      // when a restore does not complete. Only "no change applies here" leaves
      // the world untouched and unreported.
      ShortWriteTransaction.Outcome outcome = ShortWriteTransaction.apply(
         player,
         level,
         Map.of(pos, afterState),
         FastPlaceSettings.load(player).placementUpdateMode().flags(),
         PlacementUpdateMode.CLIENT_ONLY.flags()
      );
      ShortWriteTransaction.report(outcome, player);
      return outcome == ShortWriteTransaction.Outcome.APPLIED;
   }

   /**
    * A world write may only start when no history, recovery, task or editing
    * session owns this player. The task checks match {@link BlockTinker} so a
    * short operation cannot start while a queued task still holds the lease.
    */
   private static boolean admitted(ServerPlayer player) {
      return player != null
         && !WorldHistoryManager.busy(player)
         && ServerInputDispatcher.canOperate(player)
         && !FastPlaceManager.active(player)
         && !OperationManager.active(player)
         && !GeometryManager.active(player)
         && !FastPlaceManager.taskActive(player)
         && !OperationManager.taskActive(player)
         && PlaceableItems.isPlaceable(player.getMainHandItem());
   }

   static BlockState copySharedProperties(BlockState source, BlockState target) {
      BlockState result = target;
      for (Property<?> property : source.getProperties()) {
         if (result.hasProperty(property)) {
            result = copyProperty(source, result, property);
         }
      }
      return result;
   }

   @SuppressWarnings({"rawtypes", "unchecked"})
   private static BlockState copyProperty(BlockState source, BlockState target, Property property) {
      Comparable value = source.getValue(property);
      return property.getPossibleValues().contains(value) ? target.setValue(property, value) : target;
   }

   private static PlacementContextSnapshot replacingContext(
      ServerLevel level, ServerPlayer player, BlockHitResult hit
   ) {
      PlacementContextSnapshot context = PlacementContextSnapshot.capture(
         level, player, player.getMainHandItem(), hit, false
      );
      return new PlacementContextSnapshot(
         context.hitBlock(), context.hitLocation(), context.clickedFace(), context.inside(), true,
         context.rotation(), context.horizontalDirection(), context.verticalDirection(), context.nearestDirections(),
         context.secondaryUseActive()
      );
   }
}
