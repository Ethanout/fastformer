package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.core.math.Axis;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionPointerMailboxTest {
   private ClientInputSession input;

   @BeforeEach
   void prepare() {
      ClientSessionSystem.instance().operations().clearWorkspace();
      this.input = FastPlaceClientInput.inputSession();
      this.input.reset();
      var bounds = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO);
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, bounds, Map.of(), WorkspaceTransform.IDENTITY, false);
      ClientSessionSystem.instance().operations().workspace().addParts(List.of(part, part));
      ClientSessionSystem.instance().operations().selectAllWorkspaceParts();
      this.input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
   }

   @AfterEach
   void clear() {
      this.input.reset();
      ClientSessionSystem.instance().operations().clearWorkspace();
   }

   @Test
   void deselectedFixedPartCanBeSelectedAgainWithoutStartingANewDraft() {
      var workspace = ClientSessionSystem.instance().operations().workspace();
      assertTrue(workspace.beginEdit());
      workspace.updatePart(workspace.part(1).orElseThrow().fixed());
      workspace.finishEdit();
      var parts = List.copyOf(workspace.parts());
      ClientSessionSystem.instance().operations().deselectAllWorkspaceParts();
      postPress(1, 0, false, 10);
      postRelease(0, 20);
      drain();
      assertEquals(Set.of(1), workspace.selectedIds());
      assertEquals(parts, workspace.parts());
      assertFalse(ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive());
      assertPointerSettled();
   }

   @Test
   void movingBackWithinTheSameGestureKeepsSelectionFixed() {
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var original = workspace.part(1).orElseThrow();
      assertTrue(workspace.beginEdit());
      var token = workspace.activeEditToken();
      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(token, List.of(original), false,
         AxisGizmo.Operation.MOVE, Axis.X, 1, 2, Double.NaN);
      assertFalse(workspace.part(1).orElseThrow().canAdjustGeometry());
      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(token, List.of(original), false,
         AxisGizmo.Operation.MOVE, Axis.X, 1, 0, Double.NaN);
      assertEquals(original.transform(), workspace.part(1).orElseThrow().transform());
      assertFalse(workspace.part(1).orElseThrow().canAdjustGeometry());
      ClientSessionSystem.instance().operations().transforms().finishTransformGesture(token);
      assertFalse(workspace.part(1).orElseThrow().canAdjustGeometry());
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().undo());
      assertTrue(workspace.part(1).orElseThrow().canAdjustGeometry());
   }

   @Test
   void selectionControlsKeepTheirClicksWhileNewLocationsStartDrafts() {
      var part = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
      var face = new OperationInteractionIntent.Face(1, part.selection().bounds(), null, true);
      var gizmo = new OperationInteractionIntent.Gizmo(1, false, null, null);
      var label = new OperationInteractionIntent.Part(1, 1);
      var location = new OperationInteractionIntent.CreateSelection(new BlockPos(8, 0, 0));
      for (int button : List.of(0, 1)) {
         assertFalse(FastPlaceClientInput.acceptsSelectionDraftTarget(face, button));
         assertFalse(FastPlaceClientInput.acceptsSelectionDraftTarget(gizmo, button));
         assertFalse(FastPlaceClientInput.acceptsSelectionDraftTarget(label, button));
         assertTrue(FastPlaceClientInput.acceptsSelectionDraftTarget(location, button));
      }
      assertFalse(FastPlaceClientInput.acceptsSelectionDraftTarget(location, 2));
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().fixActiveSelection());
      assertTrue(FastPlaceClientInput.acceptsSelectionDraftTarget(location, 2));
   }

   @Test
   void retainedSessionStillExpandsItsRemainingUnmovedSelectionWithMiddle() {
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().cancelLastSelection());
      assertTrue(ClientSessionSystem.instance().operations().selectionSessionActive());
      assertFalse(ClientSessionSystem.instance().operations().selectionEdits().canStartSelectionDraft());
      assertTrue(ClientSessionSystem.instance().operations().workspace().parts().getFirst().canAdjustGeometry());
   }

   @Test
   void cancelRemovesOneSelectionAndExitsAfterTheLast() {
      var workspace = ClientSessionSystem.instance().operations().workspace();
      int active = workspace.activeId();
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().cancelLastSelection());
      assertEquals(1, workspace.size());
      assertTrue(workspace.part(active).isEmpty());
      assertTrue(ClientSessionSystem.instance().operations().selectionSessionActive());
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().cancelLastSelection());
      assertTrue(workspace.isEmpty());
      assertFalse(ClientSessionSystem.instance().operations().selectionSessionActive());
   }

   @Test
   void newClickPreservesExistingSelectionsBeforeStartingDraft() {
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleCreateClick(1, new BlockPos(8, 0, 0)));
      assertTrue(ClientSessionSystem.instance().operations().workspace().parts().stream().allMatch(ClientSelectionPart::canAdjustGeometry));
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive());
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().cancelLastSelection());
      assertEquals(2, ClientSessionSystem.instance().operations().workspace().size());
      assertFalse(ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive());
   }

   @Test
   void keyboardMouseAndScrollKeepPostingOrderAcrossTwoCompleteClicks() {
      var order = new ArrayList<String>();
      this.input.postKeyboard(new KeyboardInputSnapshot(65, 1, 1, 0, 10, false, false));
      var first = postPress(1, 0, false, 20);
      postRelease(0, 30);
      this.input.postScroll(new ScrollInputSnapshot(1));
      var second = postPress(2, 1, false, 40);
      postRelease(1, 50);
      assertEquals(Set.of(1, 2), ClientSessionSystem.instance().operations().workspace().selectedIds());
      assertTrue(this.input.blocksDraftLoad());

      this.input.drainPhysicalEvents(() -> true, key -> order.add("key"), scroll -> order.add("scroll"), event -> {
         dispatch(event);
         order.add(event instanceof SelectionPointerEvent.Press ? "press" : "release");
         if (event == first) assertEquals(Set.of(1), ClientSessionSystem.instance().operations().workspace().selectedIds());
         if (event == second) assertEquals(Set.of(2), ClientSessionSystem.instance().operations().workspace().selectedIds());
      });

      assertEquals(List.of("key", "press", "release", "scroll", "press", "release"), order);
      assertFalse(this.input.blocksDraftLoad());
      assertEquals(0, this.input.clickGestureToken);
      assertEquals(PointerGestureState.Kind.NONE, this.input.pointerGesture.kind());
   }

   @Test
   void capturedControlAndTimeRemainFixedUntilDispatch() {
      var press = postPress(1, 1, true, 1234);
      drain();
      assertEquals(Set.of(2), ClientSessionSystem.instance().operations().workspace().selectedIds());
      assertEquals(1234, press.snapshot().pressedAtNanos());
      assertTrue(this.input.blocksDraftLoad());
      assertNull(this.input.selectionPointer.release(0, 2000));
      postRelease(1, 3000);
      drain();
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void oldReleaseCannotClearTheNextDispatchedClick() {
      postPress(1, 0, false, 10);
      var oldRelease = this.input.selectionPointer.release(0, 20);
      this.input.postSelectionPointer(oldRelease);
      postPress(2, 1, false, 30);
      drain();
      assertTrue(this.input.selectionPointer.active());
      assertNull(this.input.selectionPointer.take(oldRelease.identity()));
      assertTrue(this.input.blocksDraftLoad());
      postRelease(1, 40);
      drain();
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void overlappingButtonsKeepBothPressesButOnlyTheNewestReleaseOwnsCapture() {
      postPress(1, 0, false, 10);
      postPress(2, 1, false, 20);
      assertNull(this.input.selectionPointer.release(0, 30));
      var selected = new ArrayList<Set<Integer>>();
      this.input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(), event -> {
         dispatch(event);
         if (event instanceof SelectionPointerEvent.Press) {
            selected.add(Set.copyOf(ClientSessionSystem.instance().operations().workspace().selectedIds()));
         }
      });
      assertEquals(List.of(Set.of(1), Set.of(2)), selected);
      assertTrue(this.input.blocksDraftLoad());
      postRelease(1, 40);
      drain();
      assertFalse(this.input.blocksDraftLoad());
      assertEquals(Set.of(2), ClientSessionSystem.instance().operations().workspace().selectedIds());
   }

   @Test
   void replacedSourceRejectsTheClickWithoutChangingSelectionOrStartingAnEdit() {
      postPress(1, 0, false, 10);
      postRelease(0, 20);
      var workspace = ClientSessionSystem.instance().operations().workspace();
      workspace.beginEdit();
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(5, 0, 0)));
      workspace.finishEdit();
      ClientSessionSystem.instance().operations().selectAllWorkspaceParts();
      long revision = workspace.revision();
      drain();
      assertEquals(Set.of(1, 2), workspace.selectedIds());
      assertEquals(revision, workspace.revision());
      assertFalse(workspace.editing());
      assertFalse(this.input.blocksDraftLoad());
   }

   @Test
   void contextLossClearsQueuedClicksAndTheirRestoreGuard() {
      postPress(1, 0, false, 10);
      this.input.drainPhysicalEvents(() -> false, key -> fail(), scroll -> fail(), click -> fail());
      assertFalse(this.input.blocksDraftLoad());
      assertNull(this.input.selectionPointer.release(0, 20));
      assertEquals(Set.of(1, 2), ClientSessionSystem.instance().operations().workspace().selectedIds());
   }

   @Test
   void earlierSubmissionRejectsTheQueuedSelectionWithoutKeepingItsCapture() {
      this.input.postKeyboard(new KeyboardInputSnapshot(257, 1, 1, 0, 10, false, false));
      postPress(1, 0, false, 20);
      postRelease(0, 30);
      this.input.drainPhysicalEvents(() -> true,
         key -> assertTrue(this.input.routing.submit(42L)), scroll -> fail(), SelectionPointerMailboxTest::dispatch);
      assertEquals(Set.of(1, 2), ClientSessionSystem.instance().operations().workspace().selectedIds());
      assertFalse(this.input.selectionPointer.active());
      assertEquals(ClientInputStateMachine.State.SUBMITTING, this.input.routing.state());
   }

   @Test
   void cancelDuringAnEarlierEventDropsTheEntireClickPair() {
      this.input.postKeyboard(new KeyboardInputSnapshot(81, 1, 1, 0, 10, false, false));
      postPress(1, 0, false, 20);
      postRelease(0, 30);
      this.input.drainPhysicalEvents(() -> true,
         key -> assertTrue(this.input.cancel()), scroll -> fail(), click -> fail());
      assertFalse(this.input.selectionPointer.active());
      assertEquals(Set.of(1, 2), ClientSessionSystem.instance().operations().workspace().selectedIds());
   }

   @Test
   void gizmoPressAndReleaseInOneBatchSettleTheEditAndRouting() {
      var press = postGizmoPress(0, 10);
      postRelease(0, 20);
      this.input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(), event -> {
         dispatch(event);
         if (event == press) {
            assertTrue(ClientSessionSystem.instance().operations().workspace().editing());
            assertTrue(ClientSessionSystem.instance().operations().selectionGestures().active());
            assertTrue(this.input.routing.accepts(this.input.clickGestureToken));
         }
      });
      assertPointerSettled();
   }

   @Test
   void releaseCommitsOnlyItsCapturedGizmoEdit() {
      postGizmoPress(0, 10);
      drain();
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var moved = workspace.part(1).orElseThrow().withTranslation(new BlockPos(5, 0, 0));
      workspace.updatePart(moved);
      postRelease(0, 20);
      drain();
      assertEquals(moved, workspace.part(1).orElseThrow());
      assertPointerSettled();
   }

   @Test
   void supersedingPressCancelsTheOldEditBeforeStartingTheNewDrag() {
      var original = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
      var oldPress = postGizmoPress(0, 10);
      drain();
      var oldCapture = ClientSessionSystem.instance().operations().selectionGestures().capture();
      // Capture the replacement before the old edit changes, so rollback restores its source.
      postGizmoPress(1, 20);
      ClientSessionSystem.instance().operations().workspace().updatePart(original.withTranslation(new BlockPos(5, 0, 0)));
      drain();
      assertEquals(original, ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().workspace().editing());
      assertNotSame(oldCapture, ClientSessionSystem.instance().operations().selectionGestures().capture());
      var newCapture = ClientSessionSystem.instance().operations().selectionGestures().capture();
      long newRouting = this.input.clickGestureToken;
      dispatch(new SelectionPointerEvent.Release(oldPress.identity(), 0, 30));
      assertSame(newCapture, ClientSessionSystem.instance().operations().selectionGestures().capture());
      assertEquals(newRouting, this.input.clickGestureToken);
      assertTrue(ClientSessionSystem.instance().operations().workspace().editing());
      postRelease(1, 40);
      drain();
      assertPointerSettled();
   }

   private SelectionPointerEvent.Press postGizmoPress(int button, long time) {
      return postGizmoPress(button, time, false);
   }

   private SelectionPointerEvent.Press postGizmoPress(int button, long time, boolean alt) {
      var gizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      var handle = gizmo.handles().stream().filter(value -> value.operation() == AxisGizmo.Operation.MOVE).findFirst().orElseThrow();
      var target = new OperationInteractionIntent.Gizmo(0, true, gizmo, new AxisGizmo.Hit(handle, Vec3.ZERO, 1.0, 0.1));
      var snapshot = SelectionPointerPress.capture(target, button, false, button == 1 ? 1 : -1,
         ClientSessionSystem.instance().operations().interactionScene(), ClientSessionSystem.instance().operations().workspace(), time).orElseThrow();
      return this.input.captureSelectionPress(snapshot, alt);
   }

   @Test
   void altGizmoConsumesTheModifierBeforeItsQueuedReleaseWithoutEndingTheDrag() {
      this.input.modifier.press(1, true, true);
      var press = postGizmoPress(0, 10, true);
      this.input.postKeyboard(new KeyboardInputSnapshot(342, 1, 0, 0, 20, false, false));
      this.input.drainPhysicalEvents(() -> true, key -> {
         var release = this.input.modifier.release(key.occurredAtNanos(), 100);
         assertFalse(release.shortPress());
         assertTrue(ClientSessionSystem.instance().operations().selectionGestures().active());
      }, scroll -> fail(), SelectionPointerMailboxTest::dispatch);
      assertTrue(press.alt());
      assertFalse(this.input.modifier.held());
      assertTrue(ClientSessionSystem.instance().operations().workspace().editing());
      postRelease(0, 30);
      drain();
      assertPointerSettled();
   }

   @Test
   void rejectedAltGizmoDoesNotConsumeTheCurrentModifierOrStartAnEdit() {
      this.input.modifier.press(1, true, true);
      postGizmoPress(0, 10, true);
      ClientSessionSystem.instance().operations().workspace().setLocked(true);
      postRelease(0, 20);
      drain();
      assertFalse(ClientSessionSystem.instance().operations().selectionGestures().active());
      assertFalse(ClientSessionSystem.instance().operations().workspace().editing());
      assertTrue(this.input.modifier.release(30, 100).shortPress());
      assertPointerSettled();
   }

   @Test
   void ordinaryPressDoesNotInheritAltFromTheLaterDispatchState() {
      postGizmoPress(0, 10, false);
      this.input.modifier.press(20, true, true);
      drain();
      assertFalse(this.input.modifier.consumed());
      this.input.modifier.release(30, 100);
      postRelease(0, 40);
      drain();
      assertPointerSettled();
   }

   @Test
   void draftPressReplacesTheDragUsingTheSamePhysicalCapture() {
      postGizmoPress(0, 10);
      drain();
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var original = workspace.part(1).orElseThrow();
      workspace.updatePart(original.withTranslation(new BlockPos(3, 0, 0)));
      this.input.captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), 1, new BlockPos(8, 0, 0), true, false, 20));
      assertNull(this.input.selectionPointer.release(0, 30));
      postRelease(1, 40);
      drain();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertTrue(ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive());
      assertPointerSettled();
   }

   @Test
   void replacementSnapshotFromCancelledGeometryCannotStartANewEdit() {
      postGizmoPress(0, 10);
      drain();
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var original = workspace.part(1).orElseThrow();
      workspace.updatePart(original.withTranslation(new BlockPos(5, 0, 0)));
      ClientSessionSystem.instance().operations().selectAllWorkspaceParts();
      postGizmoPress(1, 20);
      drain();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertFalse(workspace.editing());
      assertFalse(ClientSessionSystem.instance().operations().selectionGestures().active());
      postRelease(1, 30);
      drain();
      assertPointerSettled();
   }

   @Test
   void releaseCannotSettleADragThatReplacedItsCaptureOutsideTheQueue() {
      var press = postGizmoPress(0, 10);
      drain();
      var old = ClientSessionSystem.instance().operations().selectionGestures().gizmo();
      assertNotNull(old);
      long oldRouting = this.input.clickGestureToken;
      input.selectionGestures().cancel(old.capture());
      bindOwnedDrag(press);
      var replacement = ClientSessionSystem.instance().operations().selectionGestures().capture();
      long replacementRouting = this.input.clickGestureToken;
      // Restore the old dispatch association to model a release delayed past an external replacement.
      this.input.selectionPointer.dispatched(press.identity(), old.capture(), oldRouting);
      postRelease(0, 20);
      drain();
      assertSame(replacement, ClientSessionSystem.instance().operations().selectionGestures().capture());
      assertEquals(replacementRouting, this.input.clickGestureToken);
      assertTrue(ClientSessionSystem.instance().operations().workspace().editing());
   }

   @Test
   void faceReleaseSettlesItsCapturedEditWithoutRequiringWorldResampling() {
      var press = postPress(1, 0, false, 10);
      drain();
      bindOwnedDrag(press);
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var changed = workspace.part(1).orElseThrow().withTranslation(new BlockPos(3, 0, 0));
      workspace.updatePart(changed);
      postRelease(0, 20);
      drain();
      assertEquals(changed, workspace.part(1).orElseThrow());
      assertPointerSettled();
   }

   @Test
   void faceCancellationRestoresTheBaselineAndDoesNotCommitOnLateRelease() {
      var press = postPress(1, 0, false, 10);
      drain();
      bindOwnedDrag(press);
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var original = workspace.part(1).orElseThrow();
      workspace.updatePart(original.withTranslation(new BlockPos(3, 0, 0)));
      this.input.postSelectionPointer(new SelectionPointerEvent.Cancel(press.identity()));
      postRelease(0, 20);
      drain();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertPointerSettled();
   }

   @Test
   void contextCancellationRestoresTheEditAndIgnoresItsLateRelease() {
      var press = postPress(1, 0, false, 10);
      drain();
      bindOwnedDrag(press);
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var original = workspace.part(1).orElseThrow();
      workspace.updatePart(original.withTranslation(new BlockPos(3, 0, 0)));

      this.input.selectionGestures().cancel();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertFalse(workspace.editing());
      assertFalse(ClientSessionSystem.instance().operations().selectionGestures().active());
      assertEquals(PointerGestureState.Kind.NONE, this.input.pointerGesture.kind());
      this.input.selectionGestures().cancel();
      postRelease(0, 20);
      drain();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertPointerSettled();
   }

   // Queued settlement uses a real owned edit without requiring a client world.
   private void bindOwnedDrag(SelectionPointerEvent.Press press) {
      var gizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      var handle = gizmo.handles().stream().filter(value -> value.operation() == AxisGizmo.Operation.MOVE).findFirst().orElseThrow();
      var target = new OperationInteractionIntent.Gizmo(0, true, gizmo, new AxisGizmo.Hit(handle, Vec3.ZERO, 1.0, 0.1));
      assertTrue(input.selectionGestures().beginGizmo(FastPlaceClientInput.selectionGizmoPress(target, press.snapshot().button(), false)));
      var capture = input.selectionGestures().capture();
      this.input.clickGestureToken = this.input.routing.beginGesture(press.snapshot().button());
      this.input.operationClick.capture(press.snapshot().button());
      this.input.selectionPointer.dispatched(press.identity(), capture, this.input.clickGestureToken);
   }

   private void assertPointerSettled() {
      assertFalse(ClientSessionSystem.instance().operations().workspace().editing());
      assertFalse(ClientSessionSystem.instance().operations().selectionGestures().active());
      assertFalse(this.input.selectionPointer.active());
      assertFalse(this.input.blocksDraftLoad());
      assertEquals(0, this.input.clickGestureToken);
      assertEquals(-1, this.input.operationClick.buttonId());
      assertEquals(PointerGestureState.Kind.NONE, this.input.pointerGesture.kind());
   }

   private SelectionPointerEvent.Press postPress(int id, int button, boolean control, long time) {
      var press = SelectionPointerPress.capture(new OperationInteractionIntent.Part(id, 1), button, control,
         button == 1 ? 1 : -1, ClientSessionSystem.instance().operations().interactionScene(), ClientSessionSystem.instance().operations().workspace(), time).orElseThrow();
      return this.input.captureSelectionPress(press);
   }

   private void postRelease(int button, long time) {
      this.input.postSelectionPointer(this.input.selectionPointer.release(button, time));
   }

   private void drain() {
      this.input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(), SelectionPointerMailboxTest::dispatch);
   }

   private static void dispatch(SelectionPointerEvent event) {
      SelectionInputDispatcher.dispatch(null, FastPlaceClientInput.inputSession(), event);
   }
}
