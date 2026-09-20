package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.preview.*;
import io.github.fastformer.client.render.state.ClientPreviewState;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class QuickShapeKeyboardSnapshotTest {
   @Test
   void queuedEnterRetainsItsOriginalSnapshotAfterARevisionArrives() {
      var state = new ClientPreviewState();
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var defaults = BuildingPreviewPayload.inactive();
      var session = new BuildingPreviewSession(true, true, true, false, false, false,
         defaults.polygonVolumeShape(), List.of(new BlockPos(1, 2, 3)), BlockPos.ZERO, null);
      state.applyBuildingSession(new BuildingPreviewSessionPayload(1, session, scope));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(1, defaults.parameters(), scope));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(1, new BuildingPreviewEffectSnapshot(null), scope));
      QuickShapeSubmissionSnapshot captured = state.buildingSubmission().orElseThrow();
      var input = new ClientInputSession();
      input.postKeyboard(new KeyboardInputSnapshot(257, 0, 1, 0, 123, false, false)
         .withQuickShapeSubmission(captured));
      state.applyBuildingSession(new BuildingPreviewSessionPayload(2, session, scope));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(2, defaults.parameters(), scope));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(2, new BuildingPreviewEffectSnapshot(null), scope));
      var delivered = new java.util.concurrent.atomic.AtomicBoolean();
      input.drainPhysicalEvents(() -> true, event -> {
         delivered.set(true);
         assertSame(captured, event.quickShapeSubmission());
         assertEquals(1, event.quickShapeSubmission().revision());
         assertEquals(2, state.buildingSubmission().orElseThrow().revision());
      }, event -> fail("unexpected scroll"));
      assertTrue(delivered.get());
   }
}
