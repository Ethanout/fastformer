package io.github.fastformer.client.render.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectSnapshot;
import io.github.fastformer.network.payload.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSession;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientPreviewStateTest {
   @Test
   void invalidatesPreviewOnlyWhenACompleteNewSnapshotIsPublished() {
      ClientPreviewState state = new ClientPreviewState();
      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();
      var session = new BuildingPreviewSessionPayload(9L, inactive.session());
      var parameters = new BuildingPreviewParametersPayload(9L, inactive.parameters());
      var effect = new BuildingPreviewEffectPayload(9L, new BuildingPreviewEffectSnapshot(inactive.activePlacementEffect()));

      assertFalse(state.applyBuildingEffect(effect));
      assertFalse(state.applyBuildingSession(session));
      assertTrue(state.applyBuildingParameters(parameters));
      long version = state.buildingVersion();
      assertFalse(state.applyBuildingSession(session));
      assertFalse(state.applyBuildingParameters(parameters));
      assertFalse(state.applyBuildingEffect(effect));
      assertFalse(state.applyBuildingSession(new BuildingPreviewSessionPayload(8L, inactive.session())));
      assertEquals(version, state.buildingVersion());
   }

   @Test
   void keepsTheLatestSnapshotsWhenAClientWorldIsReplaced() {
      ClientPreviewState state = new ClientPreviewState();
      BuildingPreviewPayload building = BuildingPreviewPayload.inactive();
      OperationPreviewPayload operation = OperationPreviewPayload.inactive(4L);
      GeometryPreviewPayload geometry = GeometryPreviewPayload.inactive();

      state.applyBuilding(building);
      state.applyOperation(operation);
      state.applyGeometry(geometry);
      state.applyActivity(new ActivityStatePayload(FastPlaceActivity.OPERATION_SESSION));

      assertSame(building, state.building());
      assertSame(operation, state.operation());
      assertSame(geometry, state.geometry());
      assertEquals(FastPlaceActivity.OPERATION_SESSION, state.activity());
      assertEquals(4L, state.revision());
      assertEquals(1L, state.buildingVersion());
      assertEquals(1L, state.geometryVersion());
   }

   @Test
   void startsWithInactiveSnapshotsAndZeroVersions() {
      ClientPreviewState state = new ClientPreviewState();

      assertEquals(BuildingPreviewPayload.inactive(), state.building());
      assertEquals(OperationPreviewPayload.inactive(), state.operation());
      assertFalse(state.geometry().active());
      assertEquals(FastPlaceActivity.NONE, state.activity());
      assertEquals(0L, state.revision());
      assertEquals(0L, state.buildingVersion());
      assertEquals(0L, state.geometryVersion());
   }

   @Test
   void ignoresLateGeometryPreviewRevisions() {
      ClientPreviewState state = new ClientPreviewState();
      GeometryPreviewPayload latest = activeGeometry().withRevision(3L);

      assertTrue(state.applyGeometry(latest));
      long stateRevision = state.revision();
      long geometryVersion = state.geometryVersion();

      assertFalse(state.applyGeometry(GeometryPreviewPayload.inactive().withRevision(2L)));
      assertFalse(state.applyGeometry(latest));
      assertSame(latest, state.geometry());
      assertEquals(stateRevision, state.revision());
      assertEquals(geometryVersion, state.geometryVersion());
   }

   @Test
   void ignoresLateActivityRevisions() {
      ClientPreviewState state = new ClientPreviewState();
      ActivityStatePayload latest = new ActivityStatePayload(3L, FastPlaceActivity.GEOMETRY_SESSION);

      assertTrue(state.applyActivity(latest));
      long stateRevision = state.revision();

      assertFalse(state.applyActivity(new ActivityStatePayload(2L, FastPlaceActivity.NONE)));
      assertFalse(state.applyActivity(latest));
      assertEquals(FastPlaceActivity.GEOMETRY_SESSION, state.activity());
      assertEquals(stateRevision, state.revision());
   }

   @Test
   void waitsForAllSplitPreviewPartsBeforePublishingARevision() {
      ClientPreviewState state = new ClientPreviewState();
      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();

      state.applyBuildingSession(new BuildingPreviewSessionPayload(7L, inactive.session()));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(6L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(7L, new BuildingPreviewEffectSnapshot(null)));
      assertEquals(BuildingPreviewPayload.inactive(), state.building());

      state.applyBuildingParameters(new BuildingPreviewParametersPayload(7L, inactive.parameters()));
      assertEquals(inactive, state.building());
      assertEquals(1L, state.buildingVersion());
   }

   @Test
   void ignoresLateSplitPreviewParts() {
      ClientPreviewState state = new ClientPreviewState();
      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();

      state.applyBuildingSession(new BuildingPreviewSessionPayload(3L, inactive.session()));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(3L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(3L, new BuildingPreviewEffectSnapshot(null)));
      long revision = state.revision();

      state.applyBuildingSession(new BuildingPreviewSessionPayload(2L, inactive.session()));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(2L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(2L, new BuildingPreviewEffectSnapshot(null)));

      assertEquals(revision, state.revision());
      assertEquals(1L, state.buildingVersion());
   }

   @Test
   void fullSnapshotDoesNotLowerSplitRevisionWatermark() {
      ClientPreviewState state = new ClientPreviewState();
      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();
      state.applyBuildingSession(new BuildingPreviewSessionPayload(8L, inactive.session()));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(8L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(8L, new BuildingPreviewEffectSnapshot(null)));
      long version = state.buildingVersion();

      state.applyBuilding(inactive);
      state.applyBuildingSession(new BuildingPreviewSessionPayload(7L, inactive.session()));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(7L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(7L, new BuildingPreviewEffectSnapshot(null)));

      assertEquals(version + 1L, state.buildingVersion());
      assertEquals(inactive, state.building());
   }

   @Test
   void disconnectResetsRevisionForReconnect() {
      ClientPreviewState state = new ClientPreviewState();
      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();
      state.applyBuildingSession(new BuildingPreviewSessionPayload(8L, inactive.session()));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(8L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(8L, new BuildingPreviewEffectSnapshot(null)));

      state.applyOperation(OperationPreviewPayload.inactive(4L));
      state.applyActivity(new ActivityStatePayload(FastPlaceActivity.OPERATION_SESSION));
      state.resetConnection();
      assertEquals(OperationPreviewPayload.inactive(), state.operation());
      assertEquals(FastPlaceActivity.NONE, state.activity());
      assertFalse(state.geometry().active());
      state.applyBuildingSession(new BuildingPreviewSessionPayload(1L, inactive.session()));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(1L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(1L, new BuildingPreviewEffectSnapshot(null)));

      assertEquals(3L, state.buildingVersion());
   }

   @Test
   void reconnectBuildingPreviewStaysHiddenUntilConfirmed() {
      ClientPreviewState state = new ClientPreviewState();
      state.resetConnection();
      state.beginReconnectRestore();
      var session = activeBuildingSession(11L);
      var parameters = new BuildingPreviewParametersPayload(11L, BuildingPreviewPayload.inactive().parameters());
      var effect = new BuildingPreviewEffectPayload(11L, new BuildingPreviewEffectSnapshot(null));
      var activity = new ActivityStatePayload(1L, FastPlaceActivity.BUILDING_SESSION);

      assertTrue(state.holdReconnectBuildingSession(session));
      assertTrue(state.holdReconnectBuildingParameters(parameters));
      assertTrue(state.holdReconnectBuildingEffect(effect));
      state.applyActivity(activity);
      assertFalse(state.building().active());
      assertEquals(FastPlaceActivity.BUILDING_SESSION, state.activity());

      ClientPreviewState.HeldReconnectPreviews held = state.takeHeldReconnectPreviews();
      assertFalse(state.reconnectRestorePending());
      assertEquals(session, held.buildingSession());
      assertEquals(parameters, held.buildingParameters());
      assertEquals(effect, held.buildingEffect());

      state.applyBuildingSession(held.buildingSession());
      state.applyBuildingParameters(held.buildingParameters());
      state.applyBuildingEffect(held.buildingEffect());
      assertTrue(state.building().active());
   }

   @Test
   void dismissingReconnectRestoreDropsHeldPreviewParts() {
      ClientPreviewState state = new ClientPreviewState();
      state.resetConnection();
      state.beginReconnectRestore();
      assertTrue(state.holdReconnectBuildingSession(activeBuildingSession(5L)));

      state.dismissReconnectRestore();

      assertFalse(state.reconnectRestorePending());
      assertNull(state.takeHeldReconnectPreviews());
      assertFalse(state.building().active());
   }

   @Test
   void newConnectionScopeDropsHeldPreviewsAndStillWaitsForTheNextSnapshot() {
      ClientPreviewState state = new ClientPreviewState();
      state.beginReconnectRestore();
      assertTrue(state.holdReconnectBuildingSession(activeBuildingSession(12L)));
      assertTrue(state.reconnectRestorePending());

      state.resetConnection();

      assertFalse(state.reconnectRestorePending());
      assertNull(state.takeHeldReconnectPreviews());
      assertFalse(state.building().active());

      assertTrue(state.holdReconnectBuildingSession(activeBuildingSession(13L)));
      assertTrue(state.reconnectRestorePending());
      assertFalse(state.building().active());
   }

   @Test
   void newConnectionScopeArmsTheConfirmGateForAVisiblePreview() {
      ClientPreviewState state = new ClientPreviewState();
      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();
      state.applyBuildingSession(activeBuildingSession(20L));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(20L, inactive.parameters()));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(20L, new BuildingPreviewEffectSnapshot(null)));
      assertTrue(state.building().active());

      state.resetConnection();

      assertFalse(state.building().active());
      assertTrue(state.holdReconnectBuildingSession(activeBuildingSession(21L)));
      assertTrue(state.reconnectRestorePending());
      assertFalse(state.building().active());
   }

   @Test
   void leadingInactiveSnapshotKeepsWaitingForTheReconnectSnapshot() {
      ClientPreviewState state = new ClientPreviewState();
      state.resetConnection();
      state.beginReconnectRestore();

      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();
      assertFalse(state.holdReconnectBuildingSession(new BuildingPreviewSessionPayload(4L, inactive.session())));
      assertFalse(state.reconnectRestorePending());

      assertTrue(state.holdReconnectBuildingSession(activeBuildingSession(5L)));
      assertTrue(state.reconnectRestorePending());
      assertFalse(state.building().active());
   }

   @Test
   void settledBoundaryLetsALaterRevisionApplyImmediately() {
      ClientPreviewState state = new ClientPreviewState();
      state.resetConnection();
      state.beginReconnectRestore();
      assertTrue(state.holdReconnectBuildingSession(activeBuildingSession(9L)));

      state.tickReconnectBoundary();
      state.tickReconnectBoundary();

      assertFalse(state.holdReconnectBuildingSession(activeBuildingSession(10L)));
      assertFalse(state.reconnectRestorePending());
   }

   @Test
   void unansweredBoundaryStopsHoldingLaterPreviews() {
      ClientPreviewState state = new ClientPreviewState();
      state.resetConnection();
      state.beginReconnectRestore();

      for (int tick = 0; tick < 200; tick++) {
         state.tickReconnectBoundary();
      }

      assertFalse(state.holdReconnectBuildingSession(activeBuildingSession(3L)));
      assertFalse(state.reconnectRestorePending());
   }

   @Test
   void reconnectGeometryPreviewStaysHiddenUntilConfirmed() {
      ClientPreviewState state = new ClientPreviewState();
      state.resetConnection();
      state.beginReconnectRestore();

      assertTrue(state.holdReconnectGeometry(activeGeometry()));
      assertFalse(state.geometry().active());

      ClientPreviewState.HeldReconnectPreviews held = state.takeHeldReconnectPreviews();
      state.applyGeometry(held.geometry());

      assertTrue(state.geometry().active());
   }

   @Test
   void ordinaryPreviewIsAppliedWhileNoReconnectRestoreIsPending() {
      ClientPreviewState state = new ClientPreviewState();
      state.resetConnection();

      assertFalse(state.holdReconnectBuildingSession(activeBuildingSession(6L)));
      assertFalse(state.holdReconnectGeometry(activeGeometry()));
   }

   private static BuildingPreviewSessionPayload activeBuildingSession(long revision) {
      BuildingPreviewPayload inactive = BuildingPreviewPayload.inactive();
      return new BuildingPreviewSessionPayload(
         revision,
         new BuildingPreviewSession(
            true,
            inactive.session().middleConfirmEnabled(),
            true,
            false,
            false,
            false,
            inactive.session().polygonVolumeShape(),
            List.of(BlockPos.ZERO),
            BlockPos.ZERO,
            inactive.session().placementContext()
         )
      );
   }

   private static GeometryPreviewPayload activeGeometry() {
      GeometryPreviewPayload inactive = GeometryPreviewPayload.inactive();
      return new GeometryPreviewPayload(
         true,
         GeometryMode.WALL,
         List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 2)),
         null,
         null,
         false,
         false,
         BlockPos.ZERO,
         inactive.polyhedronShapeVariant(),
         inactive.coneShapeVariant(),
         inactive.compoundShapeVariant(),
         inactive.polyhedronSizeMode(),
         inactive.fillMode(),
         inactive.conePlaneMode(),
         inactive.coneRadius(),
         inactive.coneScaleX(),
         inactive.coneScaleZ(),
         inactive.coneTopScaleOffset(),
         inactive.coneTopOffset(),
         inactive.coneRotationRadians(),
         inactive.coneGizmoLocal(),
         inactive.rotation(),
         inactive.polyhedronLocalScale(),
         inactive.polyhedronWorldScale(),
         inactive.polyhedronGizmoLocal(),
         inactive.selectedPointIndex()
      ).withRevision(1L);
   }
}
