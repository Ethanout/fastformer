package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Session state required to render the current building interaction. */
public record BuildingPreviewSession(
   boolean enabled,
   boolean middleConfirmEnabled,
   boolean active,
   boolean ctrlHeld,
   boolean polygonClosed,
   boolean polygonHeightConfirmed,
   PolygonVolumeShape polygonVolumeShape,
   List<BlockPos> points,
   BlockPos freeScrollOffset,
   PlacementContextSnapshot placementContext
) {
   public BuildingPreviewSession {
      polygonVolumeShape = polygonVolumeShape == null ? PolygonVolumeShape.EXTRUDE : polygonVolumeShape;
      points = points == null ? List.of() : points.stream().map(BlockPos::immutable).toList();
      freeScrollOffset = freeScrollOffset == null ? BlockPos.ZERO : freeScrollOffset.immutable();
   }
}
