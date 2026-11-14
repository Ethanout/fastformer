package io.github.fastformer.client.interaction;

import io.github.fastformer.common.placement.InteractionUpdateScope;
import io.github.fastformer.protocol.s2c.settings.InteractionUpdatesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import java.util.function.Supplier;

public final class ClientInteractionUpdates {
   private static final java.util.Map<net.minecraft.world.entity.player.Player, InteractionUpdatesPayload> POLICIES = new java.util.WeakHashMap<>();
   private ClientInteractionUpdates() {}

   public static void receive(InteractionUpdatesPayload payload) {
      var player = Minecraft.getInstance().player;
      if (player != null) {
         POLICIES.put(player, payload);
      }
   }

   public static boolean suppresses(Player player) {
      var policy = POLICIES.get(player);
      return player != null && player.isCreative() && policy != null && policy.suppressNeighbors();
   }

   public static <T> T interact(Level level, Player player, Supplier<T> action) {
      if (!level.isClientSide) return action.get();
      return InteractionUpdateScope.interact(level, suppresses(player), action);
   }

   public static boolean wrenchEnabled(net.minecraft.world.entity.player.Player player) {
      var policy = POLICIES.get(player);
      return policy != null && policy.wrenchEnabled();
   }

   public static boolean forcePlacement(net.minecraft.world.entity.player.Player player) {
      var policy = POLICIES.get(player);
      return policy != null && policy.forcePlacement();
   }

   public static boolean skipRepeatedCropStages(net.minecraft.world.entity.player.Player player) {
      var policy = POLICIES.get(player);
      return policy == null || policy.skipRepeatedCropStages();
   }

}
