package io.github.fastformer.fastplace.placement.plan;

import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import java.util.List;
import net.minecraft.core.BlockPos;

/** World-independent geometry shared by client submission and server placement generation. */
public record PlacementGeometryPlan(List<BlockPos> points, FastPlaceGeometry.Modes modes,
   boolean polygonHeightConfirmed, PolygonVolumeShape polygonVolumeShape, int maxPlacement,
   ResolvedPlacementEffect effect) {
   public PlacementGeometryPlan {
      points = points.stream().map(BlockPos::immutable).toList();
      if (modes == null || polygonVolumeShape == null || maxPlacement < 1 || maxPlacement == Integer.MAX_VALUE) {
         throw new IllegalArgumentException("Placement geometry requires modes, shape and a bounded limit");
      }
   }

   public long estimatedTargetBlocks() {
      long base = PlacementBlockEstimate.upperBound(points, modes, polygonHeightConfirmed, polygonVolumeShape, maxPlacement);
      return effect == null ? base : effect.estimateTargetBlocks(base);
   }

   public long additionalGeneratedBlockSets() { return effect != null && effect.transformsTargets() ? 1 : 0; }

   public BlockGenerationResult generate(BlockGenerationObserver observer) {
      observer.checkCancelled();
      var base = FastPlaceGeometry.blocksResult(points, modes, polygonHeightConfirmed,
         polygonVolumeShape, maxPlacement + 1, observer);
      observer.checkCancelled();
      var result = effect == null ? base : effect.applyToTargets(base);
      observer.checkCancelled();
      return result;
   }
}
