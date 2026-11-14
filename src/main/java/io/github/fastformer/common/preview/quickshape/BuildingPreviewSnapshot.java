package io.github.fastformer.common.preview.quickshape;

import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.PointMode;
import io.github.fastformer.common.quickshape.mode.PolygonVolumeShape;
import io.github.fastformer.common.placement.PlacementContextSnapshot;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Immutable combined preview value; only its split protocol messages are sent on the wire. */
public record BuildingPreviewSnapshot(
   boolean enabled,
   boolean middleConfirmEnabled,
   boolean active,
   boolean ctrlHeld,
   boolean polygonClosed,
   boolean polygonHeightConfirmed,
   PolygonVolumeShape polygonVolumeShape,
   List<BlockPos> points,
   int angleDistance,
   BlockPos freeScrollOffset,
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
   PlacementContextSnapshot placementContext,
   ResourceLocation activePlacementEffect,
   FaceRasterPolicySet facePolicies
) {
   public BuildingPreviewSnapshot {
      java.util.Objects.requireNonNull(facePolicies, "facePolicies");
      polygonVolumeShape = polygonVolumeShape == null ? PolygonVolumeShape.EXTRUDE : polygonVolumeShape;
      faceTieBias = faceTieBias == null ? LineTieBias.DEFAULT : faceTieBias;
      points = points.stream().map(BlockPos::immutable).toList();
      freeScrollOffset = freeScrollOffset == null ? BlockPos.ZERO : freeScrollOffset.immutable();
      perpendicularAnchor = perpendicularAnchor == null ? BlockPos.ZERO : perpendicularAnchor.immutable();
      faceBaseOffset = GeometryNumbers.finiteOrZero(faceBaseOffset);
      volumeBaseOffset = GeometryNumbers.finiteOrZero(volumeBaseOffset);
      angleDegrees = GeometryNumbers.finiteOr(angleDegrees, 0.0);
   }

   public BuildingPreviewSnapshot(
      boolean enabled, boolean middleConfirmEnabled, boolean active, boolean ctrlHeld,
      boolean polygonClosed, boolean polygonHeightConfirmed, PolygonVolumeShape polygonVolumeShape,
      List<BlockPos> points, int angleDistance, BlockPos freeScrollOffset, Vec3 faceBaseOffset,
      Vec3 volumeBaseOffset, BlockPos perpendicularAnchor, double angleDegrees, PointMode pointMode,
      RaycastPlacement raycastPlacement, LineMode lineMode, FaceMode faceMode, VolumeMode volumeMode,
      FillMode fillMode, LineTieBias faceTieBias, PlacementContextSnapshot placementContext, ResourceLocation activePlacementEffect
   ) {
      this(enabled, middleConfirmEnabled, active, ctrlHeld, polygonClosed, polygonHeightConfirmed, polygonVolumeShape,
         points, angleDistance, freeScrollOffset, faceBaseOffset, volumeBaseOffset, perpendicularAnchor, angleDegrees,
         pointMode, raycastPlacement, lineMode, faceMode, volumeMode, fillMode, faceTieBias, placementContext,
         activePlacementEffect, FaceRasterPolicySet.DEFAULT);
   }

   public BuildingPreviewSnapshot(
      boolean enabled, boolean middleConfirmEnabled, boolean active, boolean ctrlHeld,
      boolean polygonClosed, boolean polygonHeightConfirmed, PolygonVolumeShape polygonVolumeShape,
      List<BlockPos> points, int angleDistance, BlockPos freeScrollOffset, Vec3 faceBaseOffset,
      Vec3 volumeBaseOffset, BlockPos perpendicularAnchor, double angleDegrees, PointMode pointMode,
      RaycastPlacement raycastPlacement, LineMode lineMode, FaceMode faceMode, VolumeMode volumeMode,
      FillMode fillMode, LineTieBias faceTieBias, PlacementContextSnapshot placementContext
   ) {
      this(enabled, middleConfirmEnabled, active, ctrlHeld, polygonClosed, polygonHeightConfirmed,
         polygonVolumeShape, points, angleDistance, freeScrollOffset, faceBaseOffset, volumeBaseOffset,
         perpendicularAnchor, angleDegrees, pointMode, raycastPlacement, lineMode, faceMode, volumeMode,
         fillMode, faceTieBias, placementContext, null);
   }

   public static BuildingPreviewSnapshot inactive() {
      return new BuildingPreviewSnapshot(
         true, true, false, false, false, false, PolygonVolumeShape.EXTRUDE, List.of(), 5,
         BlockPos.ZERO, Vec3.ZERO, Vec3.ZERO, BlockPos.ZERO, 0.0, PointMode.RAYCAST,
         RaycastPlacement.SURFACE, LineMode.AXIS, FaceMode.POLYGON, VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.OUTLINE, LineTieBias.DEFAULT, null, null
      );
   }

   /** Recombines one revision after the recipient has validated all three protocol messages. */
   public static BuildingPreviewSnapshot combine(BuildingPreviewSession session, BuildingPreviewParameters parameters,
      BuildingPreviewEffectSnapshot effect) {
      return combine(session, parameters, effect, FaceRasterPolicySet.DEFAULT);
   }

   public static BuildingPreviewSnapshot combine(BuildingPreviewSession session, BuildingPreviewParameters parameters,
      BuildingPreviewEffectSnapshot effect, FaceRasterPolicySet policies) {
      if (!parameters.facePolicyHash().equals(policies.hash())) {
         throw new IllegalArgumentException("Preview parameters and captured face policies differ");
      }
      return new BuildingPreviewSnapshot(session.enabled(), session.middleConfirmEnabled(), session.active(),
         session.ctrlHeld(), session.polygonClosed(), session.polygonHeightConfirmed(), session.polygonVolumeShape(),
         session.points(), parameters.angleDistance(), session.freeScrollOffset(), parameters.faceBaseOffset(),
         parameters.volumeBaseOffset(), parameters.perpendicularAnchor(), parameters.angleDegrees(), parameters.pointMode(),
         parameters.raycastPlacement(), parameters.lineMode(), parameters.faceMode(), parameters.volumeMode(),
         parameters.fillMode(), parameters.faceTieBias(), session.placementContext(), effect.activeEffect(), policies);
   }

   public BuildingPreviewSession session() {
      return new BuildingPreviewSession(enabled, middleConfirmEnabled, active, ctrlHeld, polygonClosed,
         polygonHeightConfirmed, polygonVolumeShape, points, freeScrollOffset, placementContext);
   }

   public BuildingPreviewParameters parameters() {
      return new BuildingPreviewParameters(angleDistance, faceBaseOffset, volumeBaseOffset, perpendicularAnchor,
         angleDegrees, pointMode, raycastPlacement, lineMode, faceMode, volumeMode, fillMode, faceTieBias, facePolicies.hash());
   }

   public BuildingPreviewEffectSnapshot effect() {
      return new BuildingPreviewEffectSnapshot(activePlacementEffect);
   }

   public FastPlaceGeometry.Modes modes() {
      VolumeMode effectiveVolumeMode = polygonClosed ? VolumeMode.PERPENDICULAR_TO_FACE : volumeMode;
      return new FastPlaceGeometry.Modes(pointMode, raycastPlacement, lineMode, faceMode,
         effectiveVolumeMode, fillMode, angleDegrees, ctrlHeld, faceTieBias);
   }
}
