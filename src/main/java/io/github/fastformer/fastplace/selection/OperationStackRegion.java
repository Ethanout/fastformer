package io.github.fastformer.fastplace.selection;



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

   /**
    * Receives repetition cells in the order {@link #repetitions(int)} returns them.
    *
    * <p>A visitor lets a caller stop early, so a large region never has to be built in
    * memory to read its first cells.</p>
    */
   @FunctionalInterface
   public interface RepetitionVisitor {
      /**
       * @param position the next cell, in x, then y, then z order
       * @return false to stop the walk
       */
      boolean visit(BlockPos position);
   }

   /** Initial list size for {@link #repetitions(int)}. The list grows on demand. */
   private static final int REPETITION_INITIAL_CAPACITY = 256;

   /**
    * Visits repetition cells without building a list.
    *
    * <p>The order is the order of {@link #repetitions(int)}: x, then y, then z, each from
    * the minimum endpoint to the maximum one. The loop counters are {@code long}, so a
    * region with an extreme endpoint cannot overflow an {@code int} and cannot loop
    * forever.</p>
    *
    * @param limit   maximum number of cells to visit. A value at or below zero visits none.
    * @param visitor receives each cell until it returns false or the limit is reached
    * @return the number of cells this call delivered
    */
   public long visitRepetitions(long limit, RepetitionVisitor visitor) {
      if (limit <= 0L || visitor == null) {
         return 0L;
      }
      long visited = 0L;
      for (long x = this.min.getX(); x <= this.max.getX(); x++) {
         for (long y = this.min.getY(); y <= this.max.getY(); y++) {
            for (long z = this.min.getZ(); z <= this.max.getZ(); z++) {
               boolean keepGoing = visitor.visit(new BlockPos((int)x, (int)y, (int)z));
               visited++;
               if (!keepGoing || visited >= limit) {
                  return visited;
               }
            }
         }
      }
      return visited;
   }

   /** Visits every repetition cell. Prefer the bounded {@link #visitRepetitions(long, RepetitionVisitor)}. */
   public long visitRepetitions(RepetitionVisitor visitor) {
      return visitRepetitions(Long.MAX_VALUE, visitor);
   }

   public List<BlockPos> repetitions(int limit) {
      if (limit <= 0) {
         return List.of();
      }
      // The walk fills the list on demand. Only the first page is reserved, because a
      // caller that passes a large limit must not pay for the whole region up front.
      List<BlockPos> result = new ArrayList<>(Math.min(limit, REPETITION_INITIAL_CAPACITY));
      visitRepetitions(limit, position -> {
         result.add(position);
         return true;
      });
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
