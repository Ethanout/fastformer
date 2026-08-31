package io.github.fastformer.client.input.drag;

import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.fastplace.OperationPointDragConstraint;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Computes constrained targets for operation control-point dragging. */
public final class OperationPointDragCalculator {
   private static final int MAX_STEPS_PER_PACKET = 128;

   private OperationPointDragCalculator() {
   }

   public static BlockPos nextTarget(
      BlockPos current, BlockPos desired, OperationPointDrag drag, OperationPointDragConstraint constraint
   ) {
      for (int limit = MAX_STEPS_PER_PACKET; limit >= 1; limit /= 2) {
         BlockPos candidate = new BlockPos(
            ClientInputMath.stepToward(current.getX(), desired.getX(), limit),
            ClientInputMath.stepToward(current.getY(), desired.getY(), limit),
            ClientInputMath.stepToward(current.getZ(), desired.getZ(), limit)
         );
         candidate = constrain(candidate, drag, constraint);
         if (ClientInputMath.maximumCoordinateDelta(current, candidate) <= MAX_STEPS_PER_PACKET) {
            return candidate;
         }
      }
      return current;
   }

   public static BlockPos planeTarget(OperationPointDrag drag, Vec3 eye, Vec3 view) {
      if (drag.plane() == null) {
         return lineTarget(drag, eye, view);
      }
      Vec3 intersection = drag.plane().rayIntersection(eye, view);
      if (intersection != null) {
         return drag.plane().snap(BlockPos.containing(intersection.add(drag.planeGrabOffset())));
      }

      Vec3 origin = drag.plane().anchor();
      BlockPos reference = BlockPos.containing(origin);
      int axis = OperationGeometry.closestWorldAxisToRay(origin, eye, view, drag.plane().dependentAxis());
      if (axis < 0) {
         return null;
      }
      double currentOffset = axisOffset(origin, eye, view, axis);
      long steps = Math.round(currentOffset - ClientInputMath.vecAxisComponent(drag.axisBaselines(), axis));
      return drag.plane().snap(ClientInputMath.withAxisCoordinate(
         reference, axis, ClientInputMath.safeCoordinate(ClientInputMath.axisCoordinate(reference, axis), steps)
      ));
   }

   public static BlockPos lineTarget(OperationPointDrag drag, Vec3 eye, Vec3 view) {
      return drag.line() == null
         ? null
         : drag.line().pointAtOffset(drag.line().rayOffset(eye, view) - drag.lineGrabBaseline());
   }

   public static OperationPointDragConstraint selectConstraint(OperationPointDrag drag, Vec3 eye, Vec3 view) {
      if (drag.line() == null) {
         return drag.plane() == null ? OperationPointDragConstraint.FREE : OperationPointDragConstraint.PLANE;
      }
      if (drag.plane() == null) {
         return OperationPointDragConstraint.LINE;
      }
      Vec3 ray = ClientInputMath.normalize(view);
      SelectionPrism.GridLine targetingLine = drag.line().through(drag.initialPoint());
      Vec3 linePoint = OperationGeometry.closestPointOnAxisToRay(
         targetingLine.anchor(), targetingLine.direction(), eye, ray
      );
      double rayDistance = Math.max(0.0, linePoint.subtract(eye).dot(ray));
      double distanceSqr = linePoint.distanceToSqr(eye.add(ray.scale(rayDistance)));
      double radius = Math.clamp(rayDistance * 0.012, 0.35, 1.25);
      if (drag.constraint() == OperationPointDragConstraint.LINE) {
         radius *= 1.2;
      }
      return distanceSqr <= radius * radius ? OperationPointDragConstraint.LINE : OperationPointDragConstraint.PLANE;
   }

   public static OperationPointDrag rebase(
      OperationPointDrag drag, OperationPointDragConstraint constraint, Vec3 eye, Vec3 view
   ) {
      if (drag.plane() == null || drag.line() == null) {
         return drag.withConstraint(constraint);
      }
      if (constraint == OperationPointDragConstraint.LINE) {
         SelectionPrism.GridLine line = drag.line().through(drag.sentTarget());
         double baseline = line.rayOffset(eye, view) - line.offset(drag.sentTarget());
         return drag.withLineFrame(line, baseline, constraint);
      }
      SelectionPrism.GridPlane plane = drag.plane().through(drag.sentTarget());
      Vec3 intersection = plane.rayIntersection(eye, view);
      Vec3 grabOffset = intersection == null
         ? Vec3.ZERO
         : Vec3.atCenterOf(drag.sentTarget()).subtract(intersection);
      Vec3 center = Vec3.atCenterOf(drag.sentTarget());
      Vec3 axisBaselines = new Vec3(
         axisOffset(center, eye, view, 0),
         axisOffset(center, eye, view, 1),
         axisOffset(center, eye, view, 2)
      );
      return drag.withPlaneFrame(plane, grabOffset, axisBaselines, constraint);
   }

   private static BlockPos constrain(
      BlockPos target, OperationPointDrag drag, OperationPointDragConstraint constraint
   ) {
      if (constraint == OperationPointDragConstraint.LINE && drag.line() != null) {
         return drag.line().snap(target);
      }
      return drag.plane() == null ? target : drag.plane().snap(target);
   }

   public static double axisOffset(Vec3 origin, Vec3 eye, Vec3 view, int axis) {
      Vec3 axisVector = ClientInputMath.worldAxis(axis);
      return OperationGeometry.closestPointOnAxisToRay(origin, axisVector, eye, view)
         .subtract(origin)
         .dot(axisVector);
   }
}
