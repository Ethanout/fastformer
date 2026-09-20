package io.github.fastformer.client.input;

/** Resolves drag validity and the per-tick update order without client state. */
public final class DragAdvanceSemantics {
   private DragAdvanceSemantics() {
   }

   public static Plan plan(State state) {
      boolean clearOperationDrag = state.operationDragActive() && !state.operationDragOwned();
      boolean clearOperationPointDrag = state.operationPointDragActive() && !state.operationPointDragOwned();
      boolean operationDragActive = state.operationDragActive() && !clearOperationDrag;
      boolean operationPointDragActive = state.operationPointDragActive() && !clearOperationPointDrag;

      if (state.workspaceFaceDragActive()) {
         return new Plan(clearOperationDrag, clearOperationPointDrag, Owner.WORKSPACE_FACE, false, false, false, false);
      }
      if (state.workspaceGizmoDragActive()) {
         return new Plan(clearOperationDrag, clearOperationPointDrag, Owner.WORKSPACE_GIZMO, false, false, false, false);
      }
      boolean clearInactiveOperationDrags = operationDragActive && !state.operationPreviewActive();
      return new Plan(
         clearOperationDrag,
         clearOperationPointDrag,
         Owner.NONE,
         state.geometryGizmoDragActive(),
         operationPointDragActive,
         clearInactiveOperationDrags,
         operationDragActive && !clearInactiveOperationDrags
      );
   }

   public record State(
      boolean operationDragActive,
      boolean operationDragOwned,
      boolean operationPointDragActive,
      boolean operationPointDragOwned,
      boolean geometryGizmoDragActive,
      boolean workspaceGizmoDragActive,
      boolean workspaceFaceDragActive,
      boolean operationPreviewActive
   ) {
   }

   public record Plan(
      boolean clearOperationDrag,
      boolean clearOperationPointDrag,
      Owner exclusiveOwner,
      boolean advanceGeometryGizmo,
      boolean advanceOperationPoint,
      boolean clearInactiveOperationDrags,
      boolean advanceOperationDrag
   ) {
   }

   public enum Owner {
      NONE,
      WORKSPACE_FACE,
      WORKSPACE_GIZMO
   }
}
