package io.github.fastformer.fastplace.geometry.generation;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Uses one plane phase across the face. Discrete edge errors stay at the perimeter. */
final class NormalPlaneFaceRasterizer {
   private NormalPlaneFaceRasterizer() {
   }

   static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      Set<BoundaryInterpolatedFaceRasterizer.Pixel> columns,
      Set<BoundaryInterpolatedFaceRasterizer.Pixel> boundary,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
      for (BoundaryInterpolatedFaceRasterizer.Pixel column : columns) {
         observer.checkCancelled();
         observer.onScanned(1L);
         blocks.add(frame.restore(column.u(), column.v()));
         if (blocks.size() > maxBlocks) {
            return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }
      }
      LinkedHashSet<BlockPos> outline = new LinkedHashSet<>();
      for (BoundaryInterpolatedFaceRasterizer.Pixel edge : boundary) {
         observer.checkCancelled();
         outline.add(frame.restore(edge.u(), edge.v()));
      }
      return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
         Collections.unmodifiableSet(blocks),
         Collections.unmodifiableSet(outline)
      ));
   }
}
