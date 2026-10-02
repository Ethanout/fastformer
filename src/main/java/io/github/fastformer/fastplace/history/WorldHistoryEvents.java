package io.github.fastformer.fastplace.history;

import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.network.payload.world.WorldHistoryEventPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

final class WorldHistoryEvents {
   private static final Logger LOGGER = LogUtils.getLogger();
   private WorldHistoryEvents() { }
   static void send(WorldTaskContext context, WorldChangeBatch batch, WorldHistoryEventPayload.Kind kind, boolean complete) {
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
