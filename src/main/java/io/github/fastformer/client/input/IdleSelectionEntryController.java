package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.network.payload.placement.IdleSelectionEntryResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Keeps a double-click selection in SUBMITTING until the server restores its source. */
final class IdleSelectionEntryController {
   record Pending(long requestId, Object level, boolean control, long occurredAt, IdleSelectionEntryResultPayload reply) { }
   private IdleSelectionEntryController() { }

   static boolean begin(Minecraft minecraft, ClientInputSession session, long token, Vec3 eye, Vec3 view, boolean control, long now) {
      long requestId = ClientPlacementRouter.startIdleSelection(minecraft, token, eye, view);
      if (requestId == 0) return false;
      session.idleSelectionEntry = new Pending(requestId, minecraft.level, control, now, null);
      return true;
   }

   static void receive(Minecraft minecraft, ClientInputSession session, IdleSelectionEntryResultPayload reply) {
      var pending = session.idleSelectionEntry;
      if (pending == null || pending.requestId() != reply.requestId() || pending.level() != minecraft.level
         || minecraft.level == null || !minecraft.level.dimension().location().equals(reply.dimension())
         || !session.routing.awaitsPlacementRequest(reply.requestId())) return;
      session.idleSelectionEntry = new Pending(pending.requestId(), pending.level(), pending.control(), pending.occurredAt(), reply);
      session.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(new ClientSemanticEvent.Submit.Placement(reply.requestId()),
         reply.accepted() ? ClientInputStateMachine.SubmissionEvent.SUCCEEDED : ClientInputStateMachine.SubmissionEvent.FAILED,
         ClientInputStateMachine.State.IDLE));
   }

   static void dispatch(Minecraft minecraft, ClientInputSession session) {
      var pending = session.idleSelectionEntry;
      if (pending == null || pending.reply() == null) return;
      session.idleSelectionEntry = null;
      if (!pending.reply().accepted() || pending.level() != minecraft.level || minecraft.screen != null || !minecraft.isWindowActive()
         || session.routing.state() != ClientInputStateMachine.State.IDLE) return;
      if (!FastPlaceClientInput.captureSelectionEntry(pending.reply().position(), pending.control(), pending.occurredAt())) return;
      var release = session.selectionPointer.release(io.github.fastformer.client.input.mouse.MouseButtonInputSemantics.RIGHT_BUTTON, System.nanoTime());
      if (release != null) session.postSelectionPointer(release);
   }
}
