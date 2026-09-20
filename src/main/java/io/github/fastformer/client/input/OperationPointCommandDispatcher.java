package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.operation.OperationInsertPointPayload;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import io.github.fastformer.network.payload.operation.OperationSelectPointPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Sends operation point commands after the input route has accepted them. */
final class OperationPointCommandDispatcher {
   private OperationPointCommandDispatcher() { }

   static boolean insertEdge(Minecraft minecraft) {
      if (FastPlaceClientPreview.operationPrismEdgeInsertion() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationInsertPointPayload.TYPE.id())) return false;
      PacketDistributor.sendToServer(OperationInsertPointPayload.INSTANCE, new CustomPacketPayload[0]);
      return true;
   }

   static boolean queueNext(ClientInputSession session, Minecraft minecraft) {
      if (!ClientOperationController.operationPrism()
         || FastPlaceClientPreview.operationCandidatePoint() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) return false;
      OperationPointPayload.Role role = FastPlaceClientPreview.operationNeedsFirst()
         ? OperationPointPayload.Role.FIRST
         : FastPlaceClientPreview.operationNeedsSecond()
         ? OperationPointPayload.Role.SECOND : OperationPointPayload.Role.EXTRA;
      session.postRemoteSelectionPoint(RemoteSelectionPointDispatcher.capture(role));
      return true;
   }

   static boolean select(Minecraft minecraft) {
      if (!ClientOperationController.operationPrism()) return false;
      int index = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      if (index < 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationSelectPointPayload.TYPE.id())) return false;
      PacketDistributor.sendToServer(new OperationSelectPointPayload(index), new CustomPacketPayload[0]);
      return true;
   }
}
