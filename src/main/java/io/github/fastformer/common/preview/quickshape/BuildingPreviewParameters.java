package io.github.fastformer.common.preview.quickshape;

import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.geometry.FillMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Geometry and interaction parameters used to reproduce a building preview. */
public record BuildingPreviewParameters(
   int angleDistance,
   Vec3 faceBaseOffset,
   Vec3 volumeBaseOffset,
   BlockPos perpendicularAnchor,
   double angleDegrees,
   PointMode pointMode,
   RaycastPlacement raycastPlacement,
   LineMode lineMode,
   FaceMode faceMode,
   VolumeMode volumeMode,
   FillMode fillMode,
   LineTieBias faceTieBias
) {
   public BuildingPreviewParameters withLineMode(LineMode effectiveMode) {
      return new BuildingPreviewParameters(angleDistance, faceBaseOffset, volumeBaseOffset, perpendicularAnchor, angleDegrees,
         pointMode, raycastPlacement, effectiveMode, faceMode, volumeMode, fillMode, faceTieBias);
   }

   public BuildingPreviewParameters {
      faceBaseOffset = GeometryNumbers.finiteOrZero(faceBaseOffset);
      volumeBaseOffset = GeometryNumbers.finiteOrZero(volumeBaseOffset);
      perpendicularAnchor = perpendicularAnchor == null ? BlockPos.ZERO : perpendicularAnchor.immutable();
      angleDegrees = GeometryNumbers.finiteOr(angleDegrees, 0.0);
      faceTieBias = faceTieBias == null ? LineTieBias.DEFAULT : faceTieBias;
   }
}
