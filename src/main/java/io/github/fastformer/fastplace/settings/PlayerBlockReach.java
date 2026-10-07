package io.github.fastformer.fastplace.settings;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Uses the vanilla attribute as the only stored block interaction range. */
public final class PlayerBlockReach {
   public static final int DEFAULT_REACH = 20;
   private static final String INITIALIZED = "fastformerBlockReachInitialized";

   private PlayerBlockReach() { }

   public static void initialize(ServerPlayer player) {
      if (player.getPersistentData().getBoolean(INITIALIZED)) return;
      var attribute = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
      if (attribute.getBaseValue() == Attributes.BLOCK_INTERACTION_RANGE.value().getDefaultValue()) {
         attribute.setBaseValue(DEFAULT_REACH);
      }
      player.getPersistentData().putBoolean(INITIALIZED, true);
   }

   public static void copy(ServerPlayer original, ServerPlayer replacement) {
      replacement.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(
         original.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).getBaseValue());
      replacement.getPersistentData().putBoolean(INITIALIZED, original.getPersistentData().getBoolean(INITIALIZED));
      initialize(replacement);
   }
}
