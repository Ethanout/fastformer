package io.github.fastformer.common.workspace.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspaceCompositionIsolationTest {
   @Test
   void aFrameMatchesTheCompositionWithoutRetainingAnotherSource() {
      var transform = WorkspaceTransform.IDENTITY.withRotation(new Vec3(0, Math.PI / 2, 0));
      var first = Map.of(BlockPos.ZERO, "first");
      var replacement = Map.of(new BlockPos(8, 0, 0), "replacement");

      var firstFrame = WorkspaceComposer.geometryFrame(first, transform);
      var replacementFrame = WorkspaceComposer.geometryFrame(replacement, transform);
      var composed = assertInstanceOf(Composition.Composed.class,
         WorkspaceComposer.composeValues(replacement, transform, CompositionBudget.RENDER));

      assertEquals(new Vec3(0.5, 0.5, 0.5), firstFrame.rotationSteps().getFirst().pivot());
      assertEquals(new Vec3(8.5, 0.5, 0.5), replacementFrame.rotationSteps().getFirst().pivot());
      assertEquals(replacementFrame, composed.frame());
      assertEquals(Map.of(new BlockPos(8, 0, 0), "replacement"), composed.values());
      assertEquals(firstFrame, WorkspaceComposer.geometryFrame(first, transform));
   }

   @Test
   void endingASceneDoesNotRequireClearingSharedCompositionState() {
      var translation = WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1, 0, 0));
      var oldScene = new WorkspaceScene<String>(value -> true);
      oldScene.overlay(4, WorkspaceComposer.resolveValues(Map.of(BlockPos.ZERO, "old"), translation));
      Map<BlockPos, String> oldResult = Map.copyOf(oldScene.blocks());

      var newScene = new WorkspaceScene<String>(value -> true);
      newScene.overlay(4, WorkspaceComposer.resolveValues(Map.of(new BlockPos(8, 0, 0), "new"), translation));

      assertEquals(Map.of(new BlockPos(9, 0, 0), "new"), newScene.blocks());
      assertEquals(Map.of(new BlockPos(1, 0, 0), "old"), oldResult);
      assertFalse(newScene.blocks().containsKey(new BlockPos(1, 0, 0)));
   }

   @Test
   void aBudgetRefusalDoesNotPoisonTheNextAction() {
      var source = Map.of(BlockPos.ZERO, "block");
      var repeat = new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO,
         new OperationStackRegion(BlockPos.ZERO, new BlockPos(2, 0, 0)));

      var refused = assertInstanceOf(Composition.OverBudget.class,
         WorkspaceComposer.composeValues(source, repeat, CompositionBudget.ofOutput(1)));
      var next = assertInstanceOf(Composition.Composed.class,
         WorkspaceComposer.composeValues(source, WorkspaceTransform.IDENTITY, CompositionBudget.ofOutput(1)));

      assertEquals(Composition.Limit.OUTPUT, refused.limit());
      assertEquals(source, next.values());
   }

   @Test
   void oversizedFramesKeepTheConservativeEnvelopePathAvailable() {
      var repeat = new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO,
         new OperationStackRegion(BlockPos.ZERO, new BlockPos(100_001, 0, 0)));

      assertNull(WorkspaceComposer.geometryFrame(Map.of(BlockPos.ZERO, "block"), repeat));
      assertFalse(WorkspaceComposer.frameChangesGeometry(
         WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1, 0, 0))));
   }
}
