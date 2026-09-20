package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspacePartLabelHintTest {
   @Test
   void ctrlOnASelectedPartShowsDeselect() {
      assertEquals(
         WorkspacePartLabelHint.DESELECT_KEY,
         key(WorkspacePartLabelHint.text(3, true, false, true, part(3)))
      );
   }

   @Test
   void ctrlOnAnUnselectedPartShowsAppend() {
      assertEquals(
         WorkspacePartLabelHint.APPEND_KEY,
         key(WorkspacePartLabelHint.text(3, false, false, true, part(3)))
      );
   }

   @Test
   void partHoverWithoutCtrlDoesNotPromiseToggle() {
      assertEquals(
         WorkspacePartLabelHint.HOVERED_KEY,
         key(WorkspacePartLabelHint.text(3, true, false, false, part(3)))
      );
   }

   @Test
   void ctrlOnASelectedMoveHandleShowsMoveNotDeselect() {
      Component text = WorkspacePartLabelHint.text(
         3, true, false, true, gizmo(3, false, AxisGizmo.Operation.MOVE)
      );

      assertEquals(WorkspacePartLabelHint.ACTION_KEY, key(text));
      assertEquals("fastformer.hud.transform.position", nestedKey(text, 1));
   }

   @Test
   void selectedRotateHandleShowsRotate() {
      Component text = WorkspacePartLabelHint.text(
         2, true, false, true, gizmo(2, false, AxisGizmo.Operation.ROTATE)
      );

      assertEquals(WorkspacePartLabelHint.ACTION_KEY, key(text));
      assertEquals("fastformer.hud.transform.rotation", nestedKey(text, 1));
   }

   @Test
   void unselectedScaleHandleShowsScaleNotAppend() {
      Component text = WorkspacePartLabelHint.text(
         4, false, false, true, gizmo(4, false, AxisGizmo.Operation.SCALE)
      );

      assertEquals(WorkspacePartLabelHint.ACTION_KEY, key(text));
      assertEquals("fastformer.hud.transform.scale", nestedKey(text, 1));
   }

   @Test
   void commonHandleKeepsANeutralSelectedLabel() {
      assertEquals(
         WorkspacePartLabelHint.SELECTED_KEY,
         key(WorkspacePartLabelHint.text(3, true, false, true, gizmo(0, true, AxisGizmo.Operation.MOVE)))
      );
   }

   @Test
   void faceHoverDoesNotShowCtrlToggle() {
      assertEquals(
         WorkspacePartLabelHint.HOVERED_KEY,
         key(WorkspacePartLabelHint.text(3, true, false, true, face(3)))
      );
   }

   @Test
   void submissionLockSuppressesCtrlToggleOnAPartIntent() {
      assertEquals(
         WorkspacePartLabelHint.SELECTED_KEY,
         key(WorkspacePartLabelHint.text(3, true, true, true, part(3)))
      );
   }

   @Test
   void submissionLockKeepsANeutralHandleLabel() {
      assertEquals(
         WorkspacePartLabelHint.SELECTED_KEY,
         key(WorkspacePartLabelHint.text(
            3, true, true, true, gizmo(3, false, AxisGizmo.Operation.MOVE)
         ))
      );
   }

   @Test
   void idleSelectedPartShowsSelected() {
      assertEquals(
         WorkspacePartLabelHint.SELECTED_KEY,
         key(WorkspacePartLabelHint.text(3, true, false, false, null))
      );
   }

   @Test
   void idleUnselectedPartShowsOnlyTheId() {
      assertEquals(
         WorkspacePartLabelHint.ID_KEY,
         key(WorkspacePartLabelHint.text(3, false, false, true, null))
      );
   }

   @Test
   void anotherPartsIntentDoesNotToggleThisPart() {
      assertEquals(
         WorkspacePartLabelHint.SELECTED_KEY,
         key(WorkspacePartLabelHint.text(3, true, false, true, part(4)))
      );
   }

   @Test
   void createSelectionIntentKeepsANeutralLabel() {
      assertEquals(
         WorkspacePartLabelHint.SELECTED_KEY,
         key(WorkspacePartLabelHint.text(
            3, true, false, true, new OperationInteractionIntent.CreateSelection(BlockPos.ZERO)
         ))
      );
   }

   private static OperationInteractionIntent.Part part(int partId) {
      return new OperationInteractionIntent.Part(partId, 1.0);
   }

   private static OperationInteractionIntent.Face face(int partId) {
      return new OperationInteractionIntent.Face(
         partId,
         new AABB(BlockPos.ZERO),
         new OperationGeometry.RayHit(Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), 0.5, 0),
         true
      );
   }

   private static OperationInteractionIntent.Gizmo gizmo(
      int partId, boolean common, AxisGizmo.Operation operation
   ) {
      AxisGizmo axisGizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      AxisGizmo.Handle handle = axisGizmo.handles().stream()
         .filter(candidate -> candidate.operation() == operation)
         .findFirst()
         .orElseThrow();
      return new OperationInteractionIntent.Gizmo(
         partId, common, axisGizmo, new AxisGizmo.Hit(handle, Vec3.ZERO, 1.0, 0.1)
      );
   }

   private static String key(Component text) {
      return ((TranslatableContents) text.getContents()).getKey();
   }

   private static String nestedKey(Component text, int argumentIndex) {
      Object argument = ((TranslatableContents) text.getContents()).getArgs()[argumentIndex];
      return key((Component) argument);
   }
}
