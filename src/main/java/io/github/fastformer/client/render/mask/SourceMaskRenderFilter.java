package io.github.fastformer.client.render.mask;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Render-only registry for the source positions that a ghost preview replaces.
 *
 * <p>The filter writes no block state and no block entity data into the client level.
 * The section builder reads a mask through one immutable {@link Snapshot}, so every block
 * of one mesh sees the same mask revision. {@link #publish} swaps the snapshot reference
 * once; a reader either sees the old snapshot or the new one, never a mixture.
 *
 * <p>Reads from the asynchronous meshing thread are safe. Writes come from the client
 * thread only.
 */
public final class SourceMaskRenderFilter {
   private static final SourceMaskRenderFilter INSTANCE = new SourceMaskRenderFilter();

   private volatile Snapshot snapshot = Snapshot.EMPTY;

   private SourceMaskRenderFilter() {
   }

   public static SourceMaskRenderFilter instance() {
      return INSTANCE;
   }

   /** Returns the current mask. One read is enough for a whole mesh build. */
   public Snapshot snapshot() {
      return this.snapshot;
   }

   public long revision() {
      return this.snapshot.revision;
   }

   public boolean isEmpty() {
      return this.snapshot.isEmpty();
   }

   public boolean hides(BlockPos pos) {
      return pos != null && this.snapshot.hides(pos.asLong());
   }

   public boolean hides(long packedPos) {
      return this.snapshot.hides(packedPos);
   }

   public Set<BlockPos> positions() {
      return this.snapshot.positions;
   }

   /**
    * Publishes a new mask.
    *
    * @return true when the position set changed. An unchanged set keeps the current
    *     revision, so a repeated publish causes no rebuild.
    */
   public boolean publish(Collection<BlockPos> values) {
      Set<BlockPos> next = immutableCopy(values);
      Snapshot current = this.snapshot;
      if (next.equals(current.positions)) {
         return false;
      }

      this.snapshot = new Snapshot(current.revision + 1L, next);
      return true;
   }

   /** Publishes the empty mask. The next section rebuild shows the real world again. */
   public boolean clear() {
      return this.publish(Set.of());
   }

   /**
    * The state that a hidden source position presents to the render layer.
    *
    * <p>Void air has no block mesh, no fluid, no block entity, and no collision box, so the
    * section builder drops the block from the mesh and from the visibility graph.
    */
   public static BlockState maskedBlockState() {
      return Blocks.VOID_AIR.defaultBlockState();
   }

   /**
    * Returns the render state of a position: the hidden placeholder for a masked position,
    * and the given state otherwise.
    *
    * <p>Use this in a render-layer {@code BlockAndTintGetter} that reads the client level,
    * such as the ghost preview level. Without it, the geometry builder culls the faces
    * between a ghost block and a masked source block, because the client level still holds
    * the real source block.
    */
   public BlockState masked(BlockPos pos, BlockState visibleState) {
      return this.hides(pos) ? maskedBlockState() : visibleState;
   }

   private static Set<BlockPos> immutableCopy(Collection<BlockPos> values) {
      if (values == null || values.isEmpty()) {
         return Set.of();
      }

      LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
      for (BlockPos pos : values) {
         if (pos != null) {
            copy.add(pos.immutable());
         }
      }

      return copy.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(copy);
   }

   /**
    * One immutable mask revision.
    *
    * <p>A section render region binds one instance when the region is built. The instance
    * never changes afterwards, so a long mesh build cannot mix two revisions.
    */
   public static final class Snapshot {
      public static final Snapshot EMPTY = new Snapshot(0L, Set.of());

      private final long revision;
      private final Set<BlockPos> positions;
      private final LongOpenHashSet packedPositions;

      private Snapshot(long revision, Set<BlockPos> positions) {
         this.revision = revision;
         this.positions = positions;
         this.packedPositions = new LongOpenHashSet(Math.max(4, positions.size() * 2));
         for (BlockPos pos : positions) {
            this.packedPositions.add(pos.asLong());
         }
      }

      public long revision() {
         return this.revision;
      }

      public Set<BlockPos> positions() {
         return this.positions;
      }

      public boolean isEmpty() {
         return this.positions.isEmpty();
      }

      public boolean hides(long packedPos) {
         return !this.packedPositions.isEmpty() && this.packedPositions.contains(packedPos);
      }
   }
}
