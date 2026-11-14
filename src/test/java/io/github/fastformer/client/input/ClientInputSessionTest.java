package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.testkit.ClientScopes;
import io.github.fastformer.workspace.model.ClientSelectionPart;
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
      input.clickGestureToken = 42;
      input.undoPress.press(100);
      assertTrue(input.captureQuickShapeButton(1));
      input.modifier.press(100, true, true);
      input.undoPressCaptured = true;
      input.operationClick.capture(0);
      input.captureGeometryPointerButton(1);
      input.radialChordDown = true;
      input.pathClose.press(BlockPos.ZERO, true, 100);
      input.lastOperationPointLeftClickAt = 100;
      input.lastOperationPointLeftClickIndex = 2;
      input.lastOperationPointRightClickAt = 100;
      input.lastOperationPointRightClickIndex = 3;
      input.operationSessionWasActive = true;
      input.suppressedPausePausedSound = true;
      input.operationTransformCapture = new ClientInputSession.OperationTransformCapture(7L, 1L,
         io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped());

      input.reset();

      assertEquals(ClientInputStateMachine.State.IDLE, input.routing.state());
      assertFalse(input.routing.completeSubmission(request, ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.ADJUSTING));
      assertFalse(input.pointerGesture.owns(pointer, PointerGestureState.Kind.WORKSPACE_FACE));
      assertEquals(0, input.pointerGesture.activeToken());
      assertEquals(0, input.clickGestureToken);
      assertFalse(input.undoPress.release(101, 10));
      assertFalse(input.hasQuickShapeButtons());
      assertFalse(input.modifier.held());
      assertFalse(input.undoPressCaptured);
      assertEquals(-1, input.operationClick.buttonId());
      assertFalse(input.hasGeometryPointerButtons());
      assertFalse(input.radialChordDown);
      assertFalse(input.pathClose.press(BlockPos.ZERO, true, 101));
      assertEquals(0, input.lastOperationPointLeftClickAt);
      assertEquals(-1, input.lastOperationPointLeftClickIndex);
      assertEquals(0, input.lastOperationPointRightClickAt);
      assertEquals(-1, input.lastOperationPointRightClickIndex);
      assertFalse(input.operationSessionWasActive);
      assertFalse(input.suppressedPausePausedSound);
      assertNull(input.operationTransformCapture);
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
      var player = ClientScopes.create(UUID.randomUUID());
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
   void suspendedDraftNeverRestoresInputOrSubmissionWait() {
      var player = ClientScopes.create(UUID.randomUUID());
      player.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      player.inputSession().routing.observe(ClientInputStateMachine.State.ADJUSTING);
      UUID request = UUID.randomUUID();
      assertTrue(player.inputSession().routing.submit(request));
      player.inputSession().modifier.press(100, true, true);
      player.suspendOperationDraft(null, OperationSubmissionOrigin.LOCAL_ONLY, request);
      assertTrue(player.suspendedOperationDraft());
      assertEquals(ClientInputStateMachine.State.IDLE, player.inputSession().routing.state());
      assertFalse(player.inputSession().modifier.held());
      assertTrue(player.restoreSuspendedOperationDraft(null));
      assertEquals(1, player.workspace().size());
      assertEquals(ClientInputStateMachine.State.IDLE, player.inputSession().routing.state());
      assertFalse(player.inputSession().routing.completeSubmission(request,
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.ADJUSTING));
   }

   @Test
   void draftLoadWaitsForSubmissionAndPointerEvenWithoutSelectionData() throws Exception {
      var player = ClientScopes.create(UUID.randomUUID());
      assertEquals(false, player.hasLiveWork());
      player.inputSession().routing.observe(ClientInputStateMachine.State.SELECTING);
      assertEquals(false, player.hasLiveWork());
      player.inputSession().routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertEquals(false, player.hasLiveWork());
      player.inputSession().routing.observe(ClientInputStateMachine.State.BUILDING);
      player.inputSession().routing.submit(17L);
      assertEquals(true, player.hasLiveWork());
      player.resetInput();
      player.inputSession().pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      assertEquals(true, player.hasLiveWork());
      player.resetInput();
      assertEquals(false, player.hasLiveWork());
   }

}
