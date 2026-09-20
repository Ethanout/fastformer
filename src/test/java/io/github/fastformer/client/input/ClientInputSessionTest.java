package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.session.ClientPlayerSession;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.client.session.OperationSubmissionOrigin;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientInputSessionTest {
   @Test
   void resetInvalidatesRequestsGesturesModifiersAndPressHistory() {
      var input = new ClientInputSession();
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      var request = UUID.randomUUID();
      assertTrue(input.routing.submit(request));
      long pointer = input.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      input.pointerGestureToken = pointer;
      input.clickGestureToken = 42;
      input.undoPress.press(100);
      input.buildingRightPress.press();
      assertTrue(input.buildingRightPress.consume());
      input.modifier.press(100, true, true);
      input.undoPressCaptured = true;
      input.operationClickCapturedButton = 0;
      input.geometryClickCapturedButton = 1;
      input.radialChordDown = true;
      input.pathClose.press(BlockPos.ZERO, true, 100);
      input.lastOperationPointLeftClickAt = 100;
      input.lastOperationPointLeftClickIndex = 2;
      input.lastOperationPointRightClickAt = 100;
      input.lastOperationPointRightClickIndex = 3;
      input.operationSessionWasActive = true;
      input.suppressedPausePausedSound = true;

      input.reset();

      assertEquals(ClientInputStateMachine.State.IDLE, input.routing.state());
      assertFalse(input.routing.completeSubmission(request, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING));
      assertFalse(input.pointerGesture.owns(pointer, PointerGestureState.Kind.WORKSPACE_FACE));
      assertEquals(0, input.pointerGestureToken);
      assertEquals(0, input.clickGestureToken);
      assertFalse(input.undoPress.release(101, 10));
      assertTrue(input.buildingRightPress.consume());
      assertFalse(input.modifier.held());
      assertFalse(input.undoPressCaptured);
      assertEquals(-1, input.operationClickCapturedButton);
      assertEquals(-1, input.geometryClickCapturedButton);
      assertFalse(input.radialChordDown);
      assertFalse(input.pathClose.press(BlockPos.ZERO, true, 101));
      assertEquals(0, input.lastOperationPointLeftClickAt);
      assertEquals(-1, input.lastOperationPointLeftClickIndex);
      assertEquals(0, input.lastOperationPointRightClickAt);
      assertEquals(-1, input.lastOperationPointRightClickIndex);
      assertFalse(input.operationSessionWasActive);
      assertFalse(input.suppressedPausePausedSound);
      long next = input.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      assertNotEquals(pointer, next);
   }

   @Test
   void submissionCompletionIsAppliedOnlyAtTheTickBoundary() {
      var input = new ClientInputSession();
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      UUID request = UUID.randomUUID();
      assertTrue(input.routing.submit(request));
      input.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(request),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING));

      assertEquals(ClientInputStateMachine.State.SUBMITTING, input.routing.state());
      input.drainSubmissionEvents();
      assertEquals(ClientInputStateMachine.State.ADJUSTING, input.routing.state());
   }

   @Test
   void playerInputResetKeepsDurableDataAndInvalidatesSelectionObjects() {
      var player = new ClientPlayerSession(UUID.randomUUID());
      var selection = player.selectionSession();
      selection.addDraftPoint(BlockPos.ZERO);
      selection.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      var before = selection.draftState();
      var owner = selection.interactionOwnerId();
      var part = selection.workspace().part(1).orElseThrow();
      assertTrue(selection.workspace().beginEdit());
      selection.workspace().updatePart(part.withTranslation(new BlockPos(5, 0, 0)));
      var input = player.inputSession();
      input.modifier.press(100, true, true);
      selection.setAltHeld(true);
      player.resetInput();
      assertSame(input, player.inputSession());
      assertFalse(input.modifier.held());
      assertFalse(selection.altHeld());
      assertEquals(before, selection.draftState());
      assertEquals(1, selection.workspace().size());
      assertEquals(part, selection.workspace().part(1).orElseThrow());
      assertFalse(selection.workspace().editing());
      assertNotEquals(owner, selection.interactionOwnerId());
      assertEquals(selection.interactionOwnerId(), selection.interactionScene().owner());
   }

   @Test
   void resetDiscardsQueuedCompletionEvenWhenRequestIdentityIsReused() {
      var input = new ClientInputSession();
      UUID request = UUID.randomUUID();
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(input.routing.submit(request));
      input.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(request),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING));

      input.reset();
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(input.routing.submit(request));
      input.drainSubmissionEvents();

      assertEquals(ClientInputStateMachine.State.SUBMITTING, input.routing.state());
      assertTrue(input.routing.completeSubmission(request,
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.ADJUSTING));
   }

   @Test
   void duplicateCompletionCannotFinishTheNextRequest() {
      var input = new ClientInputSession();
      UUID first = UUID.randomUUID();
      UUID next = UUID.randomUUID();
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(input.routing.submit(first));
      var completion = new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(first),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING);
      input.postSubmissionCompleted(completion);
      input.drainSubmissionEvents();
      assertTrue(input.routing.submit(next));

      input.postSubmissionCompleted(completion);
      input.drainSubmissionEvents();

      assertEquals(ClientInputStateMachine.State.SUBMITTING, input.routing.state());
      assertTrue(input.routing.completeSubmission(next,
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.ADJUSTING));
   }

   @Test
   void scopesKeepSeparateOwnersAndReentryDoesNotRestoreSubmissionWait() throws Exception {
      var constructor = ClientSessionManager.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      var manager = constructor.newInstance();
      UUID playerId = UUID.randomUUID();
      var overworld = activate(manager, playerId, "server", "overworld");
      var oldInput = overworld.inputSession();
      oldInput.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(oldInput.routing.submit(UUID.randomUUID()));
      oldInput.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      assertSame(overworld, activate(manager, playerId, "server", "overworld"));
      assertEquals(ClientInputStateMachine.State.SUBMITTING, oldInput.routing.state());

      var nether = activate(manager, playerId, "server", "nether");
      assertNotSame(oldInput, nether.inputSession());
      assertEquals(ClientInputStateMachine.State.IDLE, oldInput.routing.state());
      assertEquals(PointerGestureState.Kind.NONE, oldInput.pointerGesture.kind());
      nether.inputSession().routing.observe(ClientInputStateMachine.State.BUILDING);
      assertSame(overworld, activate(manager, playerId, "server", "overworld"));
      assertEquals(ClientInputStateMachine.State.IDLE, oldInput.routing.state());
      assertEquals(ClientInputStateMachine.State.IDLE, nether.inputSession().routing.state());
   }

   @Test
   void changingPlayerOrServerClearsInputWithoutDiscardingLocalWork() throws Exception {
      var constructor = ClientSessionManager.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      var manager = constructor.newInstance();
      UUID playerId = UUID.randomUUID();
      var first = activate(manager, playerId, "a", "overworld");
      first.selectionSession().addDraftPoint(BlockPos.ZERO);
      first.inputSession().routing.observe(ClientInputStateMachine.State.BUILDING);
      var second = activate(manager, UUID.randomUUID(), "a", "overworld");
      assertEquals(ClientInputStateMachine.State.IDLE, first.inputSession().routing.state());
      assertTrue(first.selectionSession().hasDraft());
      second.inputSession().modifier.press(100, true, true);
      var third = activate(manager, playerId, "b", "overworld");
      assertFalse(second.inputSession().modifier.held());
      assertNotSame(first.inputSession(), third.inputSession());
   }

   @Test
   void suspendedDraftNeverRestoresInputOrSubmissionWait() {
      var player = new ClientPlayerSession(UUID.randomUUID());
      player.operationWorkspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      player.inputSession().routing.observe(ClientInputStateMachine.State.ADJUSTING);
      UUID request = UUID.randomUUID();
      assertTrue(player.inputSession().routing.submit(request));
      player.inputSession().modifier.press(100, true, true);
      player.suspendOperationDraft(null, OperationSubmissionOrigin.LOCAL_ONLY, request);
      assertTrue(player.suspendedOperationDraft());
      assertEquals(ClientInputStateMachine.State.IDLE, player.inputSession().routing.state());
      assertFalse(player.inputSession().modifier.held());
      assertTrue(player.restoreSuspendedOperationDraft(null));
      assertEquals(1, player.operationWorkspace().size());
      assertEquals(ClientInputStateMachine.State.IDLE, player.inputSession().routing.state());
      assertFalse(player.inputSession().routing.completeSubmission(request,
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.ADJUSTING));
   }

   @Test
   void draftLoadWaitsForSubmissionAndPointerEvenWithoutSelectionData() throws Exception {
      var player = new ClientPlayerSession(UUID.randomUUID());
      var gate = ClientSessionManager.class.getDeclaredMethod("liveWorkBlocksDraftRead", ClientPlayerSession.class);
      gate.setAccessible(true);
      assertEquals(false, gate.invoke(null, player));
      player.inputSession().routing.observe(ClientInputStateMachine.State.SELECTING);
      assertEquals(false, gate.invoke(null, player));
      player.inputSession().routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertEquals(false, gate.invoke(null, player));
      player.inputSession().routing.observe(ClientInputStateMachine.State.BUILDING);
      player.inputSession().routing.submit(17L);
      assertEquals(true, gate.invoke(null, player));
      player.resetInput();
      player.inputSession().pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      assertEquals(true, gate.invoke(null, player));
      player.resetInput();
      assertEquals(false, gate.invoke(null, player));
   }

   private static ClientPlayerSession activate(ClientSessionManager manager, UUID player, String server, String dimension)
      throws Exception {
      var method = ClientSessionManager.class.getDeclaredMethod("activateScope", UUID.class, String.class, String.class);
      method.setAccessible(true);
      return (ClientPlayerSession)method.invoke(manager, player, server, dimension);
   }
}
