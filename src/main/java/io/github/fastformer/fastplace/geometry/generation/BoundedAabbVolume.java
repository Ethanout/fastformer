package io.github.fastformer.fastplace.geometry.generation;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
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
      return new AabbBlockSet(
         this.minimumX, this.minimumY, this.minimumZ,
         this.maximumX, this.maximumY, this.maximumZ,
         boundaryOnly, Math.toIntExact(count)
      );
   }

   static boolean usesLazyStorageForTesting(Set<BlockPos> blocks) {
      return blocks instanceof AabbBlockSet;
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

   private static final class AabbBlockSet extends AbstractSet<BlockPos> {
      private final int minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ, size;
      private final boolean boundaryOnly;

      private AabbBlockSet(
         int minimumX, int minimumY, int minimumZ,
         int maximumX, int maximumY, int maximumZ,
         boolean boundaryOnly, int size
      ) {
         this.minimumX = minimumX;
         this.minimumY = minimumY;
         this.minimumZ = minimumZ;
         this.maximumX = maximumX;
         this.maximumY = maximumY;
         this.maximumZ = maximumZ;
         this.boundaryOnly = boundaryOnly;
         this.size = size;
      }

      @Override
      public int size() {
         return this.size;
      }

      @Override
      public boolean contains(Object candidate) {
         if (!(candidate instanceof BlockPos position)
            || position.getX() < this.minimumX || position.getX() > this.maximumX
            || position.getY() < this.minimumY || position.getY() > this.maximumY
            || position.getZ() < this.minimumZ || position.getZ() > this.maximumZ) {
            return false;
         }
         return !this.boundaryOnly
            || position.getX() == this.minimumX || position.getX() == this.maximumX
            || position.getY() == this.minimumY || position.getY() == this.maximumY
            || position.getZ() == this.minimumZ || position.getZ() == this.maximumZ;
      }

      @Override
      public Iterator<BlockPos> iterator() {
         if (this.boundaryOnly) {
            return new BoundaryIterator();
         }
         return new Iterator<>() {
            private long x = AabbBlockSet.this.minimumX;
            private long y = AabbBlockSet.this.minimumY;
            private long z = AabbBlockSet.this.minimumZ;
            private BlockPos next;
            private boolean finished;

            @Override
            public boolean hasNext() {
               while (this.next == null && !this.finished) {
                  BlockPos candidate = new BlockPos((int)this.x, (int)this.y, (int)this.z);
                  advance();
                  this.next = candidate;
               }
               return this.next != null;
            }

            @Override
            public BlockPos next() {
               if (!hasNext()) {
                  throw new NoSuchElementException();
               }
               BlockPos result = this.next;
               this.next = null;
               return result;
            }

            private void advance() {
               if (++this.z <= AabbBlockSet.this.maximumZ) {
                  return;
               }
               this.z = AabbBlockSet.this.minimumZ;
               if (++this.y <= AabbBlockSet.this.maximumY) {
                  return;
               }
               this.y = AabbBlockSet.this.minimumY;
               if (++this.x > AabbBlockSet.this.maximumX) {
                  this.finished = true;
               }
            }
         };
      }

      private final class BoundaryIterator implements Iterator<BlockPos> {
         private int phase;
         private long first = AabbBlockSet.this.minimumX;
         private long second = AabbBlockSet.this.minimumY;
         private int endpoint;
         private BlockPos next;
         private boolean finished;

         @Override
         public boolean hasNext() {
            if (this.next == null && !this.finished) {
               prepare();
            }
            return this.next != null;
         }

         @Override
         public BlockPos next() {
            if (!hasNext()) {
               throw new NoSuchElementException();
            }
            BlockPos result = this.next;
            this.next = null;
            return result;
         }

         private void prepare() {
            while (this.next == null && !this.finished) {
               switch (this.phase) {
                  case 0 -> prepareZFace();
                  case 1 -> prepareYFace();
                  case 2 -> prepareXFace();
                  default -> this.finished = true;
               }
            }
         }

         private void prepareZFace() {
            if (this.first > AabbBlockSet.this.maximumX) {
               this.phase = 1;
               this.first = AabbBlockSet.this.minimumX;
               this.second = (long)AabbBlockSet.this.minimumZ + 1L;
               this.endpoint = 0;
               return;
            }
            this.next = endpoint(this.first, this.second, 2);
            advanceEndpoints(
               AabbBlockSet.this.minimumZ, AabbBlockSet.this.maximumZ,
               () -> {
                  if (++this.second > AabbBlockSet.this.maximumY) {
                     this.second = AabbBlockSet.this.minimumY;
                     this.first++;
                  }
               }
            );
         }

         private void prepareYFace() {
            if (this.first > AabbBlockSet.this.maximumX
               || this.second >= AabbBlockSet.this.maximumZ) {
               this.phase = 2;
               this.first = (long)AabbBlockSet.this.minimumY + 1L;
               this.second = (long)AabbBlockSet.this.minimumZ + 1L;
               this.endpoint = 0;
               return;
            }
            this.next = endpoint(this.first, this.second, 1);
            advanceEndpoints(
               AabbBlockSet.this.minimumY, AabbBlockSet.this.maximumY,
               () -> {
                  if (++this.second >= AabbBlockSet.this.maximumZ) {
                     this.second = (long)AabbBlockSet.this.minimumZ + 1L;
                     this.first++;
                  }
               }
            );
         }

         private void prepareXFace() {
            if (this.first >= AabbBlockSet.this.maximumY
               || this.second >= AabbBlockSet.this.maximumZ) {
               this.finished = true;
               return;
            }
            this.next = endpoint(this.first, this.second, 0);
            advanceEndpoints(
               AabbBlockSet.this.minimumX, AabbBlockSet.this.maximumX,
               () -> {
                  if (++this.second >= AabbBlockSet.this.maximumZ) {
                     this.second = (long)AabbBlockSet.this.minimumZ + 1L;
                     this.first++;
                  }
               }
            );
         }

         private BlockPos endpoint(long first, long second, int varyingAxis) {
            long value = this.endpoint == 0
               ? varyingAxis == 2 ? AabbBlockSet.this.minimumZ
                  : varyingAxis == 1 ? AabbBlockSet.this.minimumY : AabbBlockSet.this.minimumX
               : varyingAxis == 2 ? AabbBlockSet.this.maximumZ
                  : varyingAxis == 1 ? AabbBlockSet.this.maximumY : AabbBlockSet.this.maximumX;
            return switch (varyingAxis) {
               case 0 -> new BlockPos((int)value, (int)first, (int)second);
               case 1 -> new BlockPos((int)first, (int)value, (int)second);
               case 2 -> new BlockPos((int)first, (int)second, (int)value);
               default -> throw new IllegalArgumentException("axis " + varyingAxis);
            };
         }

         private void advanceEndpoints(long minimum, long maximum, Runnable afterPair) {
            if (minimum == maximum || this.endpoint++ == 1) {
               this.endpoint = 0;
               afterPair.run();
            }
         }
      }
   }
}
