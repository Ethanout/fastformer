package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.InteractionPressBinding;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelectionPointerPressTest {
   @Test
   void capturesButtonModifierAndDirectionWithoutConflatingLeftAndRight() {
      long beforeCapture = System.nanoTime();
      var target = new OperationInteractionIntent.Part(4, 8.0);
      var left = capture(target, 0, true, -1).orElseThrow();
      var right = capture(target, 1, false, 1).orElseThrow();
      var middle = capture(target, 2, true, 0).orElseThrow();
      long afterCapture = System.nanoTime();
      assertTrue(left.pressedAtNanos() >= beforeCapture);
      assertTrue(left.pressedAtNanos() <= afterCapture);
      assertSame(target, left.target());
      assertEquals(0, left.button());
      assertTrue(left.control());
      assertEquals(-1, left.shortPressSteps());
      assertEquals(1, right.button());
      assertFalse(right.control());
      assertEquals(1, right.shortPressSteps());
      assertEquals(2, middle.button());
      assertEquals(0, middle.shortPressSteps());
   }

   @Test
   void rejectsTargetsOwnedByOtherInputPaths() {
      assertTrue(capture(null, 0, false, -1).isEmpty());
      assertTrue(capture(new OperationInteractionIntent.CreateSelection(BlockPos.ZERO),
         0, false, -1).isEmpty());
      assertTrue(capture(new OperationInteractionIntent.Part(0, 1),
         0, false, -1).isEmpty());
      var target = new OperationInteractionIntent.Part(1, 1);
      assertTrue(capture(target, -1, false, 0).isEmpty());
      assertTrue(capture(target, 3, false, 0).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> new SelectionPointerPress(null, 0, false, 0, UUID.randomUUID(), Map.of(), null, 0L));
   }

   @Test
   void rejectsChangedGeometryAndUnpublishedTargets() {
      var session = populated();
      var workspace = session.workspace();
      var target = new OperationInteractionIntent.Part(1, 1);
      var scene = session.interactionScene();
      var press = SelectionPointerPress.capture(target, 0, false, -1, scene, workspace).orElseThrow();
      assertTrue(press.matches(scene.owner(), workspace));
      workspace.beginEdit();
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(3, 0, 0)));
      workspace.finishEdit();
      assertFalse(press.matches(scene.owner(), workspace));
      assertTrue(SelectionPointerPress.capture(target, 0, false, -1, scene, workspace).isEmpty());
   }

   @Test
   void rejectsNewOwnerLockedWorkspaceAndChangedCommonSelection() {
      var session = populated();
      var workspace = session.workspace();
      var scene = session.interactionScene();
      var press = SelectionPointerPress.capture(new OperationInteractionIntent.Gizmo(0, true, null, null),
         1, true, 1, scene, workspace).orElseThrow();
      assertFalse(press.matches(UUID.randomUUID(), workspace));
      workspace.setLocked(true);
      assertFalse(press.matches(scene.owner(), workspace));
      workspace.setLocked(false);
      assertTrue(press.matches(scene.owner(), workspace));
      workspace.selectOnly(1);
      assertFalse(press.matches(scene.owner(), workspace));
   }

   @Test
   void stalePressCannotSelectAReplacementOrChangeItsScene() throws Exception {
      ClientOperationController.clearWorkspace();
      try {
         var workspace = ClientOperationController.workspace();
         workspace.addParts(List.of(populated().workspace().part(1).orElseThrow()));
         var published = ClientOperationController.interactionScene();
         var scene = SelectionInteractionScene.capture(published.owner(), workspace, published);
         var press = SelectionPointerPress.capture(new OperationInteractionIntent.Part(1, 1),
            0, true, -1, scene, workspace).orElseThrow();
         workspace.removeSelectedParts();
         workspace.addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
         var selected = java.util.Set.copyOf(workspace.selectedIds());
         SelectionGestureController.press(FastPlaceClientInput.inputSession(), null, press);
         assertEquals(selected, workspace.selectedIds());
         assertFalse(workspace.editing());
         assertSame(published, ClientOperationController.interactionScene());
      } finally {
         ClientOperationController.clearWorkspace();
      }
   }

   private static Optional<SelectionPointerPress> capture(OperationInteractionIntent target, int button, boolean control, int steps) {
      var session = populated();
      return SelectionPointerPress.capture(target, button, control, steps, session.interactionScene(), session.workspace());
   }

   @Test
   void capturesDeclaredActionAndObjectIdentity() {
      var session = populated();
      var scene = session.interactionScene();
      var label = SelectionPointerPress.capture(new OperationInteractionIntent.Part(1, 1),
         0, true, -1, scene, session.workspace()).orElseThrow();
      assertEquals(new InteractionPressBinding.SelectPart(1), label.action());
      assertSame(scene.parts().get(1).label(), label.object());
      assertTrue(label.matches(scene, session.workspace()));
      var frame = SelectionPointerPress.capture(new OperationInteractionIntent.Part(1, 1,
         OperationInteractionIntent.PartSurface.FRAME), 1, false, 1, scene, session.workspace()).orElseThrow();
      assertEquals(new InteractionPressBinding.SelectPart(1), frame.action());
      assertSame(scene.parts().get(1).frame(), frame.object());
      var faceTarget = new OperationInteractionIntent.Face(1, scene.bounds(1), null, false);
      var face = SelectionPointerPress.capture(faceTarget, 0, false, -1, scene, session.workspace()).orElseThrow();
      assertEquals(new InteractionPressBinding.SelectOrDragFace(faceTarget), face.action());
      assertSame(frame.object(), face.object());
      var target = new OperationInteractionIntent.Gizmo(0, true, null, null);
      var gizmo = SelectionPointerPress.capture(target, 2, false, 0, scene, session.workspace()).orElseThrow();
      assertEquals(new InteractionPressBinding.DragGizmo(target), gizmo.action());
      assertSame(scene.groupGizmo(), gizmo.object());
   }

   @Test
   void rejectsMissingBindingAndReplacedObjects() {
      var session = populated();
      var scene = session.interactionScene();
      var target = new OperationInteractionIntent.Part(1, 1);
      var press = SelectionPointerPress.capture(target, 0, false, -1, scene, session.workspace()).orElseThrow();
      var part = scene.parts().get(1);
      var replacement = new InteractionObject(part.label().id(), Map.of());
      var parts = new java.util.LinkedHashMap<>(scene.parts());
      parts.put(1, new SelectionInteractionScene.Part(part.source(), part.identity(), part.frame(), replacement, part.gizmo()));
      var changed = new SelectionInteractionScene(scene.owner(), parts, scene.groupGizmo());
      assertTrue(SelectionPointerPress.capture(target, 0, false, -1, changed, session.workspace()).isEmpty());
      assertFalse(press.matches(changed, session.workspace()));
      assertTrue(InteractionPressBinding.GIZMO.resolve(target).isEmpty());
      assertTrue(InteractionPressBinding.SELECT.resolve(new OperationInteractionIntent.Gizmo(0, true, null, null)).isEmpty());
   }

   @Test
   void inputDispatchExecutesDeclaredSelectionAction() throws Exception {
      ClientOperationController.clearWorkspace();
      try {
         var workspace = ClientOperationController.workspace();
         workspace.addParts(populated().workspace().parts());
         ClientOperationController.selectAllWorkspaceParts();
         var press = SelectionPointerPress.capture(new OperationInteractionIntent.Part(1, 1),
            0, false, -1, ClientOperationController.interactionScene(), workspace).orElseThrow();
         SelectionGestureController.press(FastPlaceClientInput.inputSession(), null, press);
         assertEquals(java.util.Set.of(1), workspace.selectedIds());
         assertFalse(workspace.editing());
      } finally {
         ClientOperationController.clearWorkspace();
      }
   }

   private static ClientSelectionSession populated() {
      var session = new ClientSelectionSession();
      for (int i = 0; i < 4; i++) {
         session.workspace().addParts(List.of(new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
            io.github.fastformer.fastplace.selection.OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO),
            Map.of(), io.github.fastformer.client.operation.model.WorkspaceTransform.IDENTITY, false)));
      }
      session.workspace().selectAll();
      session.publishInteractionScene();
      return session;
   }
}
