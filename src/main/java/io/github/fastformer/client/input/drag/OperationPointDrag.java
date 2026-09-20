package io.github.fastformer.client.input.drag;

import io.github.fastformer.fastplace.OperationPointDragConstraint;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Immutable state for a constrained operation control-point gesture. */
public record OperationPointDrag(
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
   long pressedAt,
   long captureToken
) {
   public OperationPointDrag withSentTarget(BlockPos target) {
      return new OperationPointDrag(
         pointIndex, mouseButton, initialPoint, target, plane, planeGrabOffset, line,
         lineGrabBaseline, axisBaselines, constraint, pressedAt, captureToken
      );
   }

   public OperationPointDrag withConstraint(OperationPointDragConstraint value) {
      return new OperationPointDrag(
         pointIndex, mouseButton, initialPoint, sentTarget, plane, planeGrabOffset, line,
         lineGrabBaseline, axisBaselines, value, pressedAt, captureToken
      );
   }

   public OperationPointDrag withPlaneFrame(
      SelectionPrism.GridPlane value,
      Vec3 grabOffset,
      Vec3 baselines,
      OperationPointDragConstraint valueConstraint
   ) {
      return new OperationPointDrag(
         pointIndex, mouseButton, initialPoint, sentTarget, value, grabOffset, line,
         lineGrabBaseline, baselines, valueConstraint, pressedAt, captureToken
      );
   }

   public OperationPointDrag withLineFrame(
      SelectionPrism.GridLine value,
      double grabBaseline,
      OperationPointDragConstraint valueConstraint
   ) {
      return new OperationPointDrag(
         pointIndex, mouseButton, initialPoint, sentTarget, plane, planeGrabOffset, value,
         grabBaseline, axisBaselines, valueConstraint, pressedAt, captureToken
      );
   }
}
