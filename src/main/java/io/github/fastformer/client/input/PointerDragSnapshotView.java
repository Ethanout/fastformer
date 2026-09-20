package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.fastplace.geometry.AxisGizmo;

/** Read-only presentation view of the currently captured pointer drag. */
final class PointerDragSnapshotView {
   private PointerDragSnapshotView() { }

   static WorkspaceFaceDrag face(ClientInputSession session) {
      return ClientOperationController.selectionGestures().face();
   }

   static WorkspaceGizmoDrag gizmo(ClientInputSession session) {
      return ClientOperationController.selectionGestures().gizmo();
   }

   static AxisGizmo.Axis axis(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? null : drag.axis();
   }

   static AxisGizmo.Operation operation(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? null : drag.operation();
   }

   static int steps(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? 0 : drag.sentSteps();
   }

   static double baseValue(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? 0.0 : drag.baseValue();
   }
}
