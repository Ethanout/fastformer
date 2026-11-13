package io.github.fastformer.client.network.outbound;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** One capability-aware boundary for sending optional requests to the current server. */
public final class ServerGateway {
   private ServerGateway() {}

   public static boolean supports(Minecraft client, CustomPacketPayload.Type<?> type) {
      return client != null && client.getConnection() != null && NetworkRegistry.hasChannel(client.getConnection(), type.id());
   }

   public static boolean trySend(CustomPacketPayload payload) {
      return trySend(Minecraft.getInstance(), payload);
   }

   public static boolean trySend(Minecraft client, CustomPacketPayload payload) {
      if (!supports(client, payload.type())) return false;
      PacketDistributor.sendToServer(payload);
      return true;
   }
}
