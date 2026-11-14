package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Owns the network boundary for accepted remote selection-point events. */
final class RemoteSelectionPointSender {
   private RemoteSelectionPointSender() { }

   static void sendIfAvailable(Minecraft minecraft, ClientInputSession session, RemoteSelectionPointRequest request) {
      if (!ServerGateway.supports(minecraft, OperationPointPayload.TYPE)) return;
      RemoteSelectionPointDispatcher.dispatch(request, session.routing,
         payload -> ServerGateway.send(payload));
   }
}
