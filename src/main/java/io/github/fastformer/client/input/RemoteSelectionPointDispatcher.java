package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import java.util.function.Consumer;

final class RemoteSelectionPointDispatcher {
   private RemoteSelectionPointDispatcher() { }

   static RemoteSelectionPointRequest capture(OperationPointPayload.Role role) {
      return new RemoteSelectionPointRequest(ClientOperationController.interactionScene().owner(),
         ClientOperationController.draftSelectionMode(), ClientOperationController.remoteSelectionIdentity(), role);
   }

   static void dispatch(RemoteSelectionPointRequest request, ClientInputStateMachine routing,
      Consumer<OperationPointPayload> send) {
      var route = routing.dispatch(ClientInputStateMachine.InputKind.POINTER);
      if (route != ClientInputStateMachine.Dispatch.OPERATION && route != ClientInputStateMachine.Dispatch.VANILLA) return;
      if (ClientOperationController.workspaceSubmissionPending() || ClientOperationController.workspace().locked()
         || ClientOperationController.active() || ClientOperationController.selectionDraftActive()
         || !request.matches(ClientOperationController.interactionScene().owner(), ClientOperationController.draftSelectionMode(),
            ClientOperationController.remoteSelectionIdentity())) return;
      send.accept(new OperationPointPayload(request.role()));
   }
}
