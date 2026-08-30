package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record OperationSelectionVolume(
   OperationSelectionMode mode,
   AABB bounds,
   SelectionPrism prism,
   List<OperationGeometry.HullFace> hullFaces,
   int hullInflation,
   BlockPos point1,
   BlockPos point2
) {
   public static final double RAYCAST_INFLATE = 0.01;

   public OperationSelectionVolume {
      hullFaces = hullFaces == null ? List.of() : List.copyOf(hullFaces);
      point1 = point1 == null ? null : point1.immutable();
      point2 = point2 == null ? null : point2.immutable();
   }

   public static OperationSelectionVolume create(
      OperationSelectionMode mode, List<BlockPos> points, BlockPos minOffset, BlockPos maxOffset, int hullInflation
   ) {
      int legacyBasePointCount = mode == OperationSelectionMode.PRISM && points != null && points.size() >= 4
         ? points.size() - 1
         : 0;
      return create(mode, points, legacyBasePointCount, minOffset, maxOffset, hullInflation);
   }

   public static OperationSelectionVolume create(
      OperationSelectionMode mode,
      List<BlockPos> points,
      int prismBasePointCount,
      BlockPos minOffset,
      BlockPos maxOffset,
      int hullInflation
   ) {
      if (mode == null || points == null || points.isEmpty()) {
         return null;
      }
      if (mode == OperationSelectionMode.PRISM) {
         SelectionPrism prism = SelectionPrism.fromPoints(points, prismBasePointCount, minOffset, maxOffset);
         return prism == null ? null : new OperationSelectionVolume(mode, prism.bounds(), prism, List.of(), 0,
            points.size() > 0 ? points.getFirst() : null, points.size() > 1 ? points.get(1) : null);
      }
      AABB bounds = OperationGeometry.bounds(points, minOffset, maxOffset, hullInflation);
      if (bounds == null) {
         return null;
      }
      List<OperationGeometry.HullFace> faces = mode == OperationSelectionMode.CONVEX_HULL
         ? OperationGeometry.convexHullFaces(points)
         : List.of();
      return new OperationSelectionVolume(mode, bounds, null, faces, mode == OperationSelectionMode.CONVEX_HULL ? hullInflation : 0,
         points.size() > 0 ? points.getFirst() : null, points.size() > 1 ? points.get(1) : null);
   }

   public boolean contains(Vec3 point) {
      return this.mode == OperationSelectionMode.CUBOID
         ? this.bounds.contains(point)
         : this.prism != null
         ? this.prism.contains(point)
         : OperationGeometry.insideConvexHull(point, this.hullFaces, this.hullInflation);
   }

   public boolean intersects(AABB box) {
      return this.prism != null ? this.prism.intersects(box) : this.bounds.intersects(box);
   }

   public OperationGeometry.RayHit raycast(Vec3 origin, Vec3 direction, double maxDistance) {
      if (this.prism != null) {
         return this.prism.raycast(origin, direction, maxDistance);
      }
      AABB raycastBounds = this.mode == OperationSelectionMode.CUBOID
         ? this.bounds.inflate(RAYCAST_INFLATE)
         : this.bounds;
      return OperationGeometry.raycast(raycastBounds, origin, direction, maxDistance);
   }

   public Vec3 axis(int axis) {
      if (this.prism != null) {
         return this.prism.axis(axis);
      }
      return switch (axis) {
         case 0 -> new Vec3(1.0, 0.0, 0.0);
         case 1 -> new Vec3(0.0, 1.0, 0.0);
         case 2 -> new Vec3(0.0, 0.0, 1.0);
         default -> Vec3.ZERO;
      };
   }
}
