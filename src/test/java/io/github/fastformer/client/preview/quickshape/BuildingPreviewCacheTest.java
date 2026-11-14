package io.github.fastformer.client.preview.quickshape;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BuildingPreviewCacheTest {
   @Test
   void snapshotChangesClearOnlyTheBoundPreviewsResources() {
      var firstState = new ClientPreviewState();
      var secondState = new ClientPreviewState();
      var firstClears = new AtomicInteger();
      var secondClears = new AtomicInteger();
      var first = cache(firstState, firstClears);
      var second = cache(secondState, secondClears);
      first.applyBuildingPreviewResults();
      second.applyBuildingPreviewResults();
      assertEquals(1, firstClears.get());
      assertEquals(1, secondClears.get());

      firstState.applyBuilding(BuildingPreviewSnapshot.inactive());
      first.applyBuildingPreviewResults();
      second.applyBuildingPreviewResults();
      assertEquals(2, firstClears.get());
      assertEquals(1, secondClears.get());

      first.applyBuildingPreviewResults();
      first.resetBuildingPreviewCaches();
      first.applyBuildingPreviewResults();
      assertEquals(3, firstClears.get());
      assertEquals(1, secondClears.get());
   }

   private static BuildingPreviewCache cache(ClientPreviewState state, AtomicInteger clears) {
      return new BuildingPreviewCache(state, () -> false,
         (message, failure) -> { throw new AssertionError(message, failure); }, Runnable::run, clears::incrementAndGet);
   }
}
