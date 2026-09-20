package io.github.fastformer.client.operation.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspaceCompositionLimitTest {
   @Test
   void aScaleBeyondIntegerRangeCannotWrapIntoASmallSuccess() {
      WorkspaceTransform transform = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 4294967297.0);
      Composition<String> result = WorkspacePreviewComposer.composeValues(
         Map.of(BlockPos.ZERO, "a"), transform, CompositionBudget.INTERACTION
      );
      Composition.OverBudget<?> refusal = assertInstanceOf(Composition.OverBudget.class, result);
      assertEquals(Composition.Limit.WORK, refusal.limit());
   }

   @Test
   void aScanVolumeBeyondLongRangeIsRefusedBeforeScanning() {
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.ZERO, Vec3.ZERO, OperationStackRegion.origin(), BlockPos.ZERO,
         new Vec3(2097152, 2097152, 2097152)
      );
      Composition<String> result = WorkspacePreviewComposer.composeValues(
         Map.of(BlockPos.ZERO, "a"), transform, new CompositionBudget(100, Long.MAX_VALUE)
      );
      Composition.OverBudget<?> refusal = assertInstanceOf(Composition.OverBudget.class, result);
      assertEquals(Composition.Limit.WORK, refusal.limit());
   }

   @Test
   void aLegalEmptyDownscaleIsComposedNotOverBudget() {
      WorkspaceTransform shrink = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 0.01);
      Map<BlockPos, String> source = Map.of(
         new BlockPos(0, 0, 0), "a",
         new BlockPos(100, 0, 0), "b"
      );

      Composition<String> composition = WorkspacePreviewComposer.composeValues(
         source, shrink, CompositionBudget.ofOutput(8)
      );

      Composition.Composed<String> composed = assertInstanceOf(Composition.Composed.class, composition);
      assertTrue(composed.values().isEmpty());
   }

   @Test
   void aScanThatExceedsWorkIsWorkNotOutput() {
      WorkspaceTransform stretch = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, 50.0);
      CompositionBudget budget = new CompositionBudget(100, 20);
      Composition<String> composition = WorkspacePreviewComposer.composeValues(
         Map.of(BlockPos.ZERO, "a", new BlockPos(1, 0, 0), "b"), stretch, budget
      );

      Composition.OverBudget<String> over = assertInstanceOf(Composition.OverBudget.class, composition);
      assertEquals(Composition.Limit.WORK, over.limit());
   }

   @Test
   void uniqueRepeatOutputStopsBeforeBuildingAList() {
      OperationStackRegion repeats = new OperationStackRegion(
         BlockPos.ZERO, new BlockPos(128, 0, 0)
      );
      WorkspaceTransform transform = new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, repeats);
      Composition<String> composition = WorkspacePreviewComposer.composeValues(
         Map.of(BlockPos.ZERO, "a"), transform, CompositionBudget.ofOutput(8)
      );

      Composition.OverBudget<String> over = assertInstanceOf(Composition.OverBudget.class, composition);
      assertEquals(Composition.Limit.OUTPUT, over.limit());
      assertTrue(over.reached() > 8);
   }

   @Test
   void overlappingRepeatsCountWorkSeparatelyFromUniqueOutput() {
      OperationStackRegion repeats = new OperationStackRegion(BlockPos.ZERO, new BlockPos(20, 0, 0));
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.ZERO, Vec3.ZERO, repeats, BlockPos.ZERO
      );
      CompositionBudget budget = new CompositionBudget(100, 10);
      Composition<String> composition = WorkspacePreviewComposer.composeValues(
         Map.of(BlockPos.ZERO, "a"), transform, budget
      );

      Composition.OverBudget<String> over = assertInstanceOf(Composition.OverBudget.class, composition);
      assertEquals(Composition.Limit.WORK, over.limit());
   }

   @Test
   void aNoRotationPlanAtTheOutputCapFitsTheWorkFactor() {
      Map<BlockPos, String> source = Map.of(BlockPos.ZERO, "a", new BlockPos(1, 0, 0), "b");
      CompositionBudget budget = CompositionBudget.ofOutput(2);
      Composition<String> composition = WorkspacePreviewComposer.composeValues(
         source, WorkspaceTransform.IDENTITY.withTranslation(new Vec3(3, 0, 0)), budget
      );

      assertInstanceOf(Composition.Composed.class, composition);
      assertEquals(2, ((Composition.Composed<String>)composition).values().size());
   }
}
