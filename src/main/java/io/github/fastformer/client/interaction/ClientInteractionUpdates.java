package io.github.fastformer.client.interaction;

import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import io.github.fastformer.network.payload.settings.InteractionUpdatesPayload;
import net.minecraft.client.Minecraft;

public final class ClientInteractionUpdates {
   private static final java.util.Map<net.minecraft.world.entity.player.Player, InteractionUpdatesPayload> POLICIES = new java.util.WeakHashMap<>();
   private ClientInteractionUpdates() {}

   public static void receive(InteractionUpdatesPayload payload) {
      var player = Minecraft.getInstance().player;
      if (player != null) {
         InteractionUpdateScope.setClientPolicy(player, payload.suppressNeighbors());
         POLICIES.put(player, payload);
      }
   }

   public static boolean wrenchEnabled(net.minecraft.world.entity.player.Player player) {
      var policy = POLICIES.get(player);
      return policy != null && policy.wrenchEnabled();
   }

   public static boolean forcePlacement(net.minecraft.world.entity.player.Player player) {
      var policy = POLICIES.get(player);
      return policy != null && policy.forcePlacement();
   }
}
