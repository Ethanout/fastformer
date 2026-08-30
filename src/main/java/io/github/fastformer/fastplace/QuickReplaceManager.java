package io.github.fastformer.fastplace;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Authoritative Axiom-style replacement using the held block's placement state. */
public final class QuickReplaceManager {
   private static final Map<UUID, Long> LAST_REPLACE_TICK = new ConcurrentHashMap<>();

   private QuickReplaceManager() {
   }

   public static boolean replaceCrosshair(ServerPlayer player) {
      if (player == null || WorldHistoryManager.busy(player) || !ServerInputDispatcher.canOperate(player)
         || FastPlaceManager.active(player) || OperationManager.active(player) || GeometryManager.active(player)
         || !PlaceableItems.isPlaceable(player.getMainHandItem())) {
         return false;
      }
      ServerLevel level = player.serverLevel();
      long gameTick = level.getGameTime();
      if (LAST_REPLACE_TICK.put(player.getUUID(), gameTick) == gameTick) {
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
      if (beforeState.equals(afterState) || !WorldWriteCoordinator.tryAcquire(player.getServer(), level.dimension(), player.getUUID())) {
         return false;
      }
      try {
         Optional<ReversibleBlockSnapshot> before = ReversibleBlockSnapshot.capture(level, pos);
         if (before.isEmpty() || !WorldWriteSideEffectGuard.setBlock(
            level, pos, afterState, FastPlaceSettings.load(player).placementUpdateMode().flags()
         )) {
            return false;
         }
         Optional<ReversibleBlockSnapshot> after = ReversibleBlockSnapshot.capture(level, pos);
         if (after.isEmpty()) {
            before.orElseThrow().restore(level, PlacementUpdateMode.CLIENT_ONLY.flags());
            return false;
         }
         ArrayDeque<ReversibleBlockSnapshot> changes = new ArrayDeque<>();
         changes.addFirst(before.orElseThrow());
         if (!WorldHistoryManager.record(player, level, changes, Map.of(pos, after.orElseThrow()))) {
            before.orElseThrow().restore(level, PlacementUpdateMode.CLIENT_ONLY.flags());
            return false;
         }
         return true;
      } finally {
         WorldWriteCoordinator.release(player.getServer(), level.dimension(), player.getUUID());
      }
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
