package io.github.fastformer.workspace.transform;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import net.minecraft.world.phys.Vec3;

/** Shared orientation math for workspace handles and rotation edits. */
public final class WorkspaceRotation {
   private WorkspaceRotation() {
   }

   public static Vec3 localAxis(WorkspaceTransform transform, AxisGizmo.Axis axis) {
      return transform.symmetry().vector(rotateVector(unitAxis(axis), transform.rotation()));
   }

   public static TransformFrame frame(WorkspaceTransform transform, Vec3 center) {
      return TransformFrame.local(center, localAxis(transform, AxisGizmo.Axis.X),
         localAxis(transform, AxisGizmo.Axis.Y), localAxis(transform, AxisGizmo.Axis.Z));
   }

   public static Vec3 rotateWorld(WorkspaceTransform transform, Vec3 worldAxis, double radians) {
      Vec3 normal = transform.symmetry().localRotationAxis(worldAxis).normalize();
      Vec3 x = rotateAroundAxis(rotateVector(unitAxis(AxisGizmo.Axis.X), transform.rotation()), normal, radians);
      Vec3 y = rotateAroundAxis(rotateVector(unitAxis(AxisGizmo.Axis.Y), transform.rotation()), normal, radians);
      Vec3 z = rotateAroundAxis(rotateVector(unitAxis(AxisGizmo.Axis.Z), transform.rotation()), normal, radians);
      // Decompose Rz * Ry * Rx. At the pole, one canonical angle replaces the two
      // equivalent angles without losing the composed orientation.
      double pitch = Math.asin(Math.clamp(-x.z, -1.0, 1.0));
      if (Math.hypot(x.x, x.y) < 1.0E-8) {
         return new Vec3(0.0, Math.copySign(Math.PI * 0.5, -x.z), Math.atan2(-y.x, y.y));
      }
      return new Vec3(Math.atan2(y.z, z.z), pitch, Math.atan2(x.y, x.x));
   }

   public static Vec3 rotateAroundAxis(Vec3 vector, Vec3 axis, double radians) {
      Vec3 normal = axis.normalize();
      double sin = Math.sin(radians);
      double cos = Math.cos(radians);
      return vector.scale(cos).add(normal.cross(vector).scale(sin))
         .add(normal.scale(normal.dot(vector) * (1.0 - cos)));
   }

   private static Vec3 rotateVector(Vec3 value, Vec3 rotation) {
      value = VoxelRotation.rotatePoint(value, Vec3.ZERO, AxisGizmo.Axis.X, rotation.x);
      value = VoxelRotation.rotatePoint(value, Vec3.ZERO, AxisGizmo.Axis.Y, rotation.y);
      return VoxelRotation.rotatePoint(value, Vec3.ZERO, AxisGizmo.Axis.Z, rotation.z);
   }

   public static Vec3 unitAxis(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> new Vec3(1, 0, 0);
         case Y -> new Vec3(0, 1, 0);
         case Z -> new Vec3(0, 0, 1);
      };
   }
}
