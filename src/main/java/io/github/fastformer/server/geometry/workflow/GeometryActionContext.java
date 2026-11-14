package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.ConePlaneMode;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public record GeometryActionContext(ServerPlayer player, boolean modifierHeld, GeometryHit hit, Vec3 eye, Vec3 view,
   Preferences preferences) {
   public record Preferences(ConePlaneMode conePlaneMode, Consumer<ConePlaneMode> saveConePlaneMode) {
      public static final Preferences NONE = new Preferences(ConePlaneMode.RADIUS, ignored -> { });
   }

   public GeometryActionContext(ServerPlayer player, boolean modifierHeld, GeometryHit hit, Vec3 eye, Vec3 view) {
      this(player, modifierHeld, hit, eye, view, Preferences.NONE);
   }
   public GeometryActionContext(ServerPlayer player, boolean modifierHeld) {
      this(player, modifierHeld, null);
   }

   public GeometryActionContext(ServerPlayer player, boolean modifierHeld, GeometryHit hit) {
      this(player, modifierHeld, hit, player == null ? null : player.getEyePosition(),
         player == null ? null : player.getViewVector(1.0F));
   }
}
