package io.github.fastformer.server.history.vanilla;

import io.github.fastformer.server.history.entry.HistoryEntry;

import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class WorldHistoryEvents {
   private static final Logger LOGGER = LogUtils.getLogger();
   private WorldHistoryEvents() { }
   public static void send(WorldTaskContext context, HistoryEntry batch, WorldHistoryEventPayload.Kind kind, boolean complete) {
      if (context.server() == null || context.server().getPlayerList() == null) return;
      var player = context.onlinePlayer();
      if (player != null && batch.operationId() != null && player.connection != null) {
         try {
            if (!player.connection.hasChannel(WorldHistoryEventPayload.TYPE)) return;
            PacketDistributor.sendToPlayer(player, new WorldHistoryEventPayload(batch.operationId(), batch.dimension().location(), kind, complete));
         } catch (RuntimeException failure) {
            // A disconnected client must not roll back a committed world transaction.
            LOGGER.warn("Could not send world history event {} to {}", batch.operationId(), context.owner(), failure);
         }
      }
   }
}
