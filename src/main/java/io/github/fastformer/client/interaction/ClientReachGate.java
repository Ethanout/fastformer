package io.github.fastformer.client.interaction;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.geometry.raycast.ReachTransition;
import io.github.fastformer.fastplace.settings.ReachThresholds;
import io.github.fastformer.network.payload.settings.ReachSettingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Owns the client's distance latch; preview opacity does not own input. */
public final class ClientReachGate {
   private static final ReachTransition TRANSITION = new ReachTransition();
   private static ReachThresholds thresholds = ReachThresholds.DEFAULT;
   private static LocalPlayer owner;
   private static Level level;

   private ClientReachGate() { }

   public static void receive(ReachSettingsPayload payload) {
      thresholds = payload.thresholds();
   }

   public static boolean vanilla() {
      return io.github.fastformer.client.placement.QuickReplaceMode.active() || TRANSITION.vanilla();
   }

   public static boolean vanillaAt(LocalPlayer player, double distance) {
      return thresholds.vanillaAt(TRANSITION.vanilla(), distance, player.blockInteractionRange());
   }

   public static void update(Minecraft minecraft) {
      if (io.github.fastformer.client.placement.QuickReplaceMode.active()) { TRANSITION.reset(); return; }
      var player = minecraft.player;
      if (owner != player || level != minecraft.level) {
         TRANSITION.reset();
         owner = player;
         level = minecraft.level;
      }
      if (player == null || !player.isCreative() || minecraft.getCameraEntity() != player || !FastPlaceClientPreview.enabled()) {
         TRANSITION.reset();
         return;
      }
      var hit = FastPlaceClientPreview.reachRaycast(player);
      double distance = hit.getType() == HitResult.Type.BLOCK
         ? player.getEyePosition().distanceTo(hit.getLocation()) : Double.POSITIVE_INFINITY;
      boolean entityTarget = minecraft.hitResult instanceof EntityHitResult entity
         && player.getEyePosition().distanceTo(entity.getLocation()) <= distance;
      if (entityTarget) distance = player.getEyePosition().distanceTo(minecraft.hitResult.getLocation());
      TRANSITION.update(distance, thresholds, entityTarget
         ? player.entityInteractionRange() : player.blockInteractionRange());
   }

   public static void reset() {
      TRANSITION.reset();
      thresholds = ReachThresholds.DEFAULT;
      owner = null;
      level = null;
   }
}
