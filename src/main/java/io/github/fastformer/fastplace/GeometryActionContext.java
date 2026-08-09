package io.github.fastformer.fastplace;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public record GeometryActionContext(ServerPlayer player, boolean modifierHeld, GeometryHit hit) {
   public GeometryActionContext(ServerPlayer player, boolean modifierHeld) {
      this(player, modifierHeld, null);
   }

   public Vec3 eye() {
      return this.player.getEyePosition();
   }

   public Vec3 view() {
      return this.player.getViewVector(1.0F);
   }

   public BlockHitResult raycast(double range) {
      return ServerInputDispatcher.raycastBlocks(this.player, range);
   }

   public boolean hasBlockHit(BlockHitResult hit) {
      return hit != null && hit.getType() == HitResult.Type.BLOCK;
   }
}
