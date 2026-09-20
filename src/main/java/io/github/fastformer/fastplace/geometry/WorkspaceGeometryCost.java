package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import java.util.Collection;
import net.minecraft.core.BlockPos;

/**
 * Pure cost model shared by the render budget and the submission validator.
 *
 * <p>The model separates three quantities:
 *
 * <ul>
 *   <li>{@link Cost#sourceCount} is the exact number of occupied source voxels.
 *   <li>{@link Cost#scanVolume} is the number of cells the nearest-neighbour sampler visits. It
 *       is 0 when scaling cannot change an occupied width, because then no scan runs at all.
 *   <li>{@link Cost#writtenUpperBound} bounds the voxels written for one repetition. Nearest
 *       neighbour scaling duplicates source voxels, so an upscale writes more voxels than the
 *       source holds. The bound is the scan volume, never the source count.
 * </ul>
 *
 * <p>All methods are side-effect free and do not touch a world. Every multiplication saturates
 * at {@link Long#MAX_VALUE}, so a hostile transform cannot wrap into a small value.
 */
public final class WorkspaceGeometryCost {
   private WorkspaceGeometryCost() {
   }

   /**
    * Cost model for one repetition of one part.
    *
    * @param sourceCount exact occupied voxels in the source
    * @param scanVolume cells the nearest-neighbour sampler visits, or 0 when no scan runs
    * @param writtenUpperBound upper bound on voxels written for one repetition. A positive bound
    *       permits output, but it does not promise output: a sparse downscale can sample only
    *       empty cells and write nothing.
    * @param repeatCells number of repetition cells
    * @param projectedUpperBound {@code writtenUpperBound * repeatCells}, saturated
    */
   public record Cost(
      long sourceCount,
      long scanVolume,
      long writtenUpperBound,
      long repeatCells,
      long projectedUpperBound
   ) {
      /**
       * Whether this cost can produce voxels at all.
       *
       * <p>This is a potential, not a promise. Every number in this record is an upper bound, so
       * a true result still permits an empty result, for example a sparse downscale whose every
       * sample lands on an empty source cell.
       */
      public boolean canProduceVoxels() {
         return this.writtenUpperBound > 0L && this.repeatCells > 0L;
      }
   }

   /** Returns the cost of one part, or {@code null} when the source or transform is absent. */
   public static Cost of(Collection<BlockPos> source, WorkspaceTransform transform) {
      if (source == null || source.isEmpty() || transform == null) {
         return null;
      }
      OccupiedBlockBounds bounds = OccupiedBlockBounds.from(source).orElse(null);
      if (bounds == null) {
         return null;
      }
      long sourceCount = source.size();
      long repeatCells = Math.max(0L, transform.repeats().cellCount());
      long scanVolume = scanVolume(bounds, transform);
      // No scan means the composer returns the source map unchanged, so the write count is
      // exact. A scan can duplicate voxels, so only the visited cell count bounds the writes.
      long writtenUpperBound = scanVolume == 0L ? sourceCount : scanVolume;
      return new Cost(
         sourceCount,
         scanVolume,
         writtenUpperBound,
         repeatCells,
         saturatingMultiply(writtenUpperBound, repeatCells)
      );
   }

   /** Returns the sampler cell count, or 0 when scaling cannot change an occupied width. */
   private static long scanVolume(OccupiedBlockBounds bounds, WorkspaceTransform transform) {
      long x = scaledWidth(bounds.width(AxisGizmo.Axis.X), transform.scale().x);
      long y = scaledWidth(bounds.width(AxisGizmo.Axis.Y), transform.scale().y);
      long z = scaledWidth(bounds.width(AxisGizmo.Axis.Z), transform.scale().z);
      boolean unchanged = x == bounds.width(AxisGizmo.Axis.X)
         && y == bounds.width(AxisGizmo.Axis.Y)
         && z == bounds.width(AxisGizmo.Axis.Z);
      return unchanged ? 0L : saturatingMultiply(saturatingMultiply(x, y), z);
   }

   private static long scaledWidth(int width, double scale) {
      if (!Double.isFinite(scale) || scale <= 0.0) {
         return Long.MAX_VALUE;
      }
      // WorkspacePreviewComposer.scaleValues rounds half up, so this must use Math.round.
      double value = Math.max(1.0, Math.round(width * scale));
      return value >= (double)Long.MAX_VALUE ? Long.MAX_VALUE : (long)value;
   }

   private static long saturatingMultiply(long left, long right) {
      if (left <= 0L || right <= 0L) {
         return 0L;
      }
      return right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
   }
}
