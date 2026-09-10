package io.github.fastformer.client.render.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectSnapshot;
import io.github.fastformer.network.payload.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
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
}
