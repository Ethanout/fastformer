package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Immutable Cartesian repetition interval used by repeat preview and execution. */
public record OperationStackRegion(BlockPos min, BlockPos max) {
   public static final int ENDPOINT_LIMIT = 128;
   private static final OperationStackRegion ORIGIN = new OperationStackRegion(BlockPos.ZERO, BlockPos.ZERO);

   public OperationStackRegion {
      if (min == null || max == null
         || min.getX() > max.getX()
         || min.getY() > max.getY()
         || min.getZ() > max.getZ()) {
         throw new IllegalArgumentException("Invalid operation stack region");
      }
      min = min.immutable();
      max = max.immutable();
   }

   public static OperationStackRegion origin() {
      return ORIGIN;
   }

   public OperationStackRegion repeat(AxisGizmo.Axis axis, int direction, int copies) {
      if (axis == null || direction == 0 || copies <= 0) {
         return this;
      }
      int axisIndex = axis.ordinal();
      int oldMin = coordinate(this.min, axisIndex);
      int oldMax = coordinate(this.max, axisIndex);
      long expansion = copies;
      if (direction > 0) {
         int newMax = (int)Math.min(ENDPOINT_LIMIT, (long)oldMax + expansion);
         if (newMax == oldMax) {
            return this;
         }
         return new OperationStackRegion(this.min, withCoordinate(this.max, axisIndex, newMax));
      }
      int newMin = (int)Math.max(-ENDPOINT_LIMIT, (long)oldMin - expansion);
      if (newMin == oldMin) {
         return this;
      }
      return new OperationStackRegion(withCoordinate(this.min, axisIndex, newMin), this.max);
   }

   /**
    * Moves one repeat boundary while keeping its handle bound to its original face.
    * A positive face therefore never becomes a negative face (and vice versa): it
    * simply stops at the source cell when pulled back past the origin.
    */
   public OperationStackRegion withAxisEndpoint(AxisGizmo.Axis axis, int direction, int delta) {
      if (axis == null || direction == 0 || delta == 0) {
         return this;
      }
      int axisIndex = axis.ordinal();
      if (direction > 0) {
         int endpoint = coordinate(this.max, axisIndex);
         int updated = (int)Math.clamp((long)endpoint + delta, 0L, (long)ENDPOINT_LIMIT);
         return updated == endpoint ? this : new OperationStackRegion(this.min, withCoordinate(this.max, axisIndex, updated));
      }
      int endpoint = coordinate(this.min, axisIndex);
      int updated = (int)Math.clamp((long)endpoint - delta, (long)-ENDPOINT_LIMIT, 0L);
      return updated == endpoint ? this : new OperationStackRegion(withCoordinate(this.min, axisIndex, updated), this.max);
   }

   public long cellCount() {
      long x = (long)this.max.getX() - this.min.getX() + 1L;
      long y = (long)this.max.getY() - this.min.getY() + 1L;
      long z = (long)this.max.getZ() - this.min.getZ() + 1L;
      return saturatingMultiply(saturatingMultiply(x, y), z);
   }

   public List<BlockPos> repetitions(int limit) {
      if (limit <= 0) {
         return List.of();
      }
      int capacity = (int)Math.min((long)limit, this.cellCount());
      List<BlockPos> result = new ArrayList<>(capacity);
      outer:
      for (int x = this.min.getX(); x <= this.max.getX(); x++) {
         for (int y = this.min.getY(); y <= this.max.getY(); y++) {
            for (int z = this.min.getZ(); z <= this.max.getZ(); z++) {
               result.add(new BlockPos(x, y, z));
               if (result.size() >= limit) {
                  break outer;
               }
            }
         }
      }
      return List.copyOf(result);
   }

   private static long saturatingMultiply(long left, long right) {
      return left != 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
   }

   private static int coordinate(BlockPos pos, int axis) {
      return switch (axis) {
         case 0 -> pos.getX();
         case 1 -> pos.getY();
         default -> pos.getZ();
      };
   }

   private static BlockPos withCoordinate(BlockPos pos, int axis, int value) {
      return switch (axis) {
         case 0 -> new BlockPos(value, pos.getY(), pos.getZ());
         case 1 -> new BlockPos(pos.getX(), value, pos.getZ());
         default -> new BlockPos(pos.getX(), pos.getY(), value);
      };
   }
}
