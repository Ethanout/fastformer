package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.operation.input.OperationPointCommandEvent;
import io.github.fastformer.client.operation.input.OperationPointCommandPress;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationSelectPointPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sends operation point commands after the input route has accepted them. */
final class OperationPointCommandDispatcher {
   private OperationPointCommandDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, OperationPointCommandEvent event) {
      if (!(event instanceof OperationPointCommandEvent.Press press)) return;
      var value = press.snapshot();
      var operations = ClientSessionSystem.instance().operations();
      if (session.routing.dispatch(io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind.POINTER)
         != io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.OPERATION
         || !value.owner().equals(operations.interactionScene().owner())
         || !value.selection().equals(operations.remoteSelectionIdentity())
         || value.revision() != operations.remoteSelectionRevision()
         || !value.callbackScope().equals(operations.remoteSelectionCallbackScope())) return;
      long requestId = value.occurredAtNanos() & Long.MAX_VALUE;
      if (requestId == 0L) requestId = 1L;
      if (value.insertEdge()) {
         if (ServerGateway.supports(minecraft, OperationInsertPointPayload.TYPE)) {
            ServerGateway.send(new OperationInsertPointPayload(
               requestId, value.revision(), value.callbackScope(), value.eye(), value.view()));
         }
      } else if (ServerGateway.supports(minecraft, OperationSelectPointPayload.TYPE)) {
         ServerGateway.send(new OperationSelectPointPayload(
            requestId, value.revision(), value.callbackScope(), value.pointIndex(), value.eye(), value.view()));
      }
   }

   static boolean insertEdge(Minecraft minecraft) {
      if (ClientSessionSystem.instance().operations().remotePointer().operationPrismEdgeInsertion() == null
         || !ServerGateway.supports(minecraft, OperationInsertPointPayload.TYPE)) return false;
      ClientInputSession session = FastPlaceClientInput.currentSession();
      session.captureOperationPointCommand(OperationPointCommandPress.insert(System.nanoTime()));
      return true;
   }

   static boolean queueNext(ClientInputSession session, Minecraft minecraft) {
      if (!ClientSessionSystem.instance().operations().operationPrism()
         || ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() == null
         || !ServerGateway.supports(minecraft, OperationPointPayload.TYPE)) return false;
      OperationPointPayload.Role role = ClientSessionSystem.instance().operations().remotePointer().operationNeedsFirst()
         ? OperationPointPayload.Role.FIRST
         : ClientSessionSystem.instance().operations().remotePointer().operationNeedsSecond()
         ? OperationPointPayload.Role.SECOND : OperationPointPayload.Role.EXTRA;
      session.postRemoteSelectionPoint(RemoteSelectionPointDispatcher.capture(role));
      return true;
   }

   static boolean select(Minecraft minecraft) {
      if (!ClientSessionSystem.instance().operations().operationPrism()) return false;
      int index = ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex();
      if (index < 0 || !ServerGateway.supports(minecraft, OperationSelectPointPayload.TYPE)) return false;
      FastPlaceClientInput.currentSession().captureOperationPointCommand(OperationPointCommandPress.select(System.nanoTime()));
      return true;
   }
}
