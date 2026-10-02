package io.github.fastformer.workspace.preview;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorkspaceSceneTest {
   @Test void creationOrderSurvivesReusedIdsAndAirDoesNotHideLowerLayers() {
      var scene = new WorkspaceScene<String>(value -> !value.equals("air"));
      scene.overlay(8, Map.of(BlockPos.ZERO, "stone", BlockPos.ZERO.east(), "wood"));
      scene.overlay(1, Map.of(BlockPos.ZERO, "gold", BlockPos.ZERO.east(), "air"));
      assertEquals(Map.of(BlockPos.ZERO, "gold", BlockPos.ZERO.east(), "wood"), scene.blocks());
      assertEquals(Map.of(BlockPos.ZERO, 1, BlockPos.ZERO.east(), 8), scene.owners());
   }

   @Test void swappingSourcesAndTargetsNeverClearsTheFinalDestination() {
      var scene = new WorkspaceScene<String>(value -> true);
      scene.clearSources(Set.of(BlockPos.ZERO, BlockPos.ZERO.east()));
      scene.overlay(1, Map.of(BlockPos.ZERO.east(), "stone"));
      scene.overlay(2, Map.of(BlockPos.ZERO, "wood"));
      assertEquals(Map.of(BlockPos.ZERO, "wood", BlockPos.ZERO.east(), "stone"), scene.desired("air"));
   }

   @Test void deletionClearsSharedSourcesOnceAndRetainsUnsubmittedClaims() {
      var scene = new WorkspaceScene<String>(value -> true);
      scene.clearSources(Set.of(BlockPos.ZERO, BlockPos.ZERO.east()));
      scene.clearSources(Set.of(BlockPos.ZERO));
      assertEquals(Set.of(BlockPos.ZERO), scene.exclusiveSources(Set.of(BlockPos.ZERO.east())));
   }
}
