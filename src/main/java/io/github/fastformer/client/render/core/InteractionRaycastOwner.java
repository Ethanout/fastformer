package io.github.fastformer.client.render.core;

import io.github.fastformer.fastplace.LongRangeBlockRaycast;
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
   private boolean placement;

   double distanceOr(double fallback) { return result == null ? fallback : result.distance(); }

   void clear() {
      player = null; level = null; result = null; start = null; direction = null; sampledAt = 0;
   }

   BlockHitResult clip(LocalPlayer currentPlayer, boolean forPlacement) {
      Vec3 eye = currentPlayer.getEyePosition(), view = currentPlayer.getViewVector(1.0F);
      long now = System.nanoTime();
      if (result == null || player != currentPlayer || level != currentPlayer.level() || placement != forPlacement
         || !eye.equals(start) || !view.equals(direction) || now - sampledAt > 16_000_000L) {
         result = forPlacement
            ? LongRangeBlockRaycast.clipForPlacement(currentPlayer.level(), currentPlayer, eye, view)
            : LongRangeBlockRaycast.clip(currentPlayer.level(), currentPlayer, eye, view);
         player = currentPlayer; level = currentPlayer.level(); placement = forPlacement;
         start = eye; direction = view; sampledAt = now;
      }
      return result.hit();
   }
}
