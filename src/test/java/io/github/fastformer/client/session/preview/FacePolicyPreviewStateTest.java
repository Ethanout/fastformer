package io.github.fastformer.client.session.preview;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSession;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FacePolicyPreviewStateTest {
   private static final BuildingPreviewSnapshot BASE = BuildingPreviewSnapshot.inactive();
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
   private static final FaceRasterPolicySet CUSTOM = new FaceRasterPolicySet(
      FaceRasterPolicies.CLASSIFIED, FaceRasterPolicies.CURRENT_POLYGON, FaceRasterPolicies.CURRENT_VOLUME_FACE);

   @Test void mismatchingParametersImmediatelyHideTheOldPreviewAndSubmission() {
      var state = new ClientPreviewState();
      publish(state, 1, FaceRasterPolicySet.DEFAULT);
      assertTrue(state.building().active());
      assertTrue(state.buildingSubmission().isPresent());
      long version = state.buildingVersion();
      state.applyBuildingParameters(parameters(2, CUSTOM));
      assertFalse(state.building().active());
      assertTrue(state.buildingSubmission().isEmpty());
      assertTrue(state.buildingVersion() > version);
      publish(state, 2, CUSTOM);
      assertFalse(state.building().active());
      state.applyFacePolicies(CUSTOM);
      assertTrue(state.building().active());
      assertSame(CUSTOM, state.building().facePolicies());
      assertEquals(CUSTOM.hash(), state.buildingSubmission().orElseThrow().data().parameters().facePolicyHash());
      state.applyBuildingParameters(parameters(1, FaceRasterPolicySet.DEFAULT));
      assertSame(CUSTOM, state.building().facePolicies());
   }

   @Test void changingRulesBeforeParametersAlsoInvalidatesAndEqualRulesDoNotRebuild() {
      var state = new ClientPreviewState();
      publish(state, 1, FaceRasterPolicySet.DEFAULT);
      state.applyFacePolicies(CUSTOM);
      assertFalse(state.building().active());
      assertTrue(state.buildingSubmission().isEmpty());
      publish(state, 2, CUSTOM);
      assertTrue(state.building().active());
      long version = state.buildingVersion();
      state.applyFacePolicies(new FaceRasterPolicySet(CUSTOM.quad(), CUSTOM.polygon(), CUSTOM.volume()));
      assertEquals(version, state.buildingVersion());
   }

   @Test void connectionResetDropsPreviousServerRulesAndIdleStartsWaitForMatchingRules() {
      var state = new ClientPreviewState();
      state.applyFacePolicies(CUSTOM);
      publish(state, 1, CUSTOM);
      state.resetConnection();
      publish(state, 1, CUSTOM);
      assertFalse(state.building().active());
      state.applyBuildingSession(new BuildingPreviewSessionPayload(2, BASE.session(), SCOPE));
      state.applyBuildingParameters(parameters(2, CUSTOM));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(2, BASE.effect(), SCOPE));
      assertTrue(state.idleBuildingSession().isEmpty());
      state.applyFacePolicies(CUSTOM);
      assertTrue(state.idleBuildingSession().isPresent());
   }

   private static BuildingPreviewParametersPayload parameters(long revision, FaceRasterPolicySet policies) {
      return new BuildingPreviewParametersPayload(revision, BASE.parameters().withFacePolicyHash(policies.hash()), SCOPE);
   }

   private static void publish(ClientPreviewState state, long revision, FaceRasterPolicySet policies) {
      var session = new BuildingPreviewSession(true, true, true, false, false, false, BASE.polygonVolumeShape(),
         List.of(BlockPos.ZERO, new BlockPos(4, 0, 0), new BlockPos(0, 0, 4)), BlockPos.ZERO, null);
      state.applyBuildingSession(new BuildingPreviewSessionPayload(revision, session, SCOPE));
      state.applyBuildingParameters(parameters(revision, policies));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(revision, BASE.effect(), SCOPE));
   }
}
