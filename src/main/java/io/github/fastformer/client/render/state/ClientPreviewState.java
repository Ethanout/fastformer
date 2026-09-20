package io.github.fastformer.client.render.state;

import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.Optional;

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
   private QuickShapeSubmissionSnapshot buildingSubmission;
   private io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload submissionParameters;
   private long geometryRevision = -1L;
   private long activityRevision = -1L;
   /** Ticks an unanswered connection boundary waits before it is dropped. */
   private static final int RECONNECT_BOUNDARY_IDLE_TICKS = 40;
   private boolean reconnectRestoreArmed;
   private boolean reconnectRestorePending;
   private boolean reconnectBoundarySawSnapshot;
   private int reconnectBoundaryIdleTicks;
   private long reconnectRestoreRevision = Long.MIN_VALUE;
   private BuildingPreviewSessionPayload heldReconnectBuildingSession;
   private BuildingPreviewParametersPayload heldReconnectBuildingParameters;
   private BuildingPreviewEffectPayload heldReconnectBuildingEffect;
   private GeometryPreviewPayload heldReconnectGeometry;

   public BuildingPreviewPayload building() {
      return building;
   }

   /** A partial network update cannot supply submission data from the old display. */
   public Optional<QuickShapeSubmissionSnapshot> buildingSubmission() {
      if (buildingSubmission == null || buildingSessionRevision != buildingSubmission.revision()
         || buildingParametersRevision != buildingSubmission.revision()
         || buildingEffectRevision != buildingSubmission.revision()) {
         return Optional.empty();
      }
      return Optional.of(buildingSubmission);
   }

   public void applyQuickShapeSubmissionParameters(
      io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload payload
   ) {
      if (submissionParameters == null || payload.revision() >= submissionParameters.revision()) {
         submissionParameters = payload;
      }
   }

   public Optional<io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload> submissionParameters(
      QuickShapeSubmissionSnapshot snapshot
   ) {
      return submissionParameters != null && submissionParameters.revision() == snapshot.revision()
         && submissionParameters.callbackScope().equals(snapshot.scope()) ? Optional.of(submissionParameters) : Optional.empty();
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
      buildingSubmission = null;
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

   /**
    * Ends the previews of one connection or level. A preview that was visible, and a
    * question that still waits for the player, both arm the confirm gate again, so the
    * next replayed snapshot never becomes visible on its own.
    */
   public void resetConnection() {
      submissionParameters = null;
      if (building.active() || geometry.active() || reconnectRestorePending) {
         beginReconnectRestore();
      }
      buildingSessionRevision = 0L;
      buildingParametersRevision = 0L;
      buildingEffectRevision = 0L;
      geometryRevision = -1L;
      activityRevision = -1L;
      applyBuilding(BuildingPreviewPayload.inactive());
      applyOperation(OperationPreviewPayload.inactive());
      applyGeometry(GeometryPreviewPayload.inactive());
      applyActivity(new ActivityStatePayload(FastPlaceActivity.NONE));
      // A new connection or level cannot answer the question of the old one.
      clearPendingReconnectRestore();
   }

   /**
    * Marks the connection boundary that may restore a server preview session.
    * The replayed active snapshot stays hidden until the player confirms it.
    */
   public void beginReconnectRestore() {
      reconnectRestoreArmed = true;
      reconnectRestorePending = false;
      reconnectBoundarySawSnapshot = false;
      reconnectBoundaryIdleTicks = 0;
      reconnectRestoreRevision = Long.MIN_VALUE;
      clearHeldReconnectPreviews();
   }

   public boolean reconnectRestorePending() {
      return reconnectRestorePending;
   }

   /**
    * Ends the boundary once the snapshot round that followed it has been received.
    * A snapshot already waiting for the player's answer stays held.
    */
   public void tickReconnectBoundary() {
      if (!reconnectRestoreArmed) {
         return;
      }
      if (reconnectBoundarySawSnapshot) {
         reconnectRestoreArmed = false;
         reconnectBoundarySawSnapshot = false;
         reconnectBoundaryIdleTicks = 0;
         return;
      }
      if (++reconnectBoundaryIdleTicks >= RECONNECT_BOUNDARY_IDLE_TICKS) {
         reconnectRestoreArmed = false;
         reconnectBoundaryIdleTicks = 0;
      }
   }

   /** Returns true when a replayed preview part must wait for the player's confirmation. */
   public boolean holdReconnectBuildingSession(BuildingPreviewSessionPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (reconnectRestorePending) {
         if (payload.revision() != reconnectRestoreRevision) {
            dismissReconnectRestore();
            return false;
         }
         heldReconnectBuildingSession = payload;
         return true;
      }
      if (!reconnectRestoreArmed) {
         return false;
      }
      reconnectBoundarySawSnapshot = true;
      if (!payload.value().active()) {
         return false;
      }
      reconnectRestorePending = true;
      reconnectRestoreRevision = payload.revision();
      heldReconnectBuildingSession = payload;
      return true;
   }

   public boolean holdReconnectBuildingParameters(BuildingPreviewParametersPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (!reconnectRestorePending) {
         return false;
      }
      if (payload.revision() != reconnectRestoreRevision) {
         dismissReconnectRestore();
         return false;
      }
      heldReconnectBuildingParameters = payload;
      return true;
   }

   public boolean holdReconnectBuildingEffect(BuildingPreviewEffectPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (!reconnectRestorePending) {
         return false;
      }
      if (payload.revision() != reconnectRestoreRevision) {
         dismissReconnectRestore();
         return false;
      }
      heldReconnectBuildingEffect = payload;
      return true;
   }

   public boolean holdReconnectGeometry(GeometryPreviewPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (reconnectRestorePending) {
         if (heldReconnectGeometry != null) {
            dismissReconnectRestore();
            return false;
         }
         heldReconnectGeometry = payload;
         return true;
      }
      if (!reconnectRestoreArmed) {
         return false;
      }
      reconnectBoundarySawSnapshot = true;
      if (!payload.active()) {
         return false;
      }
      reconnectRestorePending = true;
      heldReconnectGeometry = payload;
      return true;
   }

   /** Hands the held reconnect previews to the caller and ends the pending restore. */
   public HeldReconnectPreviews takeHeldReconnectPreviews() {
      if (!reconnectRestorePending) {
         return null;
      }
      HeldReconnectPreviews held = new HeldReconnectPreviews(
         heldReconnectBuildingSession,
         heldReconnectBuildingParameters,
         heldReconnectBuildingEffect,
         heldReconnectGeometry
      );
      dismissReconnectRestore();
      return held;
   }

   public void dismissReconnectRestore() {
      reconnectRestoreArmed = false;
      reconnectRestorePending = false;
      reconnectBoundarySawSnapshot = false;
      reconnectBoundaryIdleTicks = 0;
      reconnectRestoreRevision = Long.MIN_VALUE;
      clearHeldReconnectPreviews();
   }

   /**
    * Drops the held previews and their confirmation question. The boundary stays
    * armed, so the new level can ask again with its own snapshot.
    */
   private void clearPendingReconnectRestore() {
      reconnectRestorePending = false;
      reconnectBoundarySawSnapshot = false;
      reconnectBoundaryIdleTicks = 0;
      reconnectRestoreRevision = Long.MIN_VALUE;
      clearHeldReconnectPreviews();
   }

   private void clearHeldReconnectPreviews() {
      heldReconnectBuildingSession = null;
      heldReconnectBuildingParameters = null;
      heldReconnectBuildingEffect = null;
      heldReconnectGeometry = null;
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
      var scope = buildingSession.callbackScope();
      buildingSubmission = building.active() && buildingSessionRevision > 0
         && !scope.equals(OperationCallbackScope.unscoped())
         && scope.equals(buildingParameters.callbackScope()) && scope.equals(buildingEffect.callbackScope())
         ? new QuickShapeSubmissionSnapshot(
            buildingSessionRevision, scope, building
         ) : null;
      revision++;
      buildingVersion++;
      return true;
   }

   public void applyOperation(OperationPreviewPayload payload) {
      operation = Objects.requireNonNull(payload, "payload");
      revision++;
   }

   public boolean applyGeometry(GeometryPreviewPayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (payload.revision() <= geometryRevision) {
         return false;
      }
      geometry = payload;
      geometryRevision = payload.revision();
      revision++;
      geometryVersion++;
      return true;
   }

   public boolean applyActivity(ActivityStatePayload payload) {
      payload = Objects.requireNonNull(payload, "payload");
      if (payload.revision() <= activityRevision) {
         return false;
      }
      activity = payload.activity();
      activityRevision = payload.revision();
      revision++;
      return true;
   }

   /** Server preview parts replayed at a reconnect boundary, in publication order. */
   public record HeldReconnectPreviews(
      BuildingPreviewSessionPayload buildingSession,
      BuildingPreviewParametersPayload buildingParameters,
      BuildingPreviewEffectPayload buildingEffect,
      GeometryPreviewPayload geometry
   ) {
   }
}
