package io.github.fastformer.server.sync;

import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.quickshape.mode.PolygonVolumeShape;
import io.github.fastformer.common.quickshape.QuickShapeDraft;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Converts authoritative server drafts and settings into transport-independent preview values. */
final class QuickShapePreviews {
   private QuickShapePreviews() {}

   static BuildingPreviewSnapshot active(QuickShapeDraft draft, FastPlaceSettings settings,
      FastPlaceGeometry.Modes modes, ResourceLocation effect) {
      return new BuildingPreviewSnapshot(true, settings.middleConfirmEnabled(), true, draft.modifierHeld(),
         draft.polygonClosed(), draft.polygonHeightConfirmed(), draft.polygonVolumeShape(), draft.points(), 0,
         draft.freeScrollOffset(), draft.faceBaseOffset(), draft.volumeBaseOffset(), draft.perpendicularAnchor(),
         settings.angleDegrees(), settings.pointMode(), modes.raycastPlacement(), settings.lineMode(),
         settings.faceMode(), settings.volumeMode(), settings.fillMode(), draft.faceTieBias(),
         draft.placementContext(), effect);
   }

   static BuildingPreviewSnapshot inactive(FastPlaceSettings settings) {
      return new BuildingPreviewSnapshot(settings.enabled(), settings.middleConfirmEnabled(), false, false,
         false, false, PolygonVolumeShape.EXTRUDE, List.of(), 5, BlockPos.ZERO, Vec3.ZERO, Vec3.ZERO,
         BlockPos.ZERO, settings.angleDegrees(), settings.pointMode(), RaycastPlacement.SURFACE,
         settings.lineMode(), settings.faceMode(), settings.volumeMode(), settings.fillMode(),
         LineTieBias.DEFAULT, null, null);
   }
}
