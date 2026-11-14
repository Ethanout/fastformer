package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.transform.OperationTransforms;

import io.github.fastformer.client.session.ClientSessionSystem;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.operation.transform.OperationTransforms.AabbAdjustDecision.*;

import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
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
      ClientSessionSystem.instance().operations().clearWorkspace();
      input = FastPlaceClientInput.inputSession();
      input.reset();
      var bounds = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO);
      ClientSessionSystem.instance().operations().workspace().addParts(List.of(new ClientSelectionPart(1,
         ClientSelectionPart.Source.WORLD, bounds, Map.of(), WorkspaceTransform.IDENTITY, false)));
      ClientSessionSystem.instance().operations().selectAllWorkspaceParts();
   }

   @AfterEach void clear() {
      SelectionGestureController.cancelActive(input);
      input.reset();
      ClientSessionSystem.instance().operations().clearWorkspace();
   }

   private WorkspaceFaceDrag beginPending() {
      var workspace = ClientSessionSystem.instance().operations().workspace();
      var part = workspace.part(1).orElseThrow();
      var hit = new OperationGeometry.RayHit(new Vec3(3, 1, 1), new Vec3(1, 0, 0), 4, 0);
      var target = new OperationInteractionIntent.Face(1, part.selection().bounds(), hit, true);
      var press = SelectionPointerPress.capture(target, 1, false, 1,
         ClientSessionSystem.instance().operations().interactionScene(), workspace, 1234).orElseThrow();
      assertEquals(DRAG_STARTED, SelectionGestureController.captureWorkspaceFaceDrag(input, target, press, part, true));
      return ClientSessionSystem.instance().operations().selectionGestures().face();
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
      assertTrue(ClientSessionSystem.instance().operations().workspace().ownsEdit(ready.editToken()));
   }

   @Test void releaseDuringCaptureCannotResumeALateDrag() {
      var waiting = beginPending();
      var original = waiting.baseline();
      SelectionGestureController.finishFace(input);
      assertFalse(ClientSessionSystem.instance().operations().workspace().editing());
      assertNull(SelectionGestureController.resolveFaceSource(input, waiting, READY));
      assertFalse(ClientSessionSystem.instance().operations().selectionGestures().active());
      assertEquals(original, ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow());
   }

   @Test void pushingFaceKeepsSelectionAdjustableUntilExplicitFix() {
      var drag = beginPending();
      ClientSessionSystem.instance().operations().transforms().updateAabbFaceGesture(drag.editToken(), drag.baseline(), 0, true, 2);
      SelectionGestureController.finishFace(input);
      var expanded = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
      assertEquals(5.0, expanded.selection().bounds().maxX);
      assertTrue(expanded.canAdjustGeometry());
      assertFalse(ClientSessionSystem.instance().operations().canStartSelectionDraft());
      assertTrue(ClientSessionSystem.instance().operations().fixActiveSelection());
      assertFalse(ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow().canAdjustGeometry());
   }

   @Test void changedSourceCancelsTheCapturedEdit() {
      var waiting = beginPending();
      assertNull(SelectionGestureController.resolveFaceSource(input, waiting, SOURCE_CHANGED));
      assertFalse(ClientSessionSystem.instance().operations().workspace().editing());
      assertFalse(ClientSessionSystem.instance().operations().selectionGestures().active());
   }

   @Test void obsoleteSourceResultCannotCancelANewerDrag() {
      var waiting = beginPending();
      SelectionGestureController.cancelActive(input);
      var next = beginPending();
      assertNull(SelectionGestureController.resolveFaceSource(input, waiting, SOURCE_CHANGED));
      assertSame(next, ClientSessionSystem.instance().operations().selectionGestures().face());
      assertTrue(ClientSessionSystem.instance().operations().workspace().ownsEdit(next.editToken()));
   }
}
