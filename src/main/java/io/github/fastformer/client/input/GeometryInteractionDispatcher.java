package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.geometry.GeometryPointPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends accepted special-shape interaction commands and records their capture. */
final class GeometryInteractionDispatcher {
   private GeometryInteractionDispatcher() { }

   static void send(ClientInputSession session, GeometryInteractionTarget target,
      GeometryInteractionAction action, PointerGesture gesture, int mouseButton) {
      PacketDistributor.sendToServer(
         new GeometryInteractionPayload(target.type(), target.index(), action, gesture),
         new CustomPacketPayload[0]
      );
      session.geometryClickCapturedButton = mouseButton;
   }

   static void clearSelection(ClientInputSession session, PointerGesture gesture, int mouseButton) {
      PacketDistributor.sendToServer(
         GeometryInteractionPayload.clearSelection(gesture), new CustomPacketPayload[0]
      );
      session.geometryClickCapturedButton = mouseButton;
   }

   static void sendPoint(ClientInputSession session, int mouseButton) {
      PacketDistributor.sendToServer(GeometryPointPayload.INSTANCE, new CustomPacketPayload[0]);
      session.geometryClickCapturedButton = mouseButton;
   }
}
