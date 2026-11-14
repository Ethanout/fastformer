package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.generation.source.*;
import io.github.fastformer.common.geometry.generation.face.entry.*;
import io.github.fastformer.common.geometry.generation.volume.*;
import io.github.fastformer.common.geometry.generation.shape.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectedFaceBudgetTest {
   @Test
   void boundedFillAndOutlineKeepExistingBlockOrder() {
      for (List<Vec3> vertices : List.of(
         square(8),
         List.of(new Vec3(-4.5, -2.5, 0.5), new Vec3(2.5, 1.5, 2.5),
            new Vec3(0.5, 7.5, 7.5), new Vec3(-6.5, 3.5, 5.5)),
         List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(7.5, 0.5, 1.5), new Vec3(2.5, 4.5, 5.5))
      )) {
         var expected = ProjectedBresenhamFace.raster(vertices);
         var actual = ProjectedBresenhamFace.raster(vertices, expected.fill().size(), null);
         assertFalse(actual.limitExceeded());
         assertEquals(new ArrayList<>(expected.fill()), new ArrayList<>(actual.fill()));
         assertEquals(new ArrayList<>(expected.outline()), new ArrayList<>(actual.outline()));
         var outline = ProjectedBresenhamFace.outline(vertices, expected.outline().size(), null);
         assertFalse(outline.limitExceeded());
         assertEquals(new ArrayList<>(expected.outline()), new ArrayList<>(outline.outline()));
         assertTrue(ProjectedBresenhamFace.raster(vertices, expected.fill().size() - 1, null).limitExceeded());
      }
   }

   @Test
   void projectionCancelsDuringBoundaryTraversal() {
      assertCancelledAfterScans(12);
   }

   @Test
   void projectionCancelsDuringInteriorScan() {
      // Four 33-cell edges are scanned first; cancellation then occurs inside the face.
      assertCancelledAfterScans(150);
   }

   @Test
   void productionPolygonCancelsBeforePublishingAPartialResult() {
      List<Vec3> triangle = List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(32.5, 0.5, 0.5),
         new Vec3(0.5, 0.5, 32.5));
      long[] scanned = {0};
      var observer = new BlockGenerationObserver() {
         @Override public void onScanned(long amount) { scanned[0] += amount; }
         @Override public void checkCancelled() {
            if (scanned[0] >= 120) throw new CancellationException("cancelled inside polygon scan");
         }
         @Override public void onGenerated(BlockPos block) { fail("cancelled candidate must not publish blocks"); }
      };
      assertThrows(CancellationException.class, () -> PlanarFaceRasterizer.generate(triangle, 2_000, observer));
      assertEquals(120, scanned[0]);
   }

   @Test
   void hugeCoordinatesRejectBeforeAllocatingAnEdge() {
      List<Vec3> vertices = List.of(new Vec3(-1_000_000_000.0, 0.5, 0.5),
         new Vec3(1_000_000_000.0, 0.5, 0.5), new Vec3(0.5, 0.5, 1_000_000_000.0));
      var observer = new BlockGenerationObserver() {
         @Override public void onScanned(long amount) { fail("oversized edges must not be traversed"); }
         @Override public void onGenerated(BlockPos block) { fail("rejected work must not publish blocks"); }
      };
      assertTrue(ProjectedBresenhamFace.raster(vertices, 8, observer).limitExceeded());
      assertTrue(ProjectedBresenhamFace.outline(vertices, 8, observer).limitExceeded());
      assertTrue(GenerationLimitExceeded.is(PlanarFaceRasterizer.generate(vertices, 8, observer)));
      assertTrue(GenerationLimitExceeded.is(PlanarFaceRasterizer.outline(vertices, 8, observer)));
   }

   @Test
   void outlineDoesNotGenerateTheInteriorToApplyItsOwnBudget() {
      int side = 10_000;
      long[] scanned = {0};
      var outline = ProjectedBresenhamFace.outline(square(side), 4 * side, new BlockGenerationObserver() {
         @Override public void onScanned(long amount) { scanned[0] += amount; }
      });
      assertFalse(outline.limitExceeded());
      assertEquals(4 * side, outline.outline().size());
      assertEquals(4L * (side + 1), scanned[0]);
      assertTrue(outline.fill().isEmpty());
   }

   @Test
   void interpolatedFallbackPublishesEachFinalBlockOnce() {
      List<Vec3> triangle = List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(5.5, 0.5, 0.5),
         new Vec3(0.5, 0.5, 4.5));
      List<BlockPos> published = new ArrayList<>();
      var blocks = PlanarFaceRasterizer.generateInterpolatedQuad(triangle, 1_000, new BlockGenerationObserver() {
         @Override public void onGenerated(BlockPos block) { published.add(block); }
      }, LineTieBias.DEFAULT);
      assertFalse(blocks.isEmpty());
      assertEquals(new ArrayList<>(blocks), published);
   }

   private static void assertCancelledAfterScans(long stopAt) {
      long[] scanned = {0};
      var observer = new BlockGenerationObserver() {
         @Override public void onScanned(long amount) { scanned[0] += amount; }
         @Override public void checkCancelled() {
            if (scanned[0] >= stopAt) throw new CancellationException("cancelled during raster traversal");
         }
         @Override public void onGenerated(BlockPos block) { fail("candidate must not publish blocks"); }
      };
      assertThrows(CancellationException.class, () -> ProjectedBresenhamFace.raster(square(32), 2_000, observer));
      assertEquals(stopAt, scanned[0]);
   }

   private static List<Vec3> square(int side) {
      return List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(side + 0.5, 0.5, 0.5),
         new Vec3(side + 0.5, 0.5, side + 0.5), new Vec3(0.5, 0.5, side + 0.5));
   }
}
