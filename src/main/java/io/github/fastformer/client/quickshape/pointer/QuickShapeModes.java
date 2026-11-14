package io.github.fastformer.client.quickshape.pointer;

import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.PointMode;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;

/** Shared effective modes for pointing, generation and placement previews. */
public final class QuickShapeModes {
   private QuickShapeModes() {}

   public static QuickShapeStage stage(BuildingPreviewSnapshot snapshot) {
      return QuickShapeStage.resolve(snapshot.points().size(), snapshot.faceMode(), snapshot.polygonClosed());
   }

   public static FastPlaceGeometry.Modes effective(BuildingPreviewSnapshot snapshot, boolean modifier) {
      var modes = snapshot.modes().withModifierHeld(modifier);
      var stage = stage(snapshot);
      if (snapshot.faceMode() != FaceMode.POLYGON && modifier
         && (stage == QuickShapeStage.FACE || stage == QuickShapeStage.VOLUME)) modes = modes.withFaceTieBias(LineTieBias.OPPOSITE);
      boolean first = stage == QuickShapeStage.POINT && snapshot.pointMode() == PointMode.RAYCAST && snapshot.points().isEmpty();
      if (first || stage == QuickShapeStage.LINE && snapshot.lineMode() == LineMode.RAYCAST)
         return modes.withRaycastPlacement(modifier ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE);
      return modes;
   }

   public static boolean raycastSubmode(BuildingPreviewSnapshot snapshot) {
      return switch (stage(snapshot)) {
         case POINT -> snapshot.pointMode() == PointMode.RAYCAST;
         case LINE -> snapshot.lineMode() == LineMode.RAYCAST;
         default -> false;
      };
   }

   public static boolean angleDistance(BuildingPreviewSnapshot snapshot) {
      return snapshot.enabled() && snapshot.active() && QuickShapeStage.fromPointCount(snapshot.points().size()) == QuickShapeStage.LINE
         && snapshot.lineMode() == LineMode.FREE_SCROLL;
   }

   public static boolean scrollContext(BuildingPreviewSnapshot snapshot) {
      if (!snapshot.active()) return false;
      var stage = stage(snapshot);
      return angleDistance(snapshot) || stage == QuickShapeStage.FACE && snapshot.faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE
         || stage == QuickShapeStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(snapshot.modes());
   }
}
