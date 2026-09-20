package io.github.fastformer.client.operation.preview;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import io.github.fastformer.client.render.mask.SourceMaskRenderHooks;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lifecycle handle for the render-only source mask.
 *
 * <p>The mask hides source blocks from the render layer only. It writes no block state and
 * no block entity data into the client level, so collision, selection, and block entities
 * keep the server state.
 *
 * <p>{@link #replace} publishes one immutable snapshot. {@link #reapply} marks the sections
 * of every changed position as dirty, so the client rebuilds them from the new snapshot.
 * The mask holds no captured world state, so {@link #clear} and {@link #discard} do the same
 * work: they publish the empty mask and the source blocks come back with the next rebuild.
 */
public final class SourceBlockRenderMask {
   /** Publishes the source positions that the render layer must hide. */
   public void replace(Collection<BlockPos> values) {
      SourceMaskRenderFilter.instance().publish(values);
   }

   public boolean contains(BlockPos pos) {
      return SourceMaskRenderFilter.instance().hides(pos);
   }

   /** Returns whether the normal world renderer must skip this source position. */
   public boolean shouldSkipWorldRender(BlockPos pos) {
      return contains(pos);
   }

   /** Replaces the visible state of a masked position with the hidden placeholder. */
   public BlockState maskedState(BlockPos pos, BlockState visibleState) {
      return SourceMaskRenderFilter.instance().masked(pos, visibleState);
   }

   public Set<BlockPos> positions() {
      return SourceMaskRenderFilter.instance().positions();
   }

   /** Marks the changed sections as dirty. Call once per client tick. */
   public void reapply() {
      SourceMaskRenderHooks.reconcile();
   }

   /**
    * Source positions that a workspace draft must keep hidden in the client world.
    *
    * <p>Only world parts that displace or delete their source hide it. Clipboard parts and
    * world parts that still sit on their own source keep no hidden position.
    */
   public static Set<BlockPos> maskedSourcePositions(Collection<ClientSelectionPart> parts) {
      LinkedHashSet<BlockPos> masked = new LinkedHashSet<>();
      if (parts == null) {
         return Set.of();
      }
      for (ClientSelectionPart part : parts) {
         if (part == null || part.source() != ClientSelectionPart.Source.WORLD
            || !part.masksSourceBlocks()) {
            continue;
         }
         part.sourceSnapshot().keySet().forEach(pos -> {
            if (pos != null) masked.add(pos.immutable());
         });
      }
      return java.util.Collections.unmodifiableSet(masked);
   }

   /** Publishes the empty mask. The source blocks reappear with the next section rebuild. */
   public void clear() {
      SourceMaskRenderFilter.instance().clear();
   }

   /** Ends the mask after a completed move. The server owns the committed state. */
   public void discard() {
      SourceMaskRenderFilter.instance().clear();
   }

   /**
    * Ends the mask after the server accepted the move.
    *
    * <p>The committed blocks come from the server update, never from a local prediction. The
    * client only stops hiding the old source positions.
    */
   public void complete(Map<BlockPos, ClientBlockSnapshot> committedTargets) {
      this.discard();
   }
}
