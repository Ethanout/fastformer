package io.github.fastformer.client.input;

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
      if (event.selectionMove() != null) {
         var move = event.selectionMove();
         if (route == ClientInputStateMachine.Dispatch.OPERATION
            && !ClientOperationController.workspaceSubmissionPending()
            && move.matches(ClientOperationController.interactionScene().owner(), ClientOperationController.workspace())) {
            ClientOperationController.moveSelected(move.offset());
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
