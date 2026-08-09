package io.github.fastformer.fastplace;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Per-tick view of a world-owned task. The durable task stores only its owner
 * UUID and dimension; a ServerPlayer is looked up opportunistically for UI.
 */
record WorldTaskContext(MinecraftServer server, UUID owner) {
   ServerLevel level(ResourceKey<Level> dimension) {
      return this.server == null ? null : this.server.getLevel(dimension);
   }

   ServerPlayer onlinePlayer() {
      return this.server == null ? null : this.server.getPlayerList().getPlayer(this.owner);
   }

   void actionBar(Component message) {
      ServerPlayer player = this.onlinePlayer();
      if (player != null) {
         FastPlaceMessages.actionBar(player, message);
      } else {
         WorldTaskFeature.deferActionBar(this.owner, message);
      }
   }

   void chat(Component message) {
      ServerPlayer player = this.onlinePlayer();
      if (player != null) {
         FastPlaceMessages.chat(player, message);
      } else {
         WorldTaskFeature.deferChat(this.owner, message);
      }
   }
}
