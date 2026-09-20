package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.DragAxisFrame;
import io.github.fastformer.client.input.drag.DeferredDragClick;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
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
      ClientOperationController.clearWorkspace();
      this.input = FastPlaceClientInput.inputSession();
      this.input.reset();
      var bounds = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO);
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, bounds, Map.of(), WorkspaceTransform.IDENTITY, false);
      ClientOperationController.workspace().addParts(List.of(part, part));
      ClientOperationController.selectAllWorkspaceParts();
      this.input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
   }

   @AfterEach
   void clear() {
      this.input.reset();
      ClientOperationController.clearWorkspace();
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
      assertEquals(Set.of(1, 2), ClientOperationController.workspace().selectedIds());
      assertTrue(this.input.blocksDraftLoad());

      this.input.drainPhysicalEvents(() -> true, key -> order.add("key"), scroll -> order.add("scroll"), event -> {
         dispatch(event);
         order.add(event instanceof SelectionPointerEvent.Press ? "press" : "release");
         if (event == first) assertEquals(Set.of(1), ClientOperationController.workspace().selectedIds());
         if (event == second) assertEquals(Set.of(2), ClientOperationController.workspace().selectedIds());
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
      assertEquals(Set.of(2), ClientOperationController.workspace().selectedIds());
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
            selected.add(Set.copyOf(ClientOperationController.workspace().selectedIds()));
         }
      });
      assertEquals(List.of(Set.of(1), Set.of(2)), selected);
      assertTrue(this.input.blocksDraftLoad());
      postRelease(1, 40);
      drain();
      assertFalse(this.input.blocksDraftLoad());
      assertEquals(Set.of(2), ClientOperationController.workspace().selectedIds());
   }

   @Test
   void replacedSourceRejectsTheClickWithoutChangingSelectionOrStartingAnEdit() {
      postPress(1, 0, false, 10);
      postRelease(0, 20);
      var workspace = ClientOperationController.workspace();
      workspace.beginEdit();
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(5, 0, 0)));
      workspace.finishEdit();
      ClientOperationController.selectAllWorkspaceParts();
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
      assertEquals(Set.of(1, 2), ClientOperationController.workspace().selectedIds());
   }

   @Test
   void earlierSubmissionRejectsTheQueuedSelectionWithoutKeepingItsCapture() {
      this.input.postKeyboard(new KeyboardInputSnapshot(257, 1, 1, 0, 10, false, false));
      postPress(1, 0, false, 20);
      postRelease(0, 30);
      this.input.drainPhysicalEvents(() -> true,
         key -> assertTrue(this.input.routing.submit(42L)), scroll -> fail(), SelectionPointerMailboxTest::dispatch);
      assertEquals(Set.of(1, 2), ClientOperationController.workspace().selectedIds());
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
      assertEquals(Set.of(1, 2), ClientOperationController.workspace().selectedIds());
   }

   @Test
   void gizmoPressAndReleaseInOneBatchSettleTheEditAndRouting() {
      var press = postGizmoPress(0, 10);
      postRelease(0, 20);
      this.input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(), event -> {
         dispatch(event);
         if (event == press) {
            assertTrue(ClientOperationController.workspace().editing());
            assertTrue(ClientOperationController.selectionGestures().active());
            assertTrue(this.input.routing.accepts(this.input.clickGestureToken));
         }
      });
      assertPointerSettled();
   }

   @Test
   void releaseCommitsOnlyItsCapturedGizmoEdit() {
      postGizmoPress(0, 10);
      drain();
      var workspace = ClientOperationController.workspace();
      var moved = workspace.part(1).orElseThrow().withTranslation(new BlockPos(5, 0, 0));
      workspace.updatePart(moved);
      postRelease(0, 20);
      drain();
      assertEquals(moved, workspace.part(1).orElseThrow());
      assertPointerSettled();
   }

   @Test
   void supersedingPressCancelsTheOldEditBeforeStartingTheNewDrag() {
      var original = ClientOperationController.workspace().part(1).orElseThrow();
      var oldPress = postGizmoPress(0, 10);
      drain();
      var oldCapture = ClientOperationController.selectionGestures().capture();
      // Capture the replacement before the old edit changes, so rollback restores its source.
      postGizmoPress(1, 20);
      ClientOperationController.workspace().updatePart(original.withTranslation(new BlockPos(5, 0, 0)));
      drain();
      assertEquals(original, ClientOperationController.workspace().part(1).orElseThrow());
      assertTrue(ClientOperationController.workspace().editing());
      assertNotSame(oldCapture, ClientOperationController.selectionGestures().capture());
      var newCapture = ClientOperationController.selectionGestures().capture();
      long newRouting = this.input.clickGestureToken;
      dispatch(new SelectionPointerEvent.Release(oldPress.identity(), 0, 30));
      assertSame(newCapture, ClientOperationController.selectionGestures().capture());
      assertEquals(newRouting, this.input.clickGestureToken);
      assertTrue(ClientOperationController.workspace().editing());
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
         ClientOperationController.interactionScene(), ClientOperationController.workspace(), time).orElseThrow();
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
         assertTrue(ClientOperationController.selectionGestures().active());
      }, scroll -> fail(), SelectionPointerMailboxTest::dispatch);
      assertTrue(press.alt());
      assertFalse(this.input.modifier.held());
      assertTrue(ClientOperationController.workspace().editing());
      postRelease(0, 30);
      drain();
      assertPointerSettled();
   }

   @Test
   void rejectedAltGizmoDoesNotConsumeTheCurrentModifierOrStartAnEdit() {
      this.input.modifier.press(1, true, true);
      postGizmoPress(0, 10, true);
      ClientOperationController.workspace().setLocked(true);
      postRelease(0, 20);
      drain();
      assertFalse(ClientOperationController.selectionGestures().active());
      assertFalse(ClientOperationController.workspace().editing());
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
      var workspace = ClientOperationController.workspace();
      var original = workspace.part(1).orElseThrow();
      workspace.updatePart(original.withTranslation(new BlockPos(3, 0, 0)));
      this.input.captureSelectionPress(new SelectionDraftPress(ClientOperationController.interactionScene().owner(),
         ClientOperationController.draftSelectionMode(), 1, new BlockPos(8, 0, 0), true, false, 20));
      assertNull(this.input.selectionPointer.release(0, 30));
      postRelease(1, 40);
      drain();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertTrue(ClientOperationController.selectionDraftActive());
      assertPointerSettled();
   }

   @Test
   void replacementSnapshotFromCancelledGeometryCannotStartANewEdit() {
      postGizmoPress(0, 10);
      drain();
      var workspace = ClientOperationController.workspace();
      var original = workspace.part(1).orElseThrow();
      workspace.updatePart(original.withTranslation(new BlockPos(5, 0, 0)));
      ClientOperationController.selectAllWorkspaceParts();
      postGizmoPress(1, 20);
      drain();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertFalse(workspace.editing());
      assertFalse(ClientOperationController.selectionGestures().active());
      postRelease(1, 30);
      drain();
      assertPointerSettled();
   }

   @Test
   void releaseCannotSettleADragThatReplacedItsCaptureOutsideTheQueue() {
      var press = postGizmoPress(0, 10);
      drain();
      var old = ClientOperationController.selectionGestures().gizmo();
      assertNotNull(old);
      long oldRouting = this.input.clickGestureToken;
      ClientOperationController.cancelTransformGesture(old.editToken());
      ClientOperationController.selectionGestures().clear(old);
      bindFaceDrag(press);
      var replacement = ClientOperationController.selectionGestures().capture();
      long replacementRouting = this.input.clickGestureToken;
      // Restore the old dispatch association to model a release delayed past an external replacement.
      this.input.selectionPointer.dispatched(press.identity(), old.capture(), oldRouting);
      postRelease(0, 20);
      drain();
      assertSame(replacement, ClientOperationController.selectionGestures().capture());
      assertEquals(replacementRouting, this.input.clickGestureToken);
      assertTrue(ClientOperationController.workspace().editing());
   }

   @Test
   void faceReleaseSettlesItsCapturedEditWithoutRequiringWorldResampling() {
      var press = postPress(1, 0, false, 10);
      drain();
      bindFaceDrag(press);
      var workspace = ClientOperationController.workspace();
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
      bindFaceDrag(press);
      var workspace = ClientOperationController.workspace();
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
      bindFaceDrag(press);
      var workspace = ClientOperationController.workspace();
      var original = workspace.part(1).orElseThrow();
      workspace.updatePart(original.withTranslation(new BlockPos(3, 0, 0)));

      SelectionGestureController.cancelActive(this.input);
      assertEquals(original, workspace.part(1).orElseThrow());
      assertFalse(workspace.editing());
      assertFalse(ClientOperationController.selectionGestures().active());
      assertEquals(PointerGestureState.Kind.NONE, this.input.pointerGesture.kind());
      SelectionGestureController.cancelActive(this.input);
      postRelease(0, 20);
      drain();
      assertEquals(original, workspace.part(1).orElseThrow());
      assertPointerSettled();
   }

   // Face admission checks the live world. Seed its accepted capture to test only queued settlement.
   private void bindFaceDrag(SelectionPointerEvent.Press press) {
      var workspace = ClientOperationController.workspace();
      var part = workspace.part(1).orElseThrow();
      assertTrue(workspace.beginEdit());
      this.input.pointerGestureToken = this.input.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      var capture = SelectionDragCapture.create(ClientOperationController.interactionScene().owner(), workspace,
         List.of(part), press.snapshot().button(), this.input.pointerGestureToken);
      var drag = new WorkspaceFaceDrag(part, 0, true, DragAxisFrame.start(Vec3.ZERO, false),
         new Vec3(1, 0, 0), 0, capture, DeferredDragClick.none(), null, workspace.activeEditToken());
      ClientOperationController.selectionGestures().begin(drag);
      this.input.clickGestureToken = this.input.routing.beginGesture(press.snapshot().button());
      this.input.operationClickCapturedButton = press.snapshot().button();
      this.input.selectionPointer.dispatched(press.identity(), capture, this.input.clickGestureToken);
   }

   private void assertPointerSettled() {
      assertFalse(ClientOperationController.workspace().editing());
      assertFalse(ClientOperationController.selectionGestures().active());
      assertFalse(this.input.selectionPointer.active());
      assertFalse(this.input.blocksDraftLoad());
      assertEquals(0, this.input.clickGestureToken);
      assertEquals(-1, this.input.operationClickCapturedButton);
      assertEquals(PointerGestureState.Kind.NONE, this.input.pointerGesture.kind());
   }

   private SelectionPointerEvent.Press postPress(int id, int button, boolean control, long time) {
      var press = SelectionPointerPress.capture(new OperationInteractionIntent.Part(id, 1), button, control,
         button == 1 ? 1 : -1, ClientOperationController.interactionScene(), ClientOperationController.workspace(), time).orElseThrow();
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
