package io.github.fastformer.client.session.preview;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.preview.quickshape.BuildingPreviewSession;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.mode.SessionActivity;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientPreviewUpdatesTest {
   @Test void operationSnapshotPublishesOnlyAfterItsBoundEditorAcceptsIt() {
      var state = new ClientPreviewState();
      var calls = new ArrayList<OperationPreviewPayload>();
      var updates = new ClientPreviewUpdates(state, payload -> {
         assertEquals(0, state.revision());
         calls.add(payload);
         return payload.revision() == 2;
      }, ignored -> {});
      var rejected = OperationPreviewPayload.inactive(1);
      var accepted = OperationPreviewPayload.inactive(2);
      updates.operation(rejected);
      assertEquals(0, state.revision());
      updates.operation(accepted);
      assertSame(accepted, state.operation());
      assertEquals(List.of(rejected, accepted), calls);
      assertEquals(1, state.revision());
   }

   @Test void onlyAcceptedActivityRevisionsReachSubmissionObservation() {
      var state = new ClientPreviewState();
      var calls = new ArrayList<SessionActivity>();
      var updates = new ClientPreviewUpdates(state, ignored -> true, activity -> {
         assertEquals(activity, state.activity());
         calls.add(activity);
      });
      var latest = new ActivityStatePayload(4, SessionActivity.RESTORE_TASK);
      updates.activity(latest);
      updates.activity(latest);
      updates.activity(new ActivityStatePayload(3, SessionActivity.NONE));
      assertEquals(List.of(SessionActivity.RESTORE_TASK), calls);
      assertEquals(1, state.revision());
   }

   @Test void reconnectConfirmationReplaysAllPartsInPublicationOrderExactlyOnce() {
      var state = new ClientPreviewState();
      var updates = new ClientPreviewUpdates(state, ignored -> true, ignored -> {});
      var base = BuildingPreviewSnapshot.inactive();
      state.beginReconnectRestore();
      updates.buildingSession(activeBuildingSession(8));
      updates.buildingParameters(new BuildingPreviewParametersPayload(8, base.parameters()));
      updates.buildingEffect(new BuildingPreviewEffectPayload(8, new io.github.fastformer.common.preview.quickshape.BuildingPreviewEffectSnapshot(base.activePlacementEffect())));
      assertTrue(state.reconnectRestorePending());
      assertFalse(state.building().active());
      assertEquals(0, state.buildingVersion());
      assertTrue(updates.confirmReconnectRestore());
      assertTrue(state.building().active());
      assertEquals(1, state.buildingVersion());
      assertFalse(updates.confirmReconnectRestore());
      assertEquals(1, state.buildingVersion());
   }

   @Test void mismatchedRevisionDismissesHeldPreviewInsteadOfCombiningItWithNewParts() {
      var state = new ClientPreviewState();
      var updates = new ClientPreviewUpdates(state, ignored -> true, ignored -> {});
      var base = BuildingPreviewSnapshot.inactive();
      state.beginReconnectRestore();
      updates.buildingSession(activeBuildingSession(8));
      updates.buildingParameters(new BuildingPreviewParametersPayload(9, base.parameters()));
      assertFalse(state.reconnectRestorePending());
      assertFalse(updates.confirmReconnectRestore());
      updates.buildingSession(activeBuildingSession(9));
      updates.buildingEffect(new BuildingPreviewEffectPayload(9, new io.github.fastformer.common.preview.quickshape.BuildingPreviewEffectSnapshot(base.activePlacementEffect())));
      assertTrue(state.building().active());
   }

   @Test void geometryReconnectAndStaleRevisionsUseTheSameApplicationBoundary() {
      var state = new ClientPreviewState();
      var updates = new ClientPreviewUpdates(state, ignored -> true, ignored -> {});
      state.beginReconnectRestore();
      var active = geometry(GeometryMode.POLYHEDRON, true, 2);
      updates.geometry(active);
      assertTrue(state.reconnectRestorePending());
      assertFalse(state.geometry().active());
      assertTrue(updates.confirmReconnectRestore());
      assertSame(active, state.geometry());
      long version = state.geometryVersion();
      updates.geometry(active);
      updates.geometry(GeometryPreviewPayload.inactive().withRevision(1));
      assertEquals(version, state.geometryVersion());
   }

   @Test void transformContinuityRemembersIntermediateBreaksWithoutDrawingAFrame() {
      var state = new ClientPreviewState();
      var updates = new ClientPreviewUpdates(state, ignored -> true, ignored -> {});
      updates.geometry(geometry(GeometryMode.POLYHEDRON, true, 1));
      long initial = state.geometryTransformVersion();
      updates.geometry(geometry(GeometryMode.POLYHEDRON, true, 2));
      assertEquals(initial, state.geometryTransformVersion());
      updates.geometry(geometry(GeometryMode.POLYHEDRON, false, 3));
      updates.geometry(geometry(GeometryMode.POLYHEDRON, true, 4));
      assertEquals(initial + 2, state.geometryTransformVersion());
      updates.geometry(geometry(GeometryMode.POLYHEDRON, false, 2));
      assertEquals(initial + 2, state.geometryTransformVersion());
      state.resetConnection();
      assertEquals(initial + 3, state.geometryTransformVersion());
   }

   private static BuildingPreviewSessionPayload activeBuildingSession(long revision) {
      var base = BuildingPreviewSnapshot.inactive().session();
      return new BuildingPreviewSessionPayload(revision, new BuildingPreviewSession(
         true, base.middleConfirmEnabled(), true, false, false, false, base.polygonVolumeShape(),
         List.of(BlockPos.ZERO), BlockPos.ZERO, base.placementContext()));
   }

   private static GeometryPreviewPayload geometry(GeometryMode mode, boolean closed, long revision) {
      var base = GeometryPreviewPayload.inactive();
      return new GeometryPreviewPayload(true, mode, List.of(BlockPos.ZERO), null, null, closed, false, BlockPos.ZERO,
         base.polyhedronShapeVariant(), base.coneShapeVariant(), base.compoundShapeVariant(), base.polyhedronSizeMode(),
         base.fillMode(), base.conePlaneMode(), base.coneRadius(), base.coneScaleX(), base.coneScaleZ(),
         base.coneTopScaleOffset(), base.coneTopOffset(), base.coneRotationRadians(), base.coneGizmoLocal(),
         base.rotation(), base.polyhedronLocalScale(), base.polyhedronWorldScale(), base.polyhedronGizmoLocal(),
         base.selectedPointIndex()).withRevision(revision);
   }
}
