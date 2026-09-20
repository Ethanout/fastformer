package io.github.fastformer.fastplace.placement.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.DrainingBlockSet;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PlacementGenerationPlanTest {
   @Test
   void outlineEffectNeedsNoSecondaryGeometryGeneration() {
      PlacementGenerationPlan plan = plan(FillMode.OUTLINE);

      PlacementGenerationPlan.GeneratedPlacement generated = plan.generateNow();

      assertEquals(0L, plan.additionalGeneratedBlockSets());
      assertEquals(BlockGenerationResult.Status.SUCCESS, generated.result().status());
      assertEquals(3, generated.result().blocks().size());
      assertTrue(generated.result().blocks() instanceof DrainingBlockSet);
   }

   @Test
   void solidEffectNeedsNoSecondaryGeometryGeneration() {
      PlacementGenerationPlan plan = plan(FillMode.SOLID);

      PlacementGenerationPlan.GeneratedPlacement generated = plan.generateNow();

      assertEquals(0L, plan.additionalGeneratedBlockSets());
      assertEquals(BlockGenerationResult.Status.SUCCESS, generated.result().status());
      assertFalse(generated.result().blocks().isEmpty());
   }

   @Test
   void effectTransformsTargetsAndItsEstimateBeforeTaskCreation() {
      ResolvedPlacementEffect expanding = new ResolvedPlacementEffect(
         ResourceLocation.fromNamespaceAndPath("fastformer", "expanding_test"),
         blocks -> {
            HashSet<BlockPos> result = new HashSet<>(blocks);
            result.add(new BlockPos(10, 0, 0));
            return result;
         },
         estimate -> estimate + 1L,
         ignored -> Map.of()
      );
      PlacementGenerationPlan base = plan(FillMode.OUTLINE);
      PlacementGenerationPlan plan = new PlacementGenerationPlan(
         base.points(), base.modes(), base.polygonHeightConfirmed(), base.polygonVolumeShape(),
         expanding, base.estimatedBlocks(), base.taskPlan()
      );

      assertEquals(4L, plan.estimatedTargetBlocks());
      assertEquals(1L, plan.additionalGeneratedBlockSets());
      assertTrue(plan.generateNow().result().blocks().contains(new BlockPos(10, 0, 0)));
   }

   @Test
   void synchronousResultPreservesConstraintFailureMarker() {
      ResolvedPlacementEffect failing = new ResolvedPlacementEffect(
         ResourceLocation.fromNamespaceAndPath("fastformer", "failing_test"),
         ignored -> GenerationFailed.faceConstraints(),
         estimate -> estimate,
         ignored -> Map.of()
      );
      PlacementGenerationPlan base = plan(FillMode.OUTLINE);
      PlacementGenerationPlan plan = new PlacementGenerationPlan(
         base.points(), base.modes(), base.polygonHeightConfirmed(), base.polygonVolumeShape(),
         failing, base.estimatedBlocks(), base.taskPlan()
      );

      assertEquals(
         BlockGenerationResult.Status.CONSTRAINTS_FAILED,
         plan.generateNow().result().status()
      );
   }

   @Test
   void targetEffectDoesNotReceiveAnExceededLimitWitness() {
      AtomicInteger transformations = new AtomicInteger();
      ResolvedPlacementEffect transforming = new ResolvedPlacementEffect(
         ResourceLocation.fromNamespaceAndPath("fastformer", "limit_test"),
         blocks -> {
            transformations.incrementAndGet();
            return blocks;
         },
         estimate -> estimate,
         ignored -> Map.of()
      );
      PlacementGenerationPlan base = plan(FillMode.OUTLINE);
      PlacementTaskPlan limitedTask = new PlacementTaskPlan(
         null, transforming.stateOverrides(), OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY, 1, Level.OVERWORLD
      );
      PlacementGenerationPlan plan = new PlacementGenerationPlan(
         base.points(), base.modes(), base.polygonHeightConfirmed(), base.polygonVolumeShape(),
         transforming, base.estimatedBlocks(), limitedTask
      );

      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, plan.generateNow().result().status());
      assertEquals(0, transformations.get());
   }

   private static PlacementGenerationPlan plan(FillMode fillMode) {
      FastPlaceGeometry.Modes modes = new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.POLYGON,
         VolumeMode.PERPENDICULAR_TO_FACE,
         fillMode,
         0.0,
         false
      );
      ResolvedPlacementEffect effect = new ResolvedPlacementEffect(
         ResourceLocation.fromNamespaceAndPath("fastformer", "test"), ignored -> Map.of()
      );
      PlacementTaskPlan taskPlan = new PlacementTaskPlan(
         null, effect.stateOverrides(), OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY, 100, Level.OVERWORLD
      );
      return new PlacementGenerationPlan(
         List.of(new BlockPos(0, 0, 0), new BlockPos(2, 0, 0)),
         modes,
         false,
         PolygonVolumeShape.EXTRUDE,
         effect,
         3L,
         taskPlan
      );
   }
}
