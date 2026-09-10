package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

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

   public static OperationSelectionVolume cuboid(BlockPos min, BlockPos max, BlockPos point1, BlockPos point2) {
      if (min == null || max == null) {
         return null;
      }
      BlockPos low = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
      BlockPos high = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
      return new OperationSelectionVolume(
         OperationSelectionMode.CUBOID,
         new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1.0, high.getY() + 1.0, high.getZ() + 1.0),
         null, List.of(), 0, point1, point2
      );
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

   public OperationSelectionVolume withCuboidPoint(int index, BlockPos point) {
      if (this.mode != OperationSelectionMode.CUBOID || index < 0 || index > 1 || point == null) {
         throw new IllegalArgumentException("Invalid cuboid point edit");
      }
      BlockPos first = index == 0 ? point : this.point1;
      BlockPos second = index == 1 ? point : this.point2;
      if (first == null || second == null) {
         BlockPos only = first != null ? first : second;
         return new OperationSelectionVolume(
            OperationSelectionMode.CUBOID,
            new AABB(only.getX(), only.getY(), only.getZ(), only.getX() + 1.0, only.getY() + 1.0, only.getZ() + 1.0),
            null, List.of(), 0, first, second
         );
      }
      return create(this.mode, List.of(first, second), BlockPos.ZERO, BlockPos.ZERO, 0);
   }

   public OperationSelectionVolume expandCuboidTo(BlockPos point) {
      if (this.mode != OperationSelectionMode.CUBOID || point == null) {
         throw new IllegalArgumentException("Invalid cuboid expansion");
      }
      AABB expanded = new AABB(
         Math.min(bounds.minX, point.getX()), Math.min(bounds.minY, point.getY()), Math.min(bounds.minZ, point.getZ()),
         Math.max(bounds.maxX, point.getX() + 1.0), Math.max(bounds.maxY, point.getY() + 1.0), Math.max(bounds.maxZ, point.getZ() + 1.0)
      );
      return new OperationSelectionVolume(mode, expanded, null, List.of(), 0, point1, point2);
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
