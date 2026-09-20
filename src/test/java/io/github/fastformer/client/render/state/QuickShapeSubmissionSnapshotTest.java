package io.github.fastformer.client.render.state;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.preview.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuickShapeSubmissionSnapshotTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
   );

   @Test
   void partialUpdatePreservesDisplayButCannotSupplySubmissionData() {
      var state = new ClientPreviewState();
      var data = active(new BlockPos(1, 2, 3), BlockPos.ZERO);
      publish(state, data, 1, SCOPE);
      var captured = state.buildingSubmission().orElseThrow();
      var next = active(new BlockPos(4, 5, 6), BlockPos.ZERO);
      state.applyBuildingSession(new BuildingPreviewSessionPayload(2, next.session(), SCOPE));
      assertSame(captured.data(), state.building());
      assertTrue(state.buildingSubmission().isEmpty());
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(2, new BuildingPreviewEffectSnapshot(null), SCOPE));
      assertTrue(state.buildingSubmission().isEmpty());
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(2, next.parameters(), SCOPE));
      assertEquals(2, state.buildingSubmission().orElseThrow().revision());
      assertEquals(List.of(new BlockPos(1, 2, 3)), captured.points());
      assertEquals(List.of(new BlockPos(4, 5, 6)), state.buildingSubmission().orElseThrow().points());
   }

   @Test
   void reconnectAndUnscopedSnapshotsCannotSupplySubmissionIdentity() {
      var state = new ClientPreviewState();
      var data = active(BlockPos.ZERO, BlockPos.ZERO);
      publish(state, data, 1, SCOPE);
      state.resetConnection();
      assertTrue(state.buildingSubmission().isEmpty());
      state.applyBuilding(data);
      assertTrue(state.buildingSubmission().isEmpty());
      publish(state, data, 2, OperationCallbackScope.unscoped());
      assertTrue(state.buildingSubmission().isEmpty());
   }

   @Test
   void snapshotOwnsMutablePositionsAndRejectsMixedScopes() {
      var first = new BlockPos.MutableBlockPos(1, 2, 3);
      var offset = new BlockPos.MutableBlockPos(3, 0, 0);
      var data = active(first, offset);
      first.set(99, 99, 99);
      offset.set(99, 99, 99);
      assertEquals(new BlockPos(1, 2, 3), data.points().getFirst());
      assertEquals(new BlockPos(3, 0, 0), data.freeScrollOffset());
      var state = new ClientPreviewState();
      state.applyBuildingSession(new BuildingPreviewSessionPayload(1, data.session(), SCOPE));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(1, data.parameters(), SCOPE));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(1, new BuildingPreviewEffectSnapshot(null), OperationCallbackScope.unscoped()));
      assertTrue(state.buildingSubmission().isEmpty());
   }

   private static void publish(ClientPreviewState state, BuildingPreviewPayload data, long revision, OperationCallbackScope scope) {
      state.applyBuildingSession(new BuildingPreviewSessionPayload(revision, data.session(), scope));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(revision, data.parameters(), scope));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(revision, new BuildingPreviewEffectSnapshot(null), scope));
   }

   private static BuildingPreviewPayload active(BlockPos first, BlockPos offset) {
      var defaults = BuildingPreviewPayload.inactive();
      return new BuildingPreviewPayload(true, true, true, false, false, false,
         PolygonVolumeShape.EXTRUDE, List.of(first), 0, offset, Vec3.ZERO, Vec3.ZERO,
         BlockPos.ZERO, 0, defaults.pointMode(), RaycastPlacement.SURFACE, defaults.lineMode(),
         defaults.faceMode(), defaults.volumeMode(), defaults.fillMode(), LineTieBias.DEFAULT, null);
   }
}
