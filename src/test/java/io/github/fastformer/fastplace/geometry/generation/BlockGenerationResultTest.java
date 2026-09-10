package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BlockGenerationResultTest {
   @Test
   void convertsLegacyFailuresAtThePlacementBoundary() {
      BlockGenerationResult constraints = BlockGenerationResult.fromLegacy(GenerationFailed.faceConstraints());
      BlockGenerationResult limit = BlockGenerationResult.fromLegacy(GenerationLimitExceeded.witness(10));

      assertEquals(BlockGenerationResult.Status.CONSTRAINTS_FAILED, constraints.status());
      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, limit.status());
      assertTrue(constraints.blocks().isEmpty());
      assertTrue(limit.blocks().isEmpty());
   }

   @Test
   void namedFailureFactoriesNeverExposeSuccessfulEmptyResults() {
      BlockGenerationResult constraints = BlockGenerationResult.constraintsFailed();
      BlockGenerationResult limit = BlockGenerationResult.limitExceeded();

      assertEquals(BlockGenerationResult.Status.CONSTRAINTS_FAILED, constraints.status());
      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, limit.status());
      assertFalse(constraints.successful());
      assertFalse(limit.successful());
   }

   @Test
   void typedShapeBoundariesRejectIncompleteGeometry() {
      PolyhedronParameters incompleteSphere = new PolyhedronParameters(
         net.minecraft.world.phys.Vec3.ZERO, null, 0, null
      );
      ConePrismParameters incompleteCone = new ConePrismParameters(
         java.util.List.of(),
         java.util.Optional.empty(),
         0,
         io.github.fastformer.fastplace.ConePlaneMode.RADIUS,
         1.0,
         1.0,
         1.0,
         0.0,
         net.minecraft.world.phys.Vec3.ZERO,
         0.0
      );

      assertEquals(
         BlockGenerationResult.Status.CONSTRAINTS_FAILED,
         PolyhedronGenerator.generateResult(incompleteSphere, io.github.fastformer.fastplace.FillMode.SOLID, 100).status()
      );
      assertEquals(
         BlockGenerationResult.Status.CONSTRAINTS_FAILED,
         ArbitraryConvexPolyhedronGenerator.generateResult(
            java.util.List.of(net.minecraft.world.phys.Vec3.ZERO),
            io.github.fastformer.fastplace.FillMode.SOLID,
            100
         ).status()
      );
      assertEquals(
         BlockGenerationResult.Status.CONSTRAINTS_FAILED,
         ConePrismGenerator.generateResult(incompleteCone, io.github.fastformer.fastplace.FillMode.SOLID, 100).status()
      );
      assertEquals(
         BlockGenerationResult.Status.CONSTRAINTS_FAILED,
         ConePrismGenerator.generateResult(null, io.github.fastformer.fastplace.FillMode.SOLID, 100).status()
      );
   }

   @Test
   void successfulPackedSetsKeepTheirDrainingOwnership() {
      ObservedBlockSet packed = new ObservedBlockSet(BlockGenerationObserver.NONE);
      packed.add(BlockPos.ZERO);

      BlockGenerationResult result = BlockGenerationResult.fromLegacy(packed);

      assertTrue(result.successful());
      assertTrue(result.blocks() instanceof DrainingBlockSet);
      assertEquals(Set.of(BlockPos.ZERO), result.blocks());
      assertFalse(result.blocks().isEmpty());
   }

   @Test
   void positionSourcePreservesTheStorageBoundary() {
      ObservedBlockSet packed = new ObservedBlockSet(BlockGenerationObserver.NONE);
      packed.add(BlockPos.ZERO);
      packed.add(new BlockPos(1, 2, 3));

      BlockPositionSource source = BlockGenerationResult.fromLegacy(packed).positionSource();

      assertTrue(source.supportsDraining());
      assertEquals(2, source.size());
      assertEquals(BlockPos.ZERO, source.drainingIterator().next());
      assertEquals(1, source.size());
   }

   @Test
   void geometryResultBoundaryKeepsTypedFailureStatus() {
      var modes = new io.github.fastformer.fastplace.FastPlaceGeometry.Modes(
         io.github.fastformer.fastplace.PointMode.RAYCAST,
         io.github.fastformer.fastplace.RaycastPlacement.EMBEDDED,
         io.github.fastformer.fastplace.LineMode.AXIS,
         io.github.fastformer.fastplace.FaceMode.COORDINATE_PLANE,
         io.github.fastformer.fastplace.VolumeMode.FREE,
         io.github.fastformer.fastplace.FillMode.SOLID,
         0.0,
         false
      );
      BlockGenerationResult result = io.github.fastformer.fastplace.FastPlaceGeometry.blocksResult(
         java.util.List.of(BlockPos.ZERO, new BlockPos(2, 0, 0)),
         modes,
         false,
         io.github.fastformer.fastplace.PolygonVolumeShape.EXTRUDE,
         1
      );

      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, result.status());
      assertTrue(result.blocks().isEmpty());
   }

   @Test
   void geometryResultBoundaryRejectsMissingInputAndZeroBudget() {
      var modes = new io.github.fastformer.fastplace.FastPlaceGeometry.Modes(
         io.github.fastformer.fastplace.PointMode.RAYCAST,
         io.github.fastformer.fastplace.RaycastPlacement.EMBEDDED,
         io.github.fastformer.fastplace.LineMode.AXIS,
         io.github.fastformer.fastplace.FaceMode.COORDINATE_PLANE,
         io.github.fastformer.fastplace.VolumeMode.FREE,
         io.github.fastformer.fastplace.FillMode.SOLID,
         0.0,
         false
      );

      BlockGenerationResult missing = io.github.fastformer.fastplace.FastPlaceGeometry.blocksResult(
         java.util.List.of(), modes, false, io.github.fastformer.fastplace.PolygonVolumeShape.EXTRUDE, 10
      );
      BlockGenerationResult noBudget = io.github.fastformer.fastplace.FastPlaceGeometry.blocksResult(
         java.util.List.of(BlockPos.ZERO), modes, false, io.github.fastformer.fastplace.PolygonVolumeShape.EXTRUDE, 0
      );

      assertEquals(BlockGenerationResult.Status.CONSTRAINTS_FAILED, missing.status());
      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, noBudget.status());
   }
}
