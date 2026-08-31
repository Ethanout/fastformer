package io.github.fastformer.client.input.drag;

import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.world.phys.Vec3;

/** Pure geometric projections used by the client gizmo gesture state. */
public final class GizmoDragCalculator {
   private GizmoDragCalculator() {
   }

   public static int geometryEndpointSteps(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(drag.origin(), drag.axisVector(), eye, view);
      return (int) Math.round(axisPoint.subtract(drag.origin()).dot(drag.axisVector()) * 2.0);
   }

   public static int operationEndpointSteps(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      return (int) Math.round(operationEndpointOffset(drag, eye, view));
   }

   public static double operationEndpointOffset(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(drag.origin(), drag.axisVector(), eye, view);
      return axisPoint.subtract(drag.origin()).dot(drag.axisVector());
   }

   public static int geometryRotationSteps(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      Vec3 normal = ClientInputMath.normalize(drag.axisVector());
      double denominator = normal.dot(view);
      if (Math.abs(denominator) < 1.0E-7) {
         return drag.sentSteps();
      }
      double distance = drag.center().subtract(eye).dot(normal) / denominator;
      if (distance < 0.0) {
         return drag.sentSteps();
      }
      Vec3 radial = eye.add(view.scale(distance)).subtract(drag.center());
      if (radial.lengthSqr() < 1.0E-7) {
         return drag.sentSteps();
      }
      Vec3 current = radial.normalize();
      double sin = drag.startTangent().dot(current);
      double cos = drag.startRadial().dot(current);
      double angle = Math.atan2(sin, cos);
      int rawSteps = (int) Math.round(angle * 1024.0 / (Math.PI * 2.0));
      int turns = (int) Math.round((drag.sentSteps() - rawSteps) / 1024.0);
      return rawSteps + turns * 1024;
   }

   public static int rotationSteps(double radians) {
      return (int) Math.round(radians * 1024.0 / (Math.PI * 2.0));
   }

   public static Vec3 rotationTangent(Vec3 normal, Vec3 radial) {
      return ClientInputMath.normalize(normal).cross(radial).normalize();
   }
}
