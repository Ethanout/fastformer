package io.github.fastformer.client.session.preview;

import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import java.util.Optional;

import io.github.fastformer.common.mode.SessionActivity;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import java.util.Objects;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterRules;

/** Owns versioned server snapshots and reconnect confirmation for the client session. */
public final class ClientPreviewState {
   private BuildingPreviewSnapshot building = BuildingPreviewSnapshot.inactive();
   private BuildingPreviewSessionPayload buildingSession = new BuildingPreviewSessionPayload(building.session());
   private BuildingPreviewParametersPayload buildingParameters = new BuildingPreviewParametersPayload(building.parameters());
   private BuildingPreviewEffectPayload buildingEffect = new BuildingPreviewEffectPayload(building.activePlacementEffect());
   private OperationPreviewPayload operation = OperationPreviewPayload.inactive();
   private GeometryPreviewPayload geometry = GeometryPreviewPayload.inactive();
   private SessionActivity activity = SessionActivity.NONE;
   private long revision;
   private long buildingVersion;
   private long geometryVersion;
   private long geometryTransformVersion;
   private long buildingSessionRevision;
   private long buildingParametersRevision;
   private long buildingEffectRevision;
   private QuickShapeSubmissionSnapshot buildingSubmission;
   private io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload submissionParameters;
   private long geometryRevision = -1L;
   private long activityRevision = -1L;
   private final io.github.fastformer.client.session.reconnect.ReconnectSnapshotWindow reconnectBoundary =
      new io.github.fastformer.client.session.reconnect.ReconnectSnapshotWindow();
   private boolean reconnectRestorePending;
   private long reconnectRestoreRevision = Long.MIN_VALUE;
   private BuildingPreviewSessionPayload heldReconnectBuildingSession;
   private BuildingPreviewParametersPayload heldReconnectBuildingParameters;
   private BuildingPreviewEffectPayload heldReconnectBuildingEffect;
   private GeometryPreviewPayload heldReconnectGeometry;
   private FaceRasterPolicySet facePolicies = FaceRasterPolicySet.DEFAULT;
   private Object facePolicyRegistry;

   /** Captures a synchronized registry once, outside worker threads and rendering. */
   public void observeFacePolicies(net.minecraft.core.RegistryAccess registries) {
      Object registry = registries.registry(FaceRasterRules.REGISTRY).orElse(null);
      if (registry == facePolicyRegistry) return;
      facePolicyRegistry = registry;
      applyFacePolicies(FaceRasterRules.capture(registries));
   }

   public void applyFacePolicies(FaceRasterPolicySet policies) {
      policies = Objects.requireNonNull(policies, "policies");
      if (facePolicies.equals(policies)) return;
      facePolicies = policies;
      invalidateBuilding();
      rebuildBuilding();
   }

   private void invalidateBuilding() {
      building = BuildingPreviewSnapshot.inactive();
      buildingSubmission = null;
      revision++;
      buildingVersion++;
   }

   public BuildingPreviewSnapshot building() {
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

   public Optional<BuildingPreviewSessionPayload> idleBuildingSession() {
      return !buildingSession.value().active() && buildingSessionRevision > 0
         && buildingParameters.value().facePolicyHash().equals(facePolicies.hash())
         && !buildingSession.callbackScope().equals(io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped())
         && buildingSessionRevision == buildingParametersRevision
         && buildingSessionRevision == buildingEffectRevision
         && buildingSession.callbackScope().equals(buildingParameters.callbackScope())
         && buildingSession.callbackScope().equals(buildingEffect.callbackScope())
         ? Optional.of(buildingSession) : Optional.empty();
   }

   public net.minecraft.resources.ResourceLocation buildingProfile() {
      return building.active() && buildingSessionRevision == buildingParametersRevision
         && buildingSessionRevision == buildingEffectRevision ? buildingSession.value().profile()
         : io.github.fastformer.common.interaction.binding.SessionProfiles.NONE;
   }

   public void applyQuickShapeSubmissionParameters(
      io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload payload
   ) {
      if (submissionParameters == null || payload.revision() >= submissionParameters.revision()) {
         submissionParameters = payload;
      }
   }

   public Optional<io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload> submissionParameters(
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

   public SessionActivity activity() {
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

   /** Changes at every accepted break in transform continuity, even between drawn frames. */
   public long geometryTransformVersion() { return geometryTransformVersion; }

   public void applyBuilding(BuildingPreviewSnapshot payload) {
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
      facePolicyRegistry = null;
      facePolicies = FaceRasterPolicySet.DEFAULT;
      submissionParameters = null;
      if (building.active() || geometry.active() || reconnectRestorePending) {
         beginReconnectRestore();
      }
      buildingSessionRevision = 0L;
      buildingParametersRevision = 0L;
      buildingEffectRevision = 0L;
      geometryRevision = -1L;
      activityRevision = -1L;
      applyBuilding(BuildingPreviewSnapshot.inactive());
      applyOperation(OperationPreviewPayload.inactive());
      applyGeometry(GeometryPreviewPayload.inactive());
      applyActivity(new ActivityStatePayload(SessionActivity.NONE));
      // A new connection or level cannot answer the question of the old one.
      clearPendingReconnectRestore();
   }

   /**
    * Marks the connection boundary that may restore a server preview session.
    * The replayed active snapshot stays hidden until the player confirms it.
    */
   public void beginReconnectRestore() {
      reconnectBoundary.arm();
      reconnectRestorePending = false;
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
      reconnectBoundary.tick();
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
      if (!reconnectBoundary.armed()) {
         return false;
      }
      reconnectBoundary.observeSnapshot();
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
      if (!reconnectBoundary.armed()) {
         return false;
      }
      reconnectBoundary.observeSnapshot();
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
      reconnectBoundary.clear();
      reconnectRestorePending = false;
      reconnectRestoreRevision = Long.MIN_VALUE;
      clearHeldReconnectPreviews();
   }

   /**
    * Drops the held previews and their confirmation question. The boundary stays
    * armed, so the new level can ask again with its own snapshot.
    */
   private void clearPendingReconnectRestore() {
      reconnectRestorePending = false;
      reconnectBoundary.restartWait();
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
      if (!payload.value().facePolicyHash().equals(building.facePolicies().hash())) invalidateBuilding();
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
      if (!parameterValue.facePolicyHash().equals(facePolicies.hash())) {
         invalidateBuilding();
         return false;
      }
      building = BuildingPreviewSnapshot.combine(sessionValue, parameterValue, effectValue, facePolicies);
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
      if (!GeometryPreviewRules.continuesTransform(geometry, payload)) geometryTransformVersion++;
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
