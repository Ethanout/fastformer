package io.github.fastformer.client.input;

/** Pure release priority for the left and right mouse gesture owners. */
public final class MouseDragReleaseSemantics {
   private MouseDragReleaseSemantics() {
   }

   public static Target releaseTarget(int action, int button, State state) {
      if (action != MouseButtonInputSemantics.RELEASE) {
         return Target.NONE;
      }
      if (button == MouseButtonInputSemantics.LEFT_BUTTON) {
         return leftTarget(state);
      }
      if (button == MouseButtonInputSemantics.RIGHT_BUTTON) {
         return rightTarget(state);
      }
      return Target.NONE;
   }

   private static Target leftTarget(State state) {
      if (state.operationPointDragButton() == MouseButtonInputSemantics.LEFT_BUTTON) return Target.OPERATION_POINT_DRAG;
      if (state.operationDragButton() == MouseButtonInputSemantics.LEFT_BUTTON) return Target.OPERATION_DRAG;
      if (state.workspaceGizmoDragButton() == MouseButtonInputSemantics.LEFT_BUTTON) return Target.WORKSPACE_GIZMO_DRAG;
      if (state.workspaceFaceDragButton() == MouseButtonInputSemantics.LEFT_BUTTON) return Target.WORKSPACE_FACE_DRAG;
      if (state.geometryGizmoDragButton() == MouseButtonInputSemantics.LEFT_BUTTON) return Target.GEOMETRY_GIZMO_DRAG;
      if (state.operationCaptureButton() == MouseButtonInputSemantics.LEFT_BUTTON) return Target.OPERATION_CAPTURE;
      if (state.geometryCaptureButton() == MouseButtonInputSemantics.LEFT_BUTTON) return Target.GEOMETRY_CAPTURE;
      return state.undoPressCaptured() ? Target.UNDO_PRESS : Target.NONE;
   }

   private static Target rightTarget(State state) {
      if (state.operationPointDragButton() == MouseButtonInputSemantics.RIGHT_BUTTON) return Target.OPERATION_POINT_DRAG;
      if (state.operationDragButton() == MouseButtonInputSemantics.RIGHT_BUTTON) return Target.OPERATION_DRAG;
      if (state.workspaceGizmoDragButton() == MouseButtonInputSemantics.RIGHT_BUTTON) return Target.WORKSPACE_GIZMO_DRAG;
      if (state.workspaceFaceDragButton() == MouseButtonInputSemantics.RIGHT_BUTTON) return Target.WORKSPACE_FACE_DRAG;
      if (state.geometryGizmoDragButton() == MouseButtonInputSemantics.RIGHT_BUTTON) return Target.GEOMETRY_GIZMO_DRAG;
      return state.operationCaptureButton() == MouseButtonInputSemantics.RIGHT_BUTTON
         ? Target.OPERATION_CAPTURE
         : Target.NONE;
   }

   public record State(
      int operationPointDragButton,
      int operationDragButton,
      int workspaceGizmoDragButton,
      int workspaceFaceDragButton,
      int geometryGizmoDragButton,
      int operationCaptureButton,
      int geometryCaptureButton,
      boolean undoPressCaptured
   ) {
   }

   public enum Target {
      NONE,
      OPERATION_POINT_DRAG,
      OPERATION_DRAG,
      WORKSPACE_GIZMO_DRAG,
      WORKSPACE_FACE_DRAG,
      GEOMETRY_GIZMO_DRAG,
      OPERATION_CAPTURE,
      GEOMETRY_CAPTURE,
      UNDO_PRESS
   }
}
