package io.github.fastformer.client.input;

import io.github.fastformer.network.payload.operation.OperationPointPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Owns the network boundary for accepted remote selection-point events. */
final class RemoteSelectionPointSender {
   private RemoteSelectionPointSender() { }

   static void sendIfAvailable(Minecraft minecraft, ClientInputSession session, RemoteSelectionPointRequest request) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) return;
      RemoteSelectionPointDispatcher.dispatch(request, session.routing,
         payload -> PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]));
   }
}
