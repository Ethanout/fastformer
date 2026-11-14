package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import java.util.function.Consumer;

final class RemoteSelectionPointDispatcher {
   private RemoteSelectionPointDispatcher() { }

   static RemoteSelectionPointRequest capture(OperationPointPayload.Role role) {
      return new RemoteSelectionPointRequest(ClientSessionSystem.instance().operations().interactionScene().owner(),
         ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), ClientSessionSystem.instance().operations().remoteSelectionIdentity(), role);
   }

   static void dispatch(RemoteSelectionPointRequest request, ClientInputStateMachine routing,
      Consumer<OperationPointPayload> send) {
      var route = routing.dispatch(ClientInputStateMachine.InputKind.POINTER);
      if (route != ClientInputStateMachine.Dispatch.OPERATION && route != ClientInputStateMachine.Dispatch.VANILLA) return;
      if (ClientSessionSystem.instance().operations().workspaceSubmissionPending() || ClientSessionSystem.instance().operations().workspace().locked()
         || ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()
         || !request.matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(),
            ClientSessionSystem.instance().operations().remoteSelectionIdentity())) return;
      send.accept(new OperationPointPayload(request.role()));
   }
}
