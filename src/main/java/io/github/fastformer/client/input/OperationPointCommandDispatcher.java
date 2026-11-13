package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.input.OperationPointCommandEvent;
import io.github.fastformer.client.operation.input.OperationPointCommandPress;
import io.github.fastformer.client.render.core.FastPlaceClientPreviewCore;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationSelectPointPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Sends operation point commands after the input route has accepted them. */
final class OperationPointCommandDispatcher {
   private OperationPointCommandDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, OperationPointCommandEvent event) {
      if (!(event instanceof OperationPointCommandEvent.Press press)) return;
      var value = press.snapshot();
      long requestId = value.occurredAtNanos() & Long.MAX_VALUE;
      if (requestId == 0L) requestId = 1L;
      if (value.insertEdge()) {
         if (NetworkRegistry.hasChannel(minecraft.getConnection(), OperationInsertPointPayload.TYPE.id())) {
            PacketDistributor.sendToServer(new OperationInsertPointPayload(
               requestId, value.revision(), value.callbackScope(), value.eye(), value.view()), new CustomPacketPayload[0]);
         }
      } else if (NetworkRegistry.hasChannel(minecraft.getConnection(), OperationSelectPointPayload.TYPE.id())) {
         PacketDistributor.sendToServer(new OperationSelectPointPayload(
            requestId, value.revision(), value.callbackScope(), value.pointIndex(), value.eye(), value.view()), new CustomPacketPayload[0]);
      }
   }

   static boolean insertEdge(Minecraft minecraft) {
      if (FastPlaceClientPreviewCore.operationPrismEdgeInsertion() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationInsertPointPayload.TYPE.id())) return false;
      ClientInputSession session = FastPlaceClientInput.currentSession();
      session.captureOperationPointCommand(OperationPointCommandPress.insert(System.nanoTime()));
      return true;
   }

   static boolean queueNext(ClientInputSession session, Minecraft minecraft) {
      if (!ClientOperationController.operationPrism()
         || FastPlaceClientPreviewCore.operationCandidatePoint() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) return false;
      OperationPointPayload.Role role = FastPlaceClientPreviewCore.operationNeedsFirst()
         ? OperationPointPayload.Role.FIRST
         : FastPlaceClientPreviewCore.operationNeedsSecond()
         ? OperationPointPayload.Role.SECOND : OperationPointPayload.Role.EXTRA;
      session.postRemoteSelectionPoint(RemoteSelectionPointDispatcher.capture(role));
      return true;
   }

   static boolean select(Minecraft minecraft) {
      if (!ClientOperationController.operationPrism()) return false;
      int index = FastPlaceClientPreviewCore.operationPointUnderCrosshairIndex();
      if (index < 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationSelectPointPayload.TYPE.id())) return false;
      FastPlaceClientInput.currentSession().captureOperationPointCommand(OperationPointCommandPress.select(System.nanoTime()));
      return true;
   }
}
