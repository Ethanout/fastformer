package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public record GeometryHit(BlockPos blockPos, BlockPos placementPos, Direction face, Vec3 location) {
   private static final double SPHERE_CORNER_SNAP_DISTANCE = 0.2;

   public static GeometryHit from(BlockHitResult hit) {
      BlockPos block = hit.getBlockPos();
      return new GeometryHit(block, block, hit.getDirection(), hit.getLocation());
   }

   public static GeometryHit point(BlockPos point) {
      return new GeometryHit(point, point, Direction.UP, Vec3.atCenterOf(point));
   }

   public BlockPos point() {
      return this.placementPos;
   }

   public double horizontalRadiusFrom(Vec3 center) {
      Vec3 offset = this.location.subtract(center);
      return Math.max(0.5, Math.round(Math.hypot(offset.x, offset.z) * 2.0) * 0.5);
   }

   public Vec3 halfGridPlacementPoint() {
      return this.halfGridPoint(false);
   }

   public Vec3 halfGridSurfacePoint() {
      return new Vec3(snapHalf(this.location.x), snapHalf(this.location.y), snapHalf(this.location.z));
   }

   public Vec3 halfGridCenter(boolean preciseSubmode) {
      return this.halfGridPoint(preciseSubmode);
   }

   public Vec3 halfGridPoint(boolean preciseSubmode) {
      if (this.syntheticPoint()) {
         return this.location;
      }
      return preciseSubmode ? this.halfGridSurfacePoint() : Vec3.atCenterOf(this.placementPos);
   }

   /**
    * Cone/prism point selection couples placement and precision: normal input
    * uses the embedded block-center grid, while the Alt surface submode snaps
    * the hit face to half-block coordinates.
    */
   public Vec3 conePoint(boolean surfaceSubmode) {
      return this.halfGridPoint(surfaceSubmode);
   }

   /**
    * Resolves the two-point input used by the sphere workflow.
    *
    * <p>Normal input intentionally stays inside the hit block. The precise
    * submode has only two stable targets: the center of the block beyond the
    * hit face, or a nearby integer grid corner. It does not expose arbitrary
    * half-grid surface coordinates.</p>
    */
   public Vec3 spherePoint(boolean preciseSubmode) {
      if (this.syntheticPoint()) {
         return this.location;
      }
      if (!preciseSubmode) {
         return Vec3.atCenterOf(this.blockPos);
      }

      Vec3 corner = this.nearbyIntegerCorner();
      return corner != null
         ? corner
         : Vec3.atCenterOf(this.blockPos.relative(this.face));
   }

   private boolean syntheticPoint() {
      return this.blockPos.equals(this.placementPos) && this.location.equals(Vec3.atCenterOf(this.blockPos));
   }

   private Vec3 nearbyIntegerCorner() {
      double x = Math.rint(this.location.x);
      double y = Math.rint(this.location.y);
      double z = Math.rint(this.location.z);
      double dx = this.location.x - x;
      double dy = this.location.y - y;
      double dz = this.location.z - z;
      double distanceSqr = dx * dx + dy * dy + dz * dz;
      return distanceSqr <= SPHERE_CORNER_SNAP_DISTANCE * SPHERE_CORNER_SNAP_DISTANCE
         ? new Vec3(x, y, z)
         : null;
   }

   private static double snapHalf(double value) {
      return Math.round(value * 2.0) * 0.5;
   }
}
