package io.github.fastformer.client.render.state;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
import java.util.Objects;

/** Owns the latest server preview snapshots shown by the client renderer. */
public final class ClientPreviewState {
   private BuildingPreviewPayload building = BuildingPreviewPayload.inactive();
   private BuildingPreviewSessionPayload buildingSession = new BuildingPreviewSessionPayload(building.session());
   private BuildingPreviewParametersPayload buildingParameters = new BuildingPreviewParametersPayload(building.parameters());
   private BuildingPreviewEffectPayload buildingEffect = new BuildingPreviewEffectPayload(building.activePlacementEffect());
   private OperationPreviewPayload operation = OperationPreviewPayload.inactive();
   private GeometryPreviewPayload geometry = GeometryPreviewPayload.inactive();
   private FastPlaceActivity activity = FastPlaceActivity.NONE;
   private long revision;
   private long buildingVersion;
   private long geometryVersion;
   private long buildingSessionRevision;
   private long buildingParametersRevision;
   private long buildingEffectRevision;

   public BuildingPreviewPayload building() {
      return building;
   }

   public OperationPreviewPayload operation() {
      return operation;
   }

   public GeometryPreviewPayload geometry() {
      return geometry;
   }

   public FastPlaceActivity activity() {
      return activity;
   }

   public long revision() {
      return revision;
   }

   public long buildingVersion() {
      return buildingVersion;
   }

   public long geometryVersion() {
      return geometryVersion;
   }

   public void applyBuilding(BuildingPreviewPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      long revisionWatermark = Math.max(
         this.buildingSessionRevision,
         Math.max(this.buildingParametersRevision, this.buildingEffectRevision)
      );
      building = payload;
      buildingSession = new BuildingPreviewSessionPayload(payload.session());
      buildingParameters = new BuildingPreviewParametersPayload(payload.parameters());
      buildingEffect = new BuildingPreviewEffectPayload(payload.activePlacementEffect());
      buildingSessionRevision = revisionWatermark;
      buildingParametersRevision = revisionWatermark;
      buildingEffectRevision = revisionWatermark;
      revision++;
      buildingVersion++;
   }

   public boolean applyBuildingSession(BuildingPreviewSessionPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (payload.revision() < buildingSessionRevision || payload.equals(buildingSession)) {
         return false;
      }
      buildingSession = payload;
      buildingSessionRevision = payload.revision();
      return rebuildBuilding();
   }

   public void resetConnection() {
      buildingSessionRevision = 0L;
      buildingParametersRevision = 0L;
      buildingEffectRevision = 0L;
      applyBuilding(BuildingPreviewPayload.inactive());
      applyOperation(OperationPreviewPayload.inactive());
      applyGeometry(GeometryPreviewPayload.inactive());
      applyActivity(new ActivityStatePayload(FastPlaceActivity.NONE));
   }

   public boolean applyBuildingParameters(BuildingPreviewParametersPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (payload.revision() < buildingParametersRevision || payload.equals(buildingParameters)) {
         return false;
      }
      buildingParameters = payload;
      buildingParametersRevision = payload.revision();
      return rebuildBuilding();
   }

   public boolean applyBuildingEffect(BuildingPreviewEffectPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (payload.revision() < buildingEffectRevision || payload.equals(buildingEffect)) {
         return false;
      }
      buildingEffect = payload;
      buildingEffectRevision = payload.revision();
      return rebuildBuilding();
   }

   private boolean rebuildBuilding() {
      if (buildingSessionRevision != buildingParametersRevision
         || buildingSessionRevision != buildingEffectRevision) {
         return false;
      }
      var sessionValue = buildingSession.value();
      var parameterValue = buildingParameters.value();
      var effectValue = buildingEffect.value();
      building = new BuildingPreviewPayload(
         sessionValue.enabled(),
         sessionValue.middleConfirmEnabled(),
         sessionValue.active(),
         sessionValue.ctrlHeld(),
         sessionValue.polygonClosed(),
         sessionValue.polygonHeightConfirmed(),
         sessionValue.polygonVolumeShape(),
         sessionValue.points(),
         parameterValue.angleDistance(),
         sessionValue.freeScrollOffset(),
         parameterValue.faceBaseOffset(),
         parameterValue.volumeBaseOffset(),
         parameterValue.perpendicularAnchor(),
         parameterValue.angleDegrees(),
         parameterValue.pointMode(),
         parameterValue.raycastPlacement(),
         parameterValue.lineMode(),
         parameterValue.faceMode(),
         parameterValue.volumeMode(),
         parameterValue.fillMode(),
         parameterValue.faceTieBias(),
         parameterValue.faceRasterizationMode(),
         sessionValue.placementContext(),
         effectValue.activeEffect()
      );
      revision++;
      buildingVersion++;
      return true;
   }

   public void applyOperation(OperationPreviewPayload payload) {
      operation = Objects.requireNonNull(payload, "payload");
      revision++;
   }

   public void applyGeometry(GeometryPreviewPayload payload) {
      geometry = Objects.requireNonNull(payload, "payload");
      revision++;
      geometryVersion++;
   }

   public void applyActivity(ActivityStatePayload payload) {
      activity = Objects.requireNonNull(payload, "payload").activity();
      revision++;
   }
}
