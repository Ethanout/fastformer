package io.github.fastformer.fastplace.geometry.generation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A conservative integer box whose solid and six-neighbor boundary can be counted before output. */
final class BoundedAabbVolume {
   private int minimumX;
   private int minimumY;
   private int minimumZ;
   private int maximumX;
   private int maximumY;
   private int maximumZ;

   private BoundedAabbVolume(BlockPos first) {
      this.minimumX = this.maximumX = first.getX();
      this.minimumY = this.maximumY = first.getY();
      this.minimumZ = this.maximumZ = first.getZ();
   }

   static BoundedAabbVolume containing(List<Vec3> vertices) {
      if (vertices == null || vertices.isEmpty()) {
         throw new IllegalArgumentException("an AABB needs at least one vertex");
      }
      BoundedAabbVolume result = new BoundedAabbVolume(BlockPos.containing(vertices.getFirst()));
      for (int index = 1; index < vertices.size(); index++) {
         result.include(BlockPos.containing(vertices.get(index)));
      }
      return result;
   }

   void include(BlockPos position) {
      this.minimumX = Math.min(this.minimumX, position.getX());
      this.minimumY = Math.min(this.minimumY, position.getY());
      this.minimumZ = Math.min(this.minimumZ, position.getZ());
      this.maximumX = Math.max(this.maximumX, position.getX());
      this.maximumY = Math.max(this.maximumY, position.getY());
      this.maximumZ = Math.max(this.maximumZ, position.getZ());
   }

   long solidCount() {
      return saturatedMultiply(saturatedMultiply(this.sizeX(), this.sizeY()), this.sizeZ());
   }

   long boundaryCount() {
      long x = this.sizeX();
      long y = this.sizeY();
      long z = this.sizeZ();
      long zFaces = saturatedMultiply(endpointCount(z), saturatedMultiply(x, y));
      long yFaces = saturatedMultiply(endpointCount(y), saturatedMultiply(x, interiorCount(z)));
      long xFaces = saturatedMultiply(endpointCount(x), saturatedMultiply(interiorCount(y), interiorCount(z)));
      return saturatedAdd(saturatedAdd(zFaces, yFaces), xFaces);
   }

   Set<BlockPos> materialize(boolean boundaryOnly, int maxBlocks, BlockGenerationObserver observer) {
      long count = boundaryOnly ? this.boundaryCount() : this.solidCount();
      if (count > maxBlocks) {
         return GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      Set<BlockPos> blocks = new ObservedBlockSet(observer);
      if (boundaryOnly) {
         this.addBoundary(blocks);
      } else {
         this.addSolid(blocks);
      }
      if (blocks.size() != count) {
         throw new IllegalStateException("AABB count/materialization mismatch");
      }
      return Collections.unmodifiableSet(new LinkedHashSet<>(blocks));
   }

   private void addSolid(Set<BlockPos> blocks) {
      for (long x = this.minimumX; x <= this.maximumX; x++) {
         for (long y = this.minimumY; y <= this.maximumY; y++) {
            for (long z = this.minimumZ; z <= this.maximumZ; z++) {
               blocks.add(new BlockPos((int)x, (int)y, (int)z));
            }
         }
      }
   }

   /** Emits three disjoint pairs of faces, so work is O(boundary), not O(volume). */
   private void addBoundary(Set<BlockPos> blocks) {
      for (long x = this.minimumX; x <= this.maximumX; x++) {
         for (long y = this.minimumY; y <= this.maximumY; y++) {
            addEndpoints(blocks, x, y, this.minimumZ, this.maximumZ, 2);
         }
      }
      for (long x = this.minimumX; x <= this.maximumX; x++) {
         for (long z = (long)this.minimumZ + 1L; z < this.maximumZ; z++) {
            addEndpoints(blocks, x, z, this.minimumY, this.maximumY, 1);
         }
      }
      for (long y = (long)this.minimumY + 1L; y < this.maximumY; y++) {
         for (long z = (long)this.minimumZ + 1L; z < this.maximumZ; z++) {
            addEndpoints(blocks, y, z, this.minimumX, this.maximumX, 0);
         }
      }
   }

   private static void addEndpoints(
      Set<BlockPos> blocks,
      long first,
      long second,
      long minimum,
      long maximum,
      int varyingAxis
   ) {
      addPosition(blocks, first, second, minimum, varyingAxis);
      if (maximum != minimum) {
         addPosition(blocks, first, second, maximum, varyingAxis);
      }
   }

   private static void addPosition(Set<BlockPos> blocks, long first, long second, long value, int varyingAxis) {
      BlockPos position = switch (varyingAxis) {
         case 0 -> new BlockPos((int)value, (int)first, (int)second);
         case 1 -> new BlockPos((int)first, (int)value, (int)second);
         case 2 -> new BlockPos((int)first, (int)second, (int)value);
         default -> throw new IllegalArgumentException("axis " + varyingAxis);
      };
      blocks.add(position);
   }

   private long sizeX() {
      return (long)this.maximumX - this.minimumX + 1L;
   }

   private long sizeY() {
      return (long)this.maximumY - this.minimumY + 1L;
   }

   private long sizeZ() {
      return (long)this.maximumZ - this.minimumZ + 1L;
   }

   private static long endpointCount(long size) {
      return size == 1L ? 1L : 2L;
   }

   private static long interiorCount(long size) {
      return Math.max(0L, size - 2L);
   }

   private static long saturatedAdd(long first, long second) {
      return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
   }

   private static long saturatedMultiply(long first, long second) {
      return first != 0L && second > Long.MAX_VALUE / first ? Long.MAX_VALUE : first * second;
   }
}
