package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.operation.controller.ClientOperationController.AabbAdjustDecision.*;

import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionFaceCaptureTest {
   private ClientInputSession input;

   @BeforeEach void prepare() {
      ClientOperationController.clearWorkspace();
      input = FastPlaceClientInput.inputSession();
      input.reset();
      var bounds = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO);
      ClientOperationController.workspace().addParts(List.of(new ClientSelectionPart(1,
         ClientSelectionPart.Source.WORLD, bounds, Map.of(), WorkspaceTransform.IDENTITY, false)));
      ClientOperationController.selectAllWorkspaceParts();
   }

   @AfterEach void clear() {
      SelectionGestureController.cancelActive(input);
      input.reset();
      ClientOperationController.clearWorkspace();
   }

   private WorkspaceFaceDrag beginPending() {
      var workspace = ClientOperationController.workspace();
      var part = workspace.part(1).orElseThrow();
      var hit = new OperationGeometry.RayHit(new Vec3(3, 1, 1), new Vec3(1, 0, 0), 4, 0);
      var target = new OperationInteractionIntent.Face(1, part.selection().bounds(), hit, true);
      var press = SelectionPointerPress.capture(target, 1, false, 1,
         ClientOperationController.interactionScene(), workspace, 1234).orElseThrow();
      assertEquals(DRAG_STARTED, SelectionGestureController.captureWorkspaceFaceDrag(input, target, press, part, true));
      return ClientOperationController.selectionGestures().face();
   }

   @Test void firstPressResumesTheSameDragAfterSourceCapture() {
      var waiting = beginPending();
      assertTrue(waiting.awaitingSource());
      assertSame(waiting, SelectionGestureController.resolveFaceSource(input, waiting, CAPTURE_PENDING));
      var ready = SelectionGestureController.resolveFaceSource(input, waiting, READY);
      assertNotNull(ready);
      assertFalse(ready.awaitingSource());
      assertSame(waiting.capture(), ready.capture());
      assertSame(waiting.editToken(), ready.editToken());
      assertEquals(waiting.frame(), ready.frame());
      assertEquals(waiting.deferredClick(), ready.deferredClick());
      assertTrue(ClientOperationController.workspace().ownsEdit(ready.editToken()));
   }

   @Test void releaseDuringCaptureCannotResumeALateDrag() {
      var waiting = beginPending();
      var original = waiting.baseline();
      SelectionGestureController.finishFace(input);
      assertFalse(ClientOperationController.workspace().editing());
      assertNull(SelectionGestureController.resolveFaceSource(input, waiting, READY));
      assertFalse(ClientOperationController.selectionGestures().active());
      assertEquals(original, ClientOperationController.workspace().part(1).orElseThrow());
   }

   @Test void pushingFaceKeepsSelectionAdjustableUntilExplicitFix() {
      var drag = beginPending();
      ClientOperationController.updateAabbFaceGesture(drag.editToken(), drag.baseline(), 0, true, 2);
      SelectionGestureController.finishFace(input);
      var expanded = ClientOperationController.workspace().part(1).orElseThrow();
      assertEquals(5.0, expanded.selection().bounds().maxX);
      assertTrue(expanded.canAdjustGeometry());
      assertFalse(ClientOperationController.canStartSelectionDraft());
      assertTrue(ClientOperationController.fixActiveSelection());
      assertFalse(ClientOperationController.workspace().part(1).orElseThrow().canAdjustGeometry());
   }

   @Test void changedSourceCancelsTheCapturedEdit() {
      var waiting = beginPending();
      assertNull(SelectionGestureController.resolveFaceSource(input, waiting, SOURCE_CHANGED));
      assertFalse(ClientOperationController.workspace().editing());
      assertFalse(ClientOperationController.selectionGestures().active());
   }

   @Test void obsoleteSourceResultCannotCancelANewerDrag() {
      var waiting = beginPending();
      SelectionGestureController.cancelActive(input);
      var next = beginPending();
      assertNull(SelectionGestureController.resolveFaceSource(input, waiting, SOURCE_CHANGED));
      assertSame(next, ClientOperationController.selectionGestures().face());
      assertTrue(ClientOperationController.workspace().ownsEdit(next.editToken()));
   }
}
