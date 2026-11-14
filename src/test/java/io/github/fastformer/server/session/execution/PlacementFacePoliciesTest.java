package io.github.fastformer.server.session.execution;
import io.github.fastformer.common.placement.plan.PlacementGeometryPlan;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicy;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet;
import io.github.fastformer.common.quickshape.mode.*;
import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PlacementFacePoliciesTest {
   @Test void clientValidationAndSynchronousServerGenerationUseTheSameCapturedRules() {
      var points = List.of(BlockPos.ZERO, new BlockPos(6, 0, 0), new BlockPos(6, 0, 6), new BlockPos(0, 0, 6));
      var modes = new FastPlaceGeometry.Modes(PointMode.RAYCAST, RaycastPlacement.SURFACE, LineMode.AXIS,
         FaceMode.POLYGON, VolumeMode.PERPENDICULAR_TO_FACE, FillMode.SOLID, 0, false);
      var chains = new EnumMap<FaceClass, List<ResourceLocation>>(FaceClass.class);
      for (var kind : FaceClass.values()) chains.put(kind, List.of(FaceRasterPolicies.id("edge_only")));
      var policies = new FaceRasterPolicySet(FaceRasterPolicies.CURRENT_QUAD, new FaceRasterPolicy(chains),
         FaceRasterPolicies.CURRENT_VOLUME_FACE);
      var client = new PlacementGeometryPlan(points, modes, false, PolygonVolumeShape.EXTRUDE, 1000, null, policies);
      var defaults = new PlacementGeometryPlan(points, modes, false, PolygonVolumeShape.EXTRUDE, 1000, null);
      var task = new PlacementTaskPlan(null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 1000, Level.OVERWORLD);
      var server = new PlacementGenerationPlan(points, modes, false, PolygonVolumeShape.EXTRUDE, null, 49, task, policies);
      var validated = client.generate(BlockGenerationObserver.NONE);
      assertTrue(validated.successful());
      assertEquals(validated.blocks(), server.generateNow().result().blocks());
      assertTrue(defaults.generate(BlockGenerationObserver.NONE).blocks().size() > validated.blocks().size());
      assertSame(policies, client.facePolicies());
      assertSame(policies, server.facePolicies());
   }
}
