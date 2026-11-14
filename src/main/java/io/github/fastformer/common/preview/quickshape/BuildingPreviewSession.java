package io.github.fastformer.common.preview.quickshape;

import io.github.fastformer.common.placement.PlacementContextSnapshot;
import io.github.fastformer.common.quickshape.mode.PolygonVolumeShape;
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
   PlacementContextSnapshot placementContext,
   net.minecraft.resources.ResourceLocation profile
) {
   public BuildingPreviewSession(boolean enabled, boolean middleConfirmEnabled, boolean active, boolean ctrlHeld,
      boolean polygonClosed, boolean polygonHeightConfirmed, PolygonVolumeShape polygonVolumeShape,
      List<BlockPos> points, BlockPos freeScrollOffset, PlacementContextSnapshot placementContext) {
      this(enabled, middleConfirmEnabled, active, ctrlHeld, polygonClosed, polygonHeightConfirmed, polygonVolumeShape,
         points, freeScrollOffset, placementContext, io.github.fastformer.common.interaction.binding.SessionProfiles.NONE);
   }

   public BuildingPreviewSession {
      java.util.Objects.requireNonNull(profile);
      polygonVolumeShape = polygonVolumeShape == null ? PolygonVolumeShape.EXTRUDE : polygonVolumeShape;
      points = points == null ? List.of() : points.stream().map(BlockPos::immutable).toList();
      freeScrollOffset = freeScrollOffset == null ? BlockPos.ZERO : freeScrollOffset.immutable();
   }

   public BuildingPreviewSession withProfile(net.minecraft.resources.ResourceLocation id, boolean middleConfirm) {
      return new BuildingPreviewSession(enabled, middleConfirm, active, ctrlHeld, polygonClosed, polygonHeightConfirmed,
         polygonVolumeShape, points, freeScrollOffset, placementContext, id);
   }
}
