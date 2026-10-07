package io.github.fastformer.workspace.model;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Exact quarter turns and reflections applied after the existing voxel transform. */
public record WorkspaceSymmetry(int x, int y, int z, Vec3 offset) {
   public static final WorkspaceSymmetry IDENTITY = new WorkspaceSymmetry(1, 2, 3, Vec3.ZERO);

   public WorkspaceSymmetry {
      if (Math.abs(y) != 2 || !horizontal(x) || !horizontal(z) || Math.abs(x) == Math.abs(z)) {
         throw new IllegalArgumentException("Invalid workspace symmetry axes");
      }
      if (offset == null || !Double.isFinite(offset.x) || !Double.isFinite(offset.y) || !Double.isFinite(offset.z)) {
         throw new IllegalArgumentException("Workspace symmetry offset must be finite");
      }
   }

   private static boolean horizontal(int axis) {
      return axis == 1 || axis == -1 || axis == 3 || axis == -3;
   }

   public Vec3 vector(Vec3 value) {
      return new Vec3(component(value, x), component(value, y), component(value, z));
   }

   public Vec3 point(Vec3 value) {
      return vector(value).add(offset);
   }

   /** Converts a world rotation axis back through this orientation, including reflection handedness. */
   public Vec3 localRotationAxis(Vec3 worldAxis) {
      int determinant = (Math.abs(x) == 1 ? 1 : -1) * Integer.signum(x) * Integer.signum(y) * Integer.signum(z);
      return new Vec3(worldAxis.dot(vector(new Vec3(1, 0, 0))),
         worldAxis.dot(vector(new Vec3(0, 1, 0))), worldAxis.dot(vector(new Vec3(0, 0, 1)))).scale(determinant);
   }

   private static double component(Vec3 value, int axis) {
      double component = switch (Math.abs(axis)) {
         case 1 -> value.x;
         case 2 -> value.y;
         default -> value.z;
      };
      return axis < 0 ? -component : component;
   }

   /** Applies the next world-oriented operation around its shared local pivot. */
   public WorkspaceSymmetry then(WorkspaceSymmetry next, Vec3 pivot) {
      Vec3 displacement = next.vector(offset).add(pivot).subtract(next.vector(pivot));
      return new WorkspaceSymmetry(compose(next.x), compose(next.y), compose(next.z), displacement);
   }

   private int compose(int axis) {
      int source = switch (Math.abs(axis)) {
         case 1 -> x;
         case 2 -> y;
         default -> z;
      };
      return axis < 0 ? -source : source;
   }

   /** Clockwise when viewed from above. */
   public static WorkspaceSymmetry quarterTurnY() {
      return new WorkspaceSymmetry(-3, 2, 1, Vec3.ZERO);
   }

   public static WorkspaceSymmetry reflection(AxisGizmo.Axis axis) {
      return new WorkspaceSymmetry(axis == AxisGizmo.Axis.X ? -1 : 1,
         axis == AxisGizmo.Axis.Y ? -2 : 2, axis == AxisGizmo.Axis.Z ? -3 : 3, Vec3.ZERO);
   }

   public boolean identityOrientation() {
      return x == 1 && y == 2 && z == 3;
   }

   public CompoundTag encode() {
      CompoundTag tag = new CompoundTag();
      tag.putInt("X", x);
      tag.putInt("Y", y);
      tag.putInt("Z", z);
      tag.putDouble("OffsetX", offset.x);
      tag.putDouble("OffsetY", offset.y);
      tag.putDouble("OffsetZ", offset.z);
      return tag;
   }

   public static WorkspaceSymmetry decode(CompoundTag tag) {
      return new WorkspaceSymmetry(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"),
         new Vec3(tag.getDouble("OffsetX"), tag.getDouble("OffsetY"), tag.getDouble("OffsetZ")));
   }
}
