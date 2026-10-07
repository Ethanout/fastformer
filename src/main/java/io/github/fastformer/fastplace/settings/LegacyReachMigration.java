package io.github.fastformer.fastplace.settings;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Restores only the base ranges written by the removed distance controller. */
public final class LegacyReachMigration {
   private LegacyReachMigration() { }
   public static void apply(ServerPlayer player) {
      var data = player.getPersistentData();
      if (!data.getBoolean("fastformerReachInitialized")) return;
      var settings = data.getCompound("fastformer");
      double far = settings.contains("reachFar") ? settings.getInt("reachFar") : 20;
      var block = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
      var entity = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
      if (block != null && (block.getBaseValue() == far - 0.5 || block.getBaseValue() == far))
         block.setBaseValue(Attributes.BLOCK_INTERACTION_RANGE.value().getDefaultValue());
      if (entity != null && (entity.getBaseValue() == far - 2 || entity.getBaseValue() == far))
         entity.setBaseValue(Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue());
      data.remove("fastformerReachInitialized");
      settings.remove("reachClose");
      settings.remove("reachFar");
      settings.remove("attributeReach");
   }
}
