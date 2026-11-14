package io.github.fastformer.common.preview.quickshape;

import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.PointMode;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet;
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
   LineTieBias faceTieBias,
   String facePolicyHash
) {
   public BuildingPreviewParameters(int angleDistance, Vec3 faceBaseOffset, Vec3 volumeBaseOffset,
      BlockPos perpendicularAnchor, double angleDegrees, PointMode pointMode, RaycastPlacement raycastPlacement,
      LineMode lineMode, FaceMode faceMode, VolumeMode volumeMode, FillMode fillMode, LineTieBias faceTieBias) {
      this(angleDistance, faceBaseOffset, volumeBaseOffset, perpendicularAnchor, angleDegrees, pointMode, raycastPlacement,
         lineMode, faceMode, volumeMode, fillMode, faceTieBias, FaceRasterPolicySet.DEFAULT.hash());
   }

   public BuildingPreviewParameters withLineMode(LineMode effectiveMode) {
      return new BuildingPreviewParameters(angleDistance, faceBaseOffset, volumeBaseOffset, perpendicularAnchor, angleDegrees,
         pointMode, raycastPlacement, effectiveMode, faceMode, volumeMode, fillMode, faceTieBias, facePolicyHash);
   }

   public BuildingPreviewParameters withFacePolicyHash(String hash) {
      return new BuildingPreviewParameters(angleDistance, faceBaseOffset, volumeBaseOffset, perpendicularAnchor, angleDegrees,
         pointMode, raycastPlacement, lineMode, faceMode, volumeMode, fillMode, faceTieBias, hash);
   }

   public BuildingPreviewParameters {
      if (facePolicyHash == null || !facePolicyHash.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("A face policy hash must contain 64 lowercase hexadecimal characters");
      }
      faceBaseOffset = GeometryNumbers.finiteOrZero(faceBaseOffset);
      volumeBaseOffset = GeometryNumbers.finiteOrZero(volumeBaseOffset);
      perpendicularAnchor = perpendicularAnchor == null ? BlockPos.ZERO : perpendicularAnchor.immutable();
      angleDegrees = GeometryNumbers.finiteOr(angleDegrees, 0.0);
      faceTieBias = faceTieBias == null ? LineTieBias.DEFAULT : faceTieBias;
   }
}
