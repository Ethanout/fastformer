package io.github.fastformer.fastplace.settings;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Initializes reach once, then leaves later attribute changes to their owners. */
public final class PlayerReachAttributes {
   private PlayerReachAttributes() { }

   public static void initialize(ServerPlayer player) {
      if (!player.isCreative() || !FastPlaceSettings.load(player).enabled() || player.getPersistentData().getBoolean("fastformerReachInitialized")) return;
      set(player, 20);
      player.getPersistentData().putBoolean("fastformerReachInitialized", true);
   }

   public static void set(ServerPlayer player, double reach) {
      player.getPersistentData().putBoolean("fastformerReachInitialized", true);
      player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(reach - (player.isCreative() ? 0.5 : 0));
      player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE).setBaseValue(reach - (player.isCreative() ? 2 : 0));
   }
}
