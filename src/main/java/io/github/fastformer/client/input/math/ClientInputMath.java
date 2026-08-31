package io.github.fastformer.client.input.math;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Stateless coordinate and axis helpers shared by input gesture routes. */
public final class ClientInputMath {
   private static final double EPSILON = 1.0E-7;

   private ClientInputMath() {
   }

   public static int stepToward(int current, int target, int limit) {
      long delta = Math.clamp((long) target - current, -(long) limit, (long) limit);
      return (int) ((long) current + delta);
   }

   public static long maximumCoordinateDelta(BlockPos first, BlockPos second) {
      return Math.max(
         Math.max(Math.abs((long) first.getX() - second.getX()), Math.abs((long) first.getY() - second.getY())),
         Math.abs((long) first.getZ() - second.getZ())
      );
   }

   public static int safeCoordinate(int initial, long offset) {
      long result = initial + offset;
      return (int) Math.clamp(result, Integer.MIN_VALUE + 1L, Integer.MAX_VALUE - 1L);
   }

   public static int axisCoordinate(BlockPos point, int axis) {
      return axis == 0 ? point.getX() : axis == 1 ? point.getY() : point.getZ();
   }

   public static BlockPos withAxisCoordinate(BlockPos point, int axis, int coordinate) {
      return axis == 0 ? new BlockPos(coordinate, point.getY(), point.getZ())
         : axis == 1 ? new BlockPos(point.getX(), coordinate, point.getZ())
         : axis == 2 ? new BlockPos(point.getX(), point.getY(), coordinate) : point;
   }

   public static int geometryOperationIndex(AxisGizmo.Operation operation) {
      return operation == AxisGizmo.Operation.MOVE ? 0
         : operation == AxisGizmo.Operation.SCALE ? 1 : 2;
   }

   public static int geometryAxisIndex(AxisGizmo.Axis axis) {
      return axis == AxisGizmo.Axis.X ? 0 : axis == AxisGizmo.Axis.Y ? 1 : 2;
   }

   public static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return axis == AxisGizmo.Axis.X ? value.x : axis == AxisGizmo.Axis.Y ? value.y : value.z;
   }

   public static int clampDragSteps(int steps, int limit) {
      return Math.clamp(steps, -limit, limit);
   }

   public static Vec3 worldAxis(int axis) {
      return axis == 0 ? new Vec3(1.0, 0.0, 0.0)
         : axis == 1 ? new Vec3(0.0, 1.0, 0.0)
         : axis == 2 ? new Vec3(0.0, 0.0, 1.0) : Vec3.ZERO;
   }

   public static double vecAxisComponent(Vec3 value, int axis) {
      return axis == 0 ? value.x : axis == 1 ? value.y : value.z;
   }

   public static Vec3 normalize(Vec3 vector) {
      return vector == null || vector.lengthSqr() < EPSILON ? Vec3.ZERO : vector.normalize();
   }
}
