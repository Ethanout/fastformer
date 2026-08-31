package io.github.fastformer.client.render.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import org.junit.jupiter.api.Test;

class ClientPreviewStateTest {
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
}
