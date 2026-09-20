package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DragAdvanceSemanticsTest {
   @Test
   void workspaceFaceWinsOverEveryOtherDrag() {
      DragAdvanceSemantics.Plan plan = DragAdvanceSemantics.plan(state(true, true, true, true, true, true, true, true));

      assertEquals(DragAdvanceSemantics.Owner.WORKSPACE_FACE, plan.exclusiveOwner());
      assertFalse(plan.advanceGeometryGizmo());
      assertFalse(plan.advanceOperationPoint());
      assertFalse(plan.advanceOperationDrag());
   }

   @Test
   void clearsOnlyOperationDragsThatLostTheirGestureOwnership() {
      DragAdvanceSemantics.Plan plan = DragAdvanceSemantics.plan(state(true, false, true, false, false, false, false, true));

      assertTrue(plan.clearOperationDrag());
      assertTrue(plan.clearOperationPointDrag());
      assertFalse(plan.advanceOperationDrag());
      assertFalse(plan.advanceOperationPoint());
   }

   @Test
   void geometryAndOperationPointAdvanceBeforeAnOwnedOperationDrag() {
      DragAdvanceSemantics.Plan plan = DragAdvanceSemantics.plan(state(true, true, true, true, true, false, false, true));

      assertEquals(DragAdvanceSemantics.Owner.NONE, plan.exclusiveOwner());
      assertTrue(plan.advanceGeometryGizmo());
      assertTrue(plan.advanceOperationPoint());
      assertTrue(plan.advanceOperationDrag());
   }

   @Test
   void inactiveOperationPreviewClearsTheOperationDragsAfterOtherUpdates() {
      DragAdvanceSemantics.Plan plan = DragAdvanceSemantics.plan(state(true, true, true, true, true, false, false, false));

      assertTrue(plan.advanceGeometryGizmo());
      assertTrue(plan.advanceOperationPoint());
      assertTrue(plan.clearInactiveOperationDrags());
      assertFalse(plan.advanceOperationDrag());
   }

   private static DragAdvanceSemantics.State state(
      boolean operationDrag, boolean operationDragOwned,
      boolean operationPoint, boolean operationPointOwned,
      boolean geometry, boolean workspaceGizmo, boolean workspaceFace, boolean operationPreview
   ) {
      return new DragAdvanceSemantics.State(
         operationDrag, operationDragOwned, operationPoint, operationPointOwned,
         geometry, workspaceGizmo, workspaceFace, operationPreview
      );
   }
}
