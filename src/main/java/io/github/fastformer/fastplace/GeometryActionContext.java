package io.github.fastformer.fastplace;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public record GeometryActionContext(ServerPlayer player, boolean modifierHeld, GeometryHit hit, Vec3 eye, Vec3 view) {
   public GeometryActionContext(ServerPlayer player, boolean modifierHeld) {
      this(player, modifierHeld, null);
   }

   public GeometryActionContext(ServerPlayer player, boolean modifierHeld, GeometryHit hit) {
      this(player, modifierHeld, hit, player == null ? null : player.getEyePosition(),
         player == null ? null : player.getViewVector(1.0F));
   }
}
