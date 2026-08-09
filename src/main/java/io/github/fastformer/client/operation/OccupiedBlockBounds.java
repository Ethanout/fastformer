package io.github.fastformer.client.operation;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Inclusive integer bounds of blocks actually occupied by a part. */
public record OccupiedBlockBounds(BlockPos min, BlockPos max) {
   public OccupiedBlockBounds {
      if (min == null || max == null
         || min.getX() > max.getX()
         || min.getY() > max.getY()
         || min.getZ() > max.getZ()) {
         throw new IllegalArgumentException("Invalid occupied block bounds");
      }
      min = min.immutable();
      max = max.immutable();
   }

   public static Optional<OccupiedBlockBounds> from(Collection<BlockPos> blocks) {
      if (blocks == null || blocks.isEmpty()) {
         return Optional.empty();
      }
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      int maxZ = Integer.MIN_VALUE;
      for (BlockPos pos : blocks) {
         minX = Math.min(minX, pos.getX());
         minY = Math.min(minY, pos.getY());
         minZ = Math.min(minZ, pos.getZ());
         maxX = Math.max(maxX, pos.getX());
         maxY = Math.max(maxY, pos.getY());
         maxZ = Math.max(maxZ, pos.getZ());
      }
      return Optional.of(new OccupiedBlockBounds(
         new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)
      ));
   }

   public OccupiedBlockBounds union(OccupiedBlockBounds other) {
      return new OccupiedBlockBounds(
         new BlockPos(
            Math.min(this.min.getX(), other.min.getX()),
            Math.min(this.min.getY(), other.min.getY()),
            Math.min(this.min.getZ(), other.min.getZ())
         ),
         new BlockPos(
            Math.max(this.max.getX(), other.max.getX()),
            Math.max(this.max.getY(), other.max.getY()),
            Math.max(this.max.getZ(), other.max.getZ())
         )
      );
   }

   public int width(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> this.max.getX() - this.min.getX() + 1;
         case Y -> this.max.getY() - this.min.getY() + 1;
         case Z -> this.max.getZ() - this.min.getZ() + 1;
      };
   }

   public AxisGizmo.Axis smallestAxis() {
      AxisGizmo.Axis result = AxisGizmo.Axis.X;
      int width = this.width(result);
      for (AxisGizmo.Axis axis : new AxisGizmo.Axis[]{AxisGizmo.Axis.Y, AxisGizmo.Axis.Z}) {
         int candidate = this.width(axis);
         if (candidate < width) {
            result = axis;
            width = candidate;
         }
      }
      return result;
   }

   public Vec3 center() {
      return new Vec3(
         ((double)this.min.getX() + this.max.getX() + 1.0) * 0.5,
         ((double)this.min.getY() + this.max.getY() + 1.0) * 0.5,
         ((double)this.min.getZ() + this.max.getZ() + 1.0) * 0.5
      );
   }

   /** Converts inclusive block coordinates to the corresponding voxel AABB. */
   public AABB aabb() {
      return new AABB(
         this.min.getX(), this.min.getY(), this.min.getZ(),
         this.max.getX() + 1.0, this.max.getY() + 1.0, this.max.getZ() + 1.0
      );
   }
}
