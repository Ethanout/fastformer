package io.github.fastformer.client.render.core;

import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;

/** Owns the short-lived hit cache and binds it to one player and world. */
final class InteractionRaycastOwner {
   private LocalPlayer player;
   private Level level;
   private LongRangeBlockRaycast.Result result;
   private Vec3 start, direction;
   private long sampledAt;
   private Purpose purpose;

   private enum Purpose { OUTLINE, SELECTION, PLACEMENT, REACH_TRANSITION }

   double distanceOr(double fallback) { return result == null ? fallback : result.distance(); }

   void clear() {
      player = null; level = null; result = null; start = null; direction = null; sampledAt = 0;
   }

   BlockHitResult clip(LocalPlayer currentPlayer, boolean forPlacement) {
      return clip(currentPlayer, forPlacement ? Purpose.PLACEMENT : Purpose.OUTLINE);
   }

   BlockHitResult clipForSelection(LocalPlayer currentPlayer, boolean throughFluids) {
      return clip(currentPlayer, throughFluids ? Purpose.OUTLINE : Purpose.SELECTION);
   }

   BlockHitResult clipForReachTransition(LocalPlayer currentPlayer) {
      return clip(currentPlayer, Purpose.REACH_TRANSITION);
   }

   private BlockHitResult clip(LocalPlayer currentPlayer, Purpose currentPurpose) {
      Vec3 eye = currentPlayer.getEyePosition(), view = currentPlayer.getViewVector(1.0F);
      long now = System.nanoTime();
      if (result == null || player != currentPlayer || level != currentPlayer.level() || purpose != currentPurpose
         || !eye.equals(start) || !view.equals(direction) || now - sampledAt > 16_000_000L) {
         result = switch (currentPurpose) {
            case PLACEMENT -> LongRangeBlockRaycast.clipForPlacement(currentPlayer.level(), currentPlayer, eye, view);
            case OUTLINE -> LongRangeBlockRaycast.clip(currentPlayer.level(), currentPlayer, eye, view);
            case SELECTION -> LongRangeBlockRaycast.clipForSelection(currentPlayer.level(), currentPlayer, eye, view, false);
            case REACH_TRANSITION -> LongRangeBlockRaycast.clipForReachTransition(currentPlayer.level(), currentPlayer, eye, view);
         };
         player = currentPlayer; level = currentPlayer.level(); purpose = currentPurpose;
         start = eye; direction = view; sampledAt = now;
      }
      return result.hit();
   }
}
