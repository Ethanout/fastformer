package io.github.fastformer.client;

import io.github.fastformer.client.operation.ClientOperationWorkspace;
import io.github.fastformer.client.operation.ClientSelectionPart;
import io.github.fastformer.fastplace.OperationPointDragConstraint;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Immutable drag snapshots shared by the client input router. */
record OperationDrag(
   int axis,
   boolean positive,
   DragAxisFrame frame,
   Vec3 normal,
   int sentSteps,
   int mouseButton,
   OperationGeometry.RayHit faceHit,
   AxisGizmo.HandleKey gizmoKey,
   double gizmoBaseValue,
   DeferredDragClick deferredClick
) {
   OperationDrag withFrame(DragAxisFrame value) {
      return new OperationDrag(axis, positive, value, normal, sentSteps, mouseButton, faceHit, gizmoKey, gizmoBaseValue, deferredClick);
   }

   OperationDrag withSentSteps(int value) {
      return new OperationDrag(axis, positive, frame, normal, value, mouseButton, faceHit, gizmoKey, gizmoBaseValue, deferredClick);
   }

   OperationDrag withDeferredClick(DeferredDragClick value) {
      return new OperationDrag(axis, positive, frame, normal, sentSteps, mouseButton, faceHit, gizmoKey, gizmoBaseValue, value);
   }
}

record WorkspaceFaceDrag(
   ClientSelectionPart baseline,
   int axis,
   boolean positive,
   DragAxisFrame frame,
   Vec3 normal,
   int sentSteps,
   int mouseButton,
   DeferredDragClick deferredClick,
   OperationGeometry.RayHit hit,
   ClientOperationWorkspace.EditToken editToken
) {
   WorkspaceFaceDrag withSentSteps(int value) {
      return new WorkspaceFaceDrag(baseline, axis, positive, frame, normal, value, mouseButton, deferredClick, hit, editToken);
   }

   WorkspaceFaceDrag withDeferredClick(DeferredDragClick value) {
      return new WorkspaceFaceDrag(baseline, axis, positive, frame, normal, sentSteps, mouseButton, value, hit, editToken);
   }
}

record OperationPointDrag(
   int pointIndex,
   int mouseButton,
   BlockPos initialPoint,
   BlockPos sentTarget,
   SelectionPrism.GridPlane plane,
   Vec3 planeGrabOffset,
   SelectionPrism.GridLine line,
   double lineGrabBaseline,
   Vec3 axisBaselines,
   OperationPointDragConstraint constraint,
   long pressedAt
) {
   OperationPointDrag withSentTarget(BlockPos target) {
      return new OperationPointDrag(pointIndex, mouseButton, initialPoint, target, plane, planeGrabOffset, line,
         lineGrabBaseline, axisBaselines, constraint, pressedAt);
   }

   OperationPointDrag withConstraint(OperationPointDragConstraint value) {
      return new OperationPointDrag(pointIndex, mouseButton, initialPoint, sentTarget, plane, planeGrabOffset, line,
         lineGrabBaseline, axisBaselines, value, pressedAt);
   }

   OperationPointDrag withPlaneFrame(
      SelectionPrism.GridPlane value, Vec3 grabOffset, Vec3 axisBaselines, OperationPointDragConstraint constraint
   ) {
      return new OperationPointDrag(pointIndex, mouseButton, initialPoint, sentTarget, value, grabOffset, line,
         lineGrabBaseline, axisBaselines, constraint, pressedAt);
   }

   OperationPointDrag withLineFrame(
      SelectionPrism.GridLine value, double grabBaseline, OperationPointDragConstraint constraint
   ) {
      return new OperationPointDrag(pointIndex, mouseButton, initialPoint, sentTarget, plane, planeGrabOffset, value,
         grabBaseline, axisBaselines, constraint, pressedAt);
   }
}

record GeometryGizmoDrag(
   AxisGizmo.Operation operation,
   AxisGizmo.Axis axis,
   Vec3 origin,
   Vec3 axisVector,
   int sentSteps,
   double baseValue,
   Vec3 center,
   Vec3 startRadial,
   Vec3 startTangent,
   AxisGizmo.Direction direction,
   int mouseButton
) {
   GeometryGizmoDrag withSentSteps(int value) {
      return new GeometryGizmoDrag(operation, axis, origin, axisVector, value, baseValue, center, startRadial,
         startTangent, direction, mouseButton);
   }
}

record WorkspaceGizmoDrag(
   int partId,
   boolean common,
   AxisGizmo.Operation operation,
   AxisGizmo.Axis axis,
   Vec3 origin,
   Vec3 axisVector,
   int sentSteps,
   Vec3 center,
   Vec3 startRadial,
   Vec3 startTangent,
   AxisGizmo.Direction direction,
   int mouseButton,
   List<ClientSelectionPart> baseline,
   ClientOperationWorkspace.EditToken editToken
) {
   WorkspaceGizmoDrag withSentSteps(int value) {
      return new WorkspaceGizmoDrag(partId, common, operation, axis, origin, axisVector, value, center, startRadial,
         startTangent, direction, mouseButton, baseline, editToken);
   }
}
