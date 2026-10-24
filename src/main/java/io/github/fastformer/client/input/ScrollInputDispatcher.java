package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.geometry.ScrollCandidatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class ScrollInputDispatcher {
   private ScrollInputDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, ScrollInputSnapshot event) {
      var route = session.routing.dispatch(ClientInputStateMachine.InputKind.SCROLL);
      if (event.selectionAction() != null) {
         var action = event.selectionAction();
         if (route == ClientInputStateMachine.Dispatch.OPERATION
            && !ClientOperationController.workspaceSubmissionPending()
            && action.matches(ClientOperationController.interactionScene().owner(), ClientOperationController.workspace())) {
            switch (action.gesture()) {
               case io.github.fastformer.client.operation.input.SelectionScrollAction.Move move ->
                  ClientOperationController.scrollSelected(move.offset(), action.timestampNanos());
               case io.github.fastformer.client.operation.input.SelectionScrollAction.Stack stack ->
                  ClientOperationController.scrollStackSelected(stack, action.timestampNanos());
            }
         }
         return;
      }
      if (route != ClientInputStateMachine.Dispatch.BLOCKED
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ScrollCandidatePayload.TYPE.id())
         && FastPlaceClientPreview.usesScrollContext()) {
         PacketDistributor.sendToServer(new ScrollCandidatePayload(event.direction()));
         FastPlaceClientPreview.noteScrollFeedback();
      }
   }
}
