package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ArbitraryConvexPolyhedronGeneratorTest {
   private static final List<BlockPos> NEIGHBORS = List.of(
      new BlockPos(1, 0, 0),
      new BlockPos(-1, 0, 0),
      new BlockPos(0, 1, 0),
      new BlockPos(0, -1, 0),
      new BlockPos(0, 0, 1),
      new BlockPos(0, 0, -1)
   );

   @Test
   void tetrahedronProducesSolidVolume() {
      List<Vec3> points = tetrahedron(6.0);

      Set<BlockPos> solid = ArbitraryConvexPolyhedronGenerator.generate(points, FillMode.SOLID, 10000);

      assertTrue(ArbitraryConvexPolyhedronGenerator.ready(points));
      assertTrue(solid.contains(new BlockPos(0, 0, 0)));
      assertTrue(solid.contains(new BlockPos(1, 1, 1)));
      assertFalse(solid.contains(new BlockPos(4, 4, 4)));
   }

   @Test
   void coplanarInputIsRejected() {
      List<Vec3> points = List.of(
         center(0, 0, 0),
         center(4, 0, 0),
         center(4, 0, 4),
         center(0, 0, 4),
         center(2, 0, 2)
      );

      assertFalse(ArbitraryConvexPolyhedronGenerator.ready(points));
      assertTrue(ArbitraryConvexPolyhedronGenerator.generate(points, FillMode.SOLID, 1000).isEmpty());
   }

   @Test
   void hollowIsExactlyTheSixNeighborInnerBoundary() {
      List<Vec3> points = tetrahedron(10.0);
      Set<BlockPos> solid = ArbitraryConvexPolyhedronGenerator.generate(points, FillMode.SOLID, 100000);
      Set<BlockPos> hollow = ArbitraryConvexPolyhedronGenerator.generate(points, FillMode.HOLLOW, 100000);

      assertTrue(solid.containsAll(hollow));
      assertTrue(hollow.size() < solid.size());
      assertTrue(hollow.stream().allMatch(position ->
         NEIGHBORS.stream().anyMatch(offset -> !solid.contains(position.offset(offset)))
      ));
      assertTrue(solid.stream().filter(position ->
         NEIGHBORS.stream().anyMatch(offset -> !solid.contains(position.offset(offset)))
      ).allMatch(hollow::contains));
   }

   @Test
   void cubeOutlineContainsOnlyFaceBoundaryEdges() {
      List<Vec3> points = cube(0, 4);

      Set<BlockPos> outline = ArbitraryConvexPolyhedronGenerator.previewOutline(points, 10000);

      assertTrue(outline.contains(new BlockPos(0, 0, 0)));
      assertTrue(outline.contains(new BlockPos(2, 0, 0)));
      assertFalse(outline.contains(new BlockPos(2, 2, 0)));
      assertEquals(25L, ArbitraryConvexPolyhedronGenerator.estimateScanCells(points));
   }

   @Test
   void placementOutlineReportsLimitWhilePreviewRemainsBounded() {
      List<Vec3> points = cube(0, 8);
      Set<BlockPos> complete = ArbitraryConvexPolyhedronGenerator.generate(
         points, FillMode.OUTLINE, 10000
      );

      assertFalse(complete.isEmpty());
      assertEquals(
         complete,
         ArbitraryConvexPolyhedronGenerator.generate(points, FillMode.OUTLINE, complete.size())
      );
      assertTrue(GenerationLimitExceeded.is(
         ArbitraryConvexPolyhedronGenerator.generate(points, FillMode.OUTLINE, complete.size() - 1)
      ));

      Set<BlockPos> preview = ArbitraryConvexPolyhedronGenerator.previewOutline(
         points, complete.size() - 1
      );
      assertFalse(GenerationLimitExceeded.is(preview));
      assertTrue(preview.size() <= complete.size() - 1);
   }

   @Test
   void generationHonorsOutputLimit() {
      Set<BlockPos> solid = ArbitraryConvexPolyhedronGenerator.generate(cube(0, 8), FillMode.SOLID, 17);

      assertEquals(17, solid.size());
   }

   @Test
   void largeSolidBodyUsesLazyStorage() {
      Set<BlockPos> solid = ArbitraryConvexPolyhedronGenerator.generate(cube(0, 32), FillMode.SOLID, 100_000);

      assertEquals(LazyColumnBlockSet.class, solid.getClass());
      assertEquals(solid.size(), new java.util.HashSet<>(solid).size());
   }

   @Test
   void lazyColumnIterationDoesNotWrapAtIntegerBounds() {
      for (int minimum : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE - 1)) {
         Set<BlockPos> solid = ArbitraryConvexPolyhedronGenerator.generate(
            cube(minimum, minimum + 1),
            FillMode.SOLID,
            16
         );

         assertEquals(8, solid.size(), "minimum=" + minimum);
         assertEquals(solid.size(), new java.util.HashSet<>(solid).size(), "minimum=" + minimum);
      }
   }

   @Test
   void solidVisitorMatchesCompleteSolidGeneration() {
      List<Vec3> points = cube(0, 5);
      Set<BlockPos> expected = ArbitraryConvexPolyhedronGenerator.generate(points, FillMode.SOLID, 10000);
      Set<BlockPos> visited = new LinkedHashSet<>();

      assertTrue(ArbitraryConvexPolyhedronGenerator.visitSolid(
         points,
         position -> {
            visited.add(position);
            return true;
         },
         BlockGenerationObserver.NONE
      ));
      assertEquals(expected, visited);
   }

   @Test
   void solidVisitorStopsImmediatelyWhenVisitorRejectsAPosition() {
      int[] visits = {0};

      assertFalse(ArbitraryConvexPolyhedronGenerator.visitSolid(
         cube(0, 5),
         position -> {
            visits[0]++;
            return false;
         },
         BlockGenerationObserver.NONE
      ));
      assertEquals(1, visits[0]);
   }

   @Test
   void solidVisitorPropagatesObserverCancellationDuringScanning() {
      Set<BlockPos> visited = new LinkedHashSet<>();
      int[] checks = {0};
      BlockGenerationObserver observer = new BlockGenerationObserver() {
         @Override
         public void checkCancelled() {
            if (++checks[0] == 2) {
               throw new CancellationException("cancelled by test");
            }
         }
      };

      assertThrows(CancellationException.class, () ->
         ArbitraryConvexPolyhedronGenerator.visitSolid(
            cube(0, 8),
            position -> {
               visited.add(position);
               return true;
            },
            observer
         )
      );
      assertFalse(visited.isEmpty());
      assertEquals(2, checks[0]);
   }

   @Test
   void spanVisitorExpandedAcrossEveryAxisMatchesSolidVisitorForRandomConvexBodies() {
      Random random = new Random(0x5350414E56495349L);
      int checked = 0;
      int attempts = 0;
      while (checked < 128 && attempts++ < 10_000) {
         Vec3 first = randomVector(random);
         Vec3 second = randomVector(random);
         Vec3 third = randomVector(random);
         if (Math.abs(first.cross(second).dot(third)) < 1.0) {
            continue;
         }
         Vec3 origin = center(
            random.nextInt(-6, 7),
            random.nextInt(-6, 7),
            random.nextInt(-6, 7)
         );
         List<Vec3> points = parallelepiped(origin, first, second, third);
         assertSpanExpansionMatchesSolid(points, "random sample=" + checked);
         checked++;
      }
      assertEquals(128, checked);
   }

   @Test
   void spanVisitorMatchesSolidAtExhaustiveSmallIntegerProjectionTies() {
      Vec3 origin = center(0, 0, 0);
      int checked = 0;
      for (int firstShear = -2; firstShear <= 2; firstShear++) {
         for (int secondShear = -2; secondShear <= 2; secondShear++) {
            for (int thirdShear = -2; thirdShear <= 2; thirdShear++) {
               List<Vec3> points = parallelepiped(
                  origin,
                  new Vec3(1.0, 0.0, 0.0),
                  new Vec3(firstShear, 1.0, 0.0),
                  new Vec3(secondShear, thirdShear, 1.0)
               );
               assertSpanExpansionMatchesSolid(
                  points,
                  "integer tie shear=" + firstShear + "," + secondShear + "," + thirdShear
               );
               checked++;
            }
         }
      }
      assertEquals(125, checked);
   }

   @Test
   void sparseProjectionVisitsOnlyLinearWorkForLongThinDiagonalHull() {
      int extent = 500_000;
      List<Vec3> points = parallelepiped(
         center(0, 0, 0),
         new Vec3(1.0, 0.0, 0.0),
         new Vec3(0.0, 1.0, 0.0),
         new Vec3(extent, extent, extent)
      );
      for (int axis = 0; axis < 3; axis++) {
         long[] checks = {0L};
         long[] spans = {0L};
         long[] blocks = {0L};
         BlockGenerationObserver observer = new BlockGenerationObserver() {
            @Override
            public void checkCancelled() {
               checks[0]++;
               assertTrue(
                  checks[0] <= 6L * extent + 100L,
                  "span visitor regressed toward the projected bounding-box area"
               );
            }
         };

         assertTrue(ArbitraryConvexPolyhedronGenerator.visitSolidSpans(
            points,
            axis,
            (first, second, minimum, maximum) -> {
               spans[0]++;
               blocks[0] += (long)maximum - minimum + 1L;
               return true;
            },
            observer
         ));
         assertTrue(checks[0] > 0L, "axis=" + axis);
         assertTrue(spans[0] > 0L, "axis=" + axis);
         assertTrue(blocks[0] > 0L, "axis=" + axis);
         assertTrue(checks[0] <= 6L * extent + 100L, "axis=" + axis + " checks=" + checks[0]);
         assertTrue(spans[0] <= checks[0], "axis=" + axis);
      }
   }

   @Test
   void sparseProjectionCanCancelLongThinDiagonalHullWithoutScanningItsBoundingBox() {
      int[] checks = {0};
      int[] spans = {0};
      List<Vec3> points = parallelepiped(
         center(0, 0, 0),
         new Vec3(1.0, 0.0, 0.0),
         new Vec3(0.0, 1.0, 0.0),
         new Vec3(500_000.0, 500_000.0, 500_000.0)
      );
      BlockGenerationObserver observer = new BlockGenerationObserver() {
         @Override
         public void checkCancelled() {
            if (++checks[0] == 1_000) {
               throw new CancellationException("cancel sparse diagonal scan");
            }
         }
      };

      assertThrows(CancellationException.class, () ->
         ArbitraryConvexPolyhedronGenerator.visitSolidSpans(
            points,
            2,
            (first, second, minimum, maximum) -> {
               spans[0]++;
               return true;
            },
            observer
         )
      );
      assertEquals(1_000, checks[0]);
      assertTrue(spans[0] < checks[0]);
   }

   @Test
   void spanScanEstimateRejectsHugeEarlyAxesBeforeBuildingTheirColumns() {
      int extent = 100_000;
      long stopAfter = 1_000_000L;
      List<Vec3> points = parallelepiped(
         center(0, 0, 0),
         new Vec3(extent, 0.0, 0.0),
         new Vec3(0.0, 1.0, 0.0),
         new Vec3(0.0, extent, extent)
      );
      for (int badAxis : List.of(1, 2)) {
         int[] checks = {0};
         long estimate = ArbitraryConvexPolyhedronGenerator.estimateSolidSpanScanColumns(
            points,
            badAxis,
            stopAfter,
            new BlockGenerationObserver() {
               @Override
               public void checkCancelled() {
                  checks[0]++;
                  assertTrue(checks[0] < 100, "bad axis estimate did not stop after crossing its budget");
               }
            }
         );
         assertEquals(stopAfter + 1L, estimate, "axis=" + badAxis);
         assertTrue(checks[0] < 100, "axis=" + badAxis + " checks=" + checks[0]);
      }

      long viableEstimate = ArbitraryConvexPolyhedronGenerator.estimateSolidSpanScanColumns(
         points,
         0,
         stopAfter,
         BlockGenerationObserver.NONE
      );
      assertTrue(viableEstimate > 0L && viableEstimate <= stopAfter, "viable axis estimate=" + viableEstimate);
   }

   @Test
   void spanVisitorStopsImmediatelyWhenVisitorRejectsAColumn() {
      for (int axis = 0; axis < 3; axis++) {
         int[] visits = {0};
         assertFalse(ArbitraryConvexPolyhedronGenerator.visitSolidSpans(
            cube(0, 5),
            axis,
            (first, second, minimum, maximum) -> {
               visits[0]++;
               return false;
            },
            BlockGenerationObserver.NONE
         ));
         assertEquals(1, visits[0], "axis=" + axis);
      }
   }

   @Test
   void spanVisitorPropagatesCancellationBeforeVisitingTheNextColumn() {
      int[] visits = {0};
      int[] checks = {0};
      BlockGenerationObserver observer = new BlockGenerationObserver() {
         @Override
         public void checkCancelled() {
            if (++checks[0] == 2) {
               throw new CancellationException("cancelled by span test");
            }
         }
      };

      assertThrows(CancellationException.class, () ->
         ArbitraryConvexPolyhedronGenerator.visitSolidSpans(
            cube(0, 5),
            2,
            (first, second, minimum, maximum) -> {
               visits[0]++;
               return true;
            },
            observer
         )
      );
      assertEquals(1, visits[0]);
      assertEquals(2, checks[0]);
   }

   @Test
   void spanVisitorProjectionLoopsDoNotWrapAtIntegerBounds() {
      for (int minimum : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE - 1)) {
         List<Vec3> points = cube(minimum, minimum + 1);
         for (int axis = 0; axis < 3; axis++) {
            int[] checks = {0};
            int[] spans = {0};
            long[] blocks = {0L};
            BlockGenerationObserver observer = new BlockGenerationObserver() {
               @Override
               public void checkCancelled() {
                  assertTrue(++checks[0] <= 4, "projection loop wrapped past its four cells");
               }
            };
            assertTrue(ArbitraryConvexPolyhedronGenerator.visitSolidSpans(
               points,
               axis,
               (first, second, spanMinimum, spanMaximum) -> {
                  spans[0]++;
                  blocks[0] += (long)spanMaximum - spanMinimum + 1L;
                  return true;
               },
               observer
            ));
            assertEquals(4, checks[0], "axis=" + axis + " minimum=" + minimum);
            assertEquals(4, spans[0], "axis=" + axis + " minimum=" + minimum);
            assertEquals(8L, blocks[0], "axis=" + axis + " minimum=" + minimum);
         }
      }
   }

   private static List<Vec3> tetrahedron(double size) {
      return List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(0.5 + size, 0.5, 0.5),
         new Vec3(0.5, 0.5 + size, 0.5),
         new Vec3(0.5, 0.5, 0.5 + size)
      );
   }

   private static List<Vec3> cube(int minimum, int maximum) {
      return List.of(
         center(minimum, minimum, minimum),
         center(maximum, minimum, minimum),
         center(minimum, maximum, minimum),
         center(maximum, maximum, minimum),
         center(minimum, minimum, maximum),
         center(maximum, minimum, maximum),
         center(minimum, maximum, maximum),
         center(maximum, maximum, maximum)
      );
   }

   private static List<Vec3> parallelepiped(Vec3 origin, Vec3 first, Vec3 second, Vec3 third) {
      return List.of(
         origin,
         origin.add(first),
         origin.add(second),
         origin.add(first).add(second),
         origin.add(third),
         origin.add(first).add(third),
         origin.add(second).add(third),
         origin.add(first).add(second).add(third)
      );
   }

   private static Vec3 randomVector(Random random) {
      return new Vec3(
         random.nextInt(-4, 5),
         random.nextInt(-4, 5),
         random.nextInt(-4, 5)
      );
   }

   private static BlockPos columnPosition(int axis, int first, int second, int coordinate) {
      int[] values = new int[3];
      values[axis] = coordinate;
      values[(axis + 1) % 3] = first;
      values[(axis + 2) % 3] = second;
      return new BlockPos(values[0], values[1], values[2]);
   }

   private static void assertSpanExpansionMatchesSolid(List<Vec3> points, String name) {
      LinkedHashSet<BlockPos> expected = new LinkedHashSet<>();
      assertTrue(ArbitraryConvexPolyhedronGenerator.visitSolid(
         points,
         position -> {
            expected.add(position);
            return true;
         },
         BlockGenerationObserver.NONE
      ), name);
      for (int axis = 0; axis < 3; axis++) {
         int visitedAxis = axis;
         LinkedHashSet<BlockPos> actual = new LinkedHashSet<>();
         long[] callbacks = {0L};
         assertTrue(ArbitraryConvexPolyhedronGenerator.visitSolidSpans(
            points,
            axis,
            (first, second, minimum, maximum) -> {
               callbacks[0]++;
               for (long coordinate = minimum; coordinate <= maximum; coordinate++) {
                  actual.add(columnPosition(visitedAxis, first, second, (int)coordinate));
               }
               return true;
            },
            BlockGenerationObserver.NONE
         ), name + " axis=" + axis);
         assertEquals(expected, actual, name + " axis=" + axis);
         long estimate = ArbitraryConvexPolyhedronGenerator.estimateSolidSpanScanColumns(
            points,
            axis,
            Long.MAX_VALUE,
            BlockGenerationObserver.NONE
         );
         assertTrue(estimate >= callbacks[0], name + " axis=" + axis + " estimate=" + estimate);
      }
   }

   private static Vec3 center(int x, int y, int z) {
      return new Vec3(x + 0.5, y + 0.5, z + 0.5);
   }
}
