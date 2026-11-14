package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.protocol.c2s.geometry.ScrollCandidatePayload;
import net.minecraft.client.Minecraft;

final class ScrollInputDispatcher {
   private ScrollInputDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, ScrollInputSnapshot event) {
      var route = session.routing.dispatch(ClientInputStateMachine.InputKind.SCROLL);
      if (event.selectionAction() != null) {
         var action = event.selectionAction();
         if (route == ClientInputStateMachine.Dispatch.OPERATION
            && !ClientSessionSystem.instance().operations().workspaceSubmissionPending()
            && action.matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace())) {
            switch (action.gesture()) {
               case io.github.fastformer.client.operation.input.SelectionScrollAction.Move move ->
                  ClientSessionSystem.instance().operations().transforms().scrollSelected(move.offset(), action.timestampNanos());
               case io.github.fastformer.client.operation.input.SelectionScrollAction.Stack stack ->
                  ClientSessionSystem.instance().operations().transforms().scrollStackSelected(stack, action.timestampNanos());
            }
         }
         return;
      }
      if (route != ClientInputStateMachine.Dispatch.BLOCKED
         && ServerGateway.supports(minecraft, ScrollCandidatePayload.TYPE)
         && ClientSessionSystem.instance().usesScrollContext()) {
         ServerGateway.send(new ScrollCandidatePayload(event.direction()));
         session.feedback().noteScroll(ClientSessionSystem.instance().previews().building(), System.nanoTime());
      }
   }
}
