package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BresenhamFaceSweepTest {
   private static final int[][] AXIS_ORDERS = {
      {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
   };

   @Test
   void acRegressionUsesTheFourOwnedLinesWithoutRasterLiftDetours() {
      List<BlockPos> vertices = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(-2, -1, 0),
         new BlockPos(-2, -2, -1),
         new BlockPos(0, -1, -1)
      );

      BresenhamFaceSweep.Result result = generate(vertices);

      assertContract(vertices, result);
      assertTrue(result.outline().contains(new BlockPos(-1, 0, 0)), "the owned A/AB line voxel was lost");
      assertFalse(result.outline().contains(new BlockPos(-1, -2, -2)), "an AC raster-lift detour escaped the owned lines");
      assertEveryOwnedLineUsesNestedSlopeSteps(vertices);
   }

   @Test
   void steepEdgeMayOwnTwoHeightsAndPhysicalTransitionsStayContiguous() {
      List<BlockPos> vertices = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(4, -3, -3),
         new BlockPos(4, -2, -4),
         new BlockPos(0, 1, -1)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      BresenhamFaceSweep.Result result = BresenhamFaceSweep.generate(frame);

      assertContract(vertices, frame, result);
      BlockPos lower = new BlockPos(2, -2, -2);
      BlockPos upper = new BlockPos(3, -2, -2);
      assertTrue(result.outline().contains(lower));
      assertTrue(result.outline().contains(upper));
      assertEquals(project(frame, lower), project(frame, upper), "the regression must exercise a two-height owned column");
   }

   @Test
   void coordinatePlaneOutlineIsExactlyTheFourOwnedLines() {
      List<BlockPos> vertices = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(-2, -1, 0),
         new BlockPos(-3, -1, 0),
         new BlockPos(-1, 0, 0)
      );

      BresenhamFaceSweep.Result result = generate(vertices);

      assertContract(vertices, result);
      assertTrue(result.outline().contains(new BlockPos(-2, 0, 0)));
      assertFalse(result.outline().contains(new BlockPos(-1, -1, 0)));
   }

   @Test
   void coordinatePlaneRectangleIsTheCompleteParameterGrid() {
      List<BlockPos> vertices = parallelogram(
         BlockPos.ZERO,
         new BlockPos(4, 0, 0),
         new BlockPos(0, 3, 0)
      );
      LinkedHashSet<BlockPos> expected = new LinkedHashSet<>();
      for (int x = 0; x <= 4; x++) {
         for (int y = 0; y <= 3; y++) {
            expected.add(new BlockPos(x, y, 0));
         }
      }

      BresenhamFaceSweep.Result result = generate(vertices);

      assertEquals(expected, result.fill());
      assertEquals(ownedOutline(vertices), result.outline());
   }

   @Test
   void equalEdgeStepPairsProduceEqualTranslatedMicroTilePatterns() {
      List<BlockPos> vertices = parallelogram(
         new BlockPos(2, -1, 3),
         new BlockPos(19, 3, 2),
         new BlockPos(1, -7, 1)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      List<BlockPos> canonical = frame.vertices().stream().map(frame::restore).toList();
      BlockPos origin = canonical.get(0);
      List<BlockPos> edgeU = orientedPath(canonical.get(0), canonical.get(1));
      List<BlockPos> edgeV = orientedPath(canonical.get(0), canonical.get(3));
      Map<StepPair, Set<BlockPos>> patterns = new HashMap<>();
      BresenhamFaceSweep.Result face = BresenhamFaceSweep.generate(frame);

      for (int j = 0; j + 1 < edgeV.size(); j++) {
         for (int i = 0; i + 1 < edgeU.size(); i++) {
            BlockPos p00 = translatedGridPoint(origin, edgeU.get(i), edgeV.get(j));
            BlockPos p10 = translatedGridPoint(origin, edgeU.get(i + 1), edgeV.get(j));
            BlockPos p01 = translatedGridPoint(origin, edgeU.get(i), edgeV.get(j + 1));
            BlockPos p11 = translatedGridPoint(origin, edgeU.get(i + 1), edgeV.get(j + 1));
            Set<BlockPos> tile = TranslatedScanFaceRasterizer.fillMicroTileForTesting(
               frame,
               List.of(p00, p10, p11, p01)
            );
            assertTrue(face.fill().containsAll(tile), "final face lost a periodic micro-tile");
            StepPair key = new StepPair(delta(p00, p10), delta(p00, p01));
            Set<BlockPos> normalized = tile.stream().map(point -> delta(p00, point)).collect(java.util.stream.Collectors.toSet());
            Set<BlockPos> previous = patterns.putIfAbsent(key, normalized);
            if (previous != null) {
               assertEquals(previous, normalized, "equal mechanical step events changed their local pattern");
            }
         }
      }
      assertTrue(patterns.size() > 1, "the test rectangle did not exercise multiple step motifs");
   }

   @Test
   void fixedPeriodicMicroTilesKeepExactBoundaryAndClosePhysicalSeams() {
      List<BlockPos> vertices = List.of(
         new BlockPos(282, 63, 127),
         new BlockPos(279, 70, 131),
         new BlockPos(273, 70, 126),
         new BlockPos(276, 63, 122)
      );

      BresenhamFaceSweep.Result result = generate(vertices);

      assertContract(vertices, result);
      assertEquals(87, result.fill().size(), "fixed equal-height section fill changed unexpectedly");
      assertTrue(
         result.fill().containsAll(Set.of(
            new BlockPos(274, 69, 126),
            new BlockPos(275, 67, 125),
            new BlockPos(281, 64, 127)
         )),
         "the exact boundary spans were lost"
      );
   }

   @Test
   void periodicMicroTilesMatchTheFixedReference() {
      List<BlockPos> vertices = List.of(
         new BlockPos(282, 63, 127),
         new BlockPos(279, 70, 131),
         new BlockPos(273, 70, 126),
         new BlockPos(276, 63, 122)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);

      BresenhamFaceSweep.Result result = TranslatedScanFaceRasterizer.generate(frame, LineTieBias.DEFAULT);

      assertEquals(ownedOutline(vertices), result.outline());
      assertTrue(result.fill().containsAll(result.outline()));
      assertEquals(87, result.fill().size());
      assertEquals(56, columnHeights(frame, result.fill()).size());
      assertTrue(result.fill().containsAll(Set.of(
         new BlockPos(274, 69, 125),
         new BlockPos(275, 67, 124),
         new BlockPos(281, 64, 128)
      )));
      assertLowerAndUpperAirAreSeparated(frame, result.fill());
   }

   @Test
   void fixedFaceLimitIsAtomicAndExactLimitStillSucceeds() {
      List<BlockPos> vertices = List.of(
         new BlockPos(282, 63, 127),
         new BlockPos(279, 70, 131),
         new BlockPos(273, 70, 126),
         new BlockPos(276, 63, 122)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);

      BresenhamFaceSweep.Result full = BresenhamFaceSweep.generate(frame);
      BresenhamFaceSweep.Attempt limited = BresenhamFaceSweep.attempt(
         frame,
         LineTieBias.DEFAULT,
         ownedOutline(vertices).size() - 1,
         BlockGenerationObserver.NONE
      );
      BresenhamFaceSweep.Attempt exact = BresenhamFaceSweep.attempt(
         frame,
         LineTieBias.DEFAULT,
         full.fill().size(),
         BlockGenerationObserver.NONE
      );

      assertEquals(BresenhamFaceSweep.Status.LIMIT_EXCEEDED, limited.status());
      assertTrue(limited.result().fill().isEmpty(), "a low-level limit must not expose a placeable prefix");
      assertEquals(BresenhamFaceSweep.Status.SUCCESS, exact.status());
      assertEquals(full.fill(), exact.result().fill());
      assertEquals(ownedOutline(vertices), exact.result().outline());
   }

   @Test
   void periodicMicroTileEnumerationPropagatesCancellation() {
      List<BlockPos> vertices = parallelogram(
         BlockPos.ZERO,
         new BlockPos(180, 27, 19),
         new BlockPos(1, -13, 9)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      int[] checks = {0};
      BlockGenerationObserver observer = new BlockGenerationObserver() {
         @Override
         public void checkCancelled() {
            if (++checks[0] == 5) {
               throw new CancellationException("cancel periodic micro-tile test");
            }
         }
      };

      assertThrows(CancellationException.class, () -> BresenhamFaceSweep.attempt(
         frame,
         LineTieBias.DEFAULT,
         100_000,
         observer
      ));
   }

   @Test
   void forcedEmergencyFallbackStillReturnsAnExactOwnedSeparator() {
      List<BlockPos> vertices = List.of(
         new BlockPos(282, 63, 127),
         new BlockPos(279, 70, 131),
         new BlockPos(273, 70, 126),
         new BlockPos(276, 63, 122)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);

      BresenhamFaceSweep.Attempt attempt = TranslatedScanFaceRasterizer.forceEmergencyFallbackForTesting(
         frame,
         LineTieBias.DEFAULT,
         1_000
      );

      assertEquals(BresenhamFaceSweep.Status.SUCCESS, attempt.status());
      assertEquals(ownedOutline(vertices), attempt.result().outline());
      assertTrue(attempt.result().fill().containsAll(attempt.result().outline()));
      assertProjectedDomainHasNoEnclosedHole(frame, attempt.result().fill());
      assertColumnsContainNoHeightGap(frame, attempt.result().fill());
      assertLowerAndUpperAirAreSeparated(frame, attempt.result().fill());
   }

   @Test
   void rawPairThresholdUsesAnalyticFallbackInsteadOfFailingTheFace() {
      List<BlockPos> vertices = parallelogram(
         BlockPos.ZERO,
         new BlockPos(2500, 2500, 0),
         new BlockPos(-2500, -2499, 1)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);

      BresenhamFaceSweep.Attempt attempt = BresenhamFaceSweep.attempt(
         frame,
         LineTieBias.DEFAULT,
         20_000,
         BlockGenerationObserver.NONE
      );

      assertEquals(BresenhamFaceSweep.Status.SUCCESS, attempt.status());
      assertEquals(ownedOutline(vertices), attempt.result().outline());
      assertTrue(attempt.result().fill().containsAll(attempt.result().outline()));
      assertTrue(attempt.result().fill().size() <= 20_000);
      assertColumnsContainNoHeightGap(frame, attempt.result().fill());
      assertLowerAndUpperAirAreSeparated(frame, attempt.result().fill());
   }

   @Test
   void projectedLatticeGeometryUsesExactOutputSensitiveArithmetic() {
      int span = 1_000_000_000;
      List<BlockPos> unimodular = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(span, span + 1, 0),
         new BlockPos(1, 1, 0),
         new BlockPos(-span + 1, -span, 0)
      );

      assertEquals(
         Set.copyOf(unimodular),
         TranslatedScanFaceRasterizer.scanConvexDomainForTesting(unimodular, 4),
         "a billion-row bounding box with four lattice points must be enumerated by output, not by rows"
      );

      int minimum = Integer.MIN_VALUE;
      int maximum = Integer.MAX_VALUE;
      List<BlockPos> fullRangeSquare = List.of(
         new BlockPos(minimum, minimum, 0),
         new BlockPos(maximum, minimum, 0),
         new BlockPos(maximum, maximum, 0),
         new BlockPos(minimum, maximum, 0)
      );
      assertTrue(
         TranslatedScanFaceRasterizer.insideConvexForTesting(
            new BlockPos(0, maximum, 0),
            fullRangeSquare
         ),
         "an exact boundary point was rejected after a signed-long cross-product overflow"
      );
   }

   @Test
   void projectedHoleCompletionBudgetsOnlyEmittedColumns() {
      int span = 1_998;
      LinkedHashSet<BlockPos> openCorner = new LinkedHashSet<>();
      for (int u = 0; u <= span; u++) {
         openCorner.add(new BlockPos(u, 0, 0));
      }
      for (int v = 1; v <= span; v++) {
         openCorner.add(new BlockPos(span, v, 0));
      }
      assertEquals(
         openCorner,
         TranslatedScanFaceRasterizer.completeProjectedDomainForTesting(openCorner, openCorner.size()),
         "the one-cell flood frame must not consume the fixed four-million-cell scan budget"
      );

      LinkedHashSet<BlockPos> ring = new LinkedHashSet<>();
      for (int u = 0; u < 3; u++) {
         for (int v = 0; v < 3; v++) {
            if (u == 0 || u == 2 || v == 0 || v == 2) {
               ring.add(new BlockPos(u, v, 0));
            }
         }
      }
      Set<BlockPos> completed = TranslatedScanFaceRasterizer.completeProjectedDomainForTesting(ring, 9);
      assertEquals(9, completed.size());
      assertTrue(completed.contains(new BlockPos(1, 1, 0)));
      assertTrue(
         TranslatedScanFaceRasterizer.completeProjectedDomainForTesting(ring, 8).isEmpty(),
         "a real emitted-column overrun must remain atomic"
      );
   }

   @Test
   void threeHighOneLowUsesOneFaceWideSupportSideInsteadOfALocalSlab() {
      List<BlockPos> vertices = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(2, 0, 1),
         new BlockPos(2, 2, 2),
         new BlockPos(0, 2, 1)
      );

      BresenhamFaceSweep.Result result = generate(vertices);

      assertContract(vertices, result);
      Set<BlockPos> oldSymmetricSlab = new HashSet<>();
      for (int x = 0; x <= 1; x++) {
         for (int y = 0; y <= 1; y++) {
            for (int z = 0; z <= 1; z++) {
               oldSymmetricSlab.add(new BlockPos(x, y, z));
            }
         }
      }
      assertFalse(result.fill().containsAll(oldSymmetricSlab), "local 2x2x2 slab returned");
   }

   @Test
   void signedAxisFramesSatisfyTheContractAndVertexWindingKeepsTheExactVoxelSet() {
      Random random = new Random(0x4252455346414345L);
      for (int sample = 0; sample < 12; sample++) {
         BlockPos origin = randomPoint(random);
         BlockPos first;
         BlockPos second;
         do {
            first = randomVector(random);
            second = randomVector(random);
         } while (cross(first, second).equals(BlockPos.ZERO));
         List<BlockPos> vertices = parallelogram(origin, first, second);
         BresenhamFaceSweep.Result baseline = generate(vertices);
         assertContract(vertices, baseline);

         for (int[] order : AXIS_ORDERS) {
            for (int mask = 0; mask < 8; mask++) {
               int[] signs = {
                  (mask & 1) == 0 ? -1 : 1,
                  (mask & 2) == 0 ? -1 : 1,
                  (mask & 4) == 0 ? -1 : 1
               };
               List<BlockPos> transformedVertices = vertices.stream()
                  .map(position -> transform(position, order, signs))
                  .toList();
               BresenhamFaceSweep.Result transformed = generate(transformedVertices);
               String transformName = "sample=" + sample
                  + " order=" + java.util.Arrays.toString(order)
                  + " signs=" + java.util.Arrays.toString(signs);

               assertContract(transformedVertices, transformed);

               for (int start = 0; start < 4; start++) {
                  for (int direction : new int[]{1, -1}) {
                     List<BlockPos> reordered = reorder(transformedVertices, start, direction);
                     BresenhamFaceSweep.Result reorderedResult = generate(reordered);
                     String orderName = transformName + " start=" + start + " direction=" + direction;
                     assertEquals(transformed.fill(), reorderedResult.fill(), orderName + " fill");
                     assertEquals(transformed.outline(), reorderedResult.outline(), orderName + " outline");
                  }
               }
            }
         }
      }
   }

   @Test
   void tieFreeFaceIsExactlyEquivariantUnderAllSignedAxisTransforms() {
      List<BlockPos> vertices = parallelogram(
         new BlockPos(2, -1, 3),
         new BlockPos(19, 3, 2),
         new BlockPos(1, -7, 1)
      );
      BresenhamFaceSweep.Result baseline = generate(vertices);
      for (int[] order : AXIS_ORDERS) {
         for (int mask = 0; mask < 8; mask++) {
            int[] signs = {
               (mask & 1) == 0 ? -1 : 1,
               (mask & 2) == 0 ? -1 : 1,
               (mask & 4) == 0 ? -1 : 1
            };
            List<BlockPos> transformedVertices = vertices.stream()
               .map(position -> transform(position, order, signs))
               .toList();
            BresenhamFaceSweep.Result transformed = generate(transformedVertices);
            assertEquals(
               baseline.fill().stream().map(position -> transform(position, order, signs)).collect(java.util.stream.Collectors.toSet()),
               transformed.fill(),
               "fill order=" + java.util.Arrays.toString(order) + " signs=" + java.util.Arrays.toString(signs)
            );
            assertEquals(
               baseline.outline().stream().map(position -> transform(position, order, signs)).collect(java.util.stream.Collectors.toSet()),
               transformed.outline(),
               "outline order=" + java.util.Arrays.toString(order) + " signs=" + java.util.Arrays.toString(signs)
            );
         }
      }
   }

   private static void assertContract(List<BlockPos> vertices, BresenhamFaceSweep.Result result) {
      assertContract(vertices, frame(vertices), result);
   }

   private static void assertContract(
      List<BlockPos> vertices,
      ProjectedBresenhamFace.Frame frame,
      BresenhamFaceSweep.Result result
   ) {
      Set<BlockPos> expectedOutline = ownedOutline(vertices);
      assertEquals(expectedOutline, result.outline(), "outline must be the four owned LineGenerator paths");
      assertTrue(result.fill().containsAll(result.outline()), "fill lost an owned outline voxel");
      assertProjectedDomainIsFourConnected(frame, result.fill());
      assertProjectedDomainHasNoEnclosedHole(frame, result.fill());
      assertColumnsContainNoHeightGap(frame, result.fill());
      assertNonOutlineColumnsAreAtMostTwoLayers(frame, result.fill(), result.outline());
      assertLowerAndUpperAirAreSeparated(frame, result.fill());
      assertFillIsSixNeighborConnected(result.fill());
      assertOutlineIsUnbroken26NeighborLoop(vertices, result.outline());
      assertEveryOwnedLineUsesNestedSlopeSteps(vertices);
   }

   private static BresenhamFaceSweep.Result generate(List<BlockPos> vertices) {
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      return BresenhamFaceSweep.generate(frame);
   }

   private static ProjectedBresenhamFace.Frame frame(List<BlockPos> vertices) {
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(
         vertices.stream().map(Vec3::atCenterOf).toList()
      );
      assertNotNull(frame, "non-degenerate parallelogram must have a projection frame");
      return frame;
   }

   private static Set<BlockPos> ownedOutline(List<BlockPos> vertices) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int index = 0; index < vertices.size(); index++) {
         result.addAll(LineGenerator.path(vertices.get(index), vertices.get((index + 1) % vertices.size())));
      }
      return result;
   }

   private static void assertEveryOwnedLineUsesNestedSlopeSteps(List<BlockPos> vertices) {
      for (int edge = 0; edge < vertices.size(); edge++) {
         BlockPos from = vertices.get(edge);
         BlockPos to = vertices.get((edge + 1) % vertices.size());
         List<BlockPos> path = LineGenerator.path(from, to);
         int[] axes = axesByDescendingSlope(from, to);
         for (int index = 1; index < path.size(); index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            Set<Integer> changed = changedAxes(previous, current);
            assertTrue(changed.contains(axes[0]), "dominant A axis did not advance: " + path);
            assertFalse(
               changed.contains(axes[2]) && !changed.contains(axes[1]),
               "minor C advanced without middle B: " + path
            );
            assertTrue(
               changed.equals(Set.of(axes[0]))
                  || changed.equals(Set.of(axes[0], axes[1]))
                  || changed.equals(Set.of(axes[0], axes[1], axes[2])),
               "step was not A, AB, or ABC: " + changed + " in " + path
            );
         }
      }
   }

   private static int[] axesByDescendingSlope(BlockPos from, BlockPos to) {
      long[] distance = {
         Math.abs((long)to.getX() - from.getX()),
         Math.abs((long)to.getY() - from.getY()),
         Math.abs((long)to.getZ() - from.getZ())
      };
      int[] axes = {0, 1, 2};
      for (int index = 1; index < axes.length; index++) {
         int axis = axes[index];
         int previous = index - 1;
         while (previous >= 0 && distance[axis] > distance[axes[previous]]) {
            axes[previous + 1] = axes[previous];
            previous--;
         }
         axes[previous + 1] = axis;
      }
      return axes;
   }

   private static Set<Integer> changedAxes(BlockPos first, BlockPos second) {
      HashSet<Integer> result = new HashSet<>();
      if (first.getX() != second.getX()) {
         result.add(0);
      }
      if (first.getY() != second.getY()) {
         result.add(1);
      }
      if (first.getZ() != second.getZ()) {
         result.add(2);
      }
      return result;
   }

   private static void assertProjectedDomainHasNoEnclosedHole(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill
   ) {
      Set<Column> occupied = new HashSet<>();
      fill.forEach(position -> occupied.add(project(frame, position)));
      assertFalse(occupied.isEmpty());
      int minimumU = occupied.stream().mapToInt(Column::u).min().orElseThrow() - 1;
      int maximumU = occupied.stream().mapToInt(Column::u).max().orElseThrow() + 1;
      int minimumV = occupied.stream().mapToInt(Column::v).min().orElseThrow() - 1;
      int maximumV = occupied.stream().mapToInt(Column::v).max().orElseThrow() + 1;
      HashSet<Column> exterior = new HashSet<>();
      ArrayDeque<Column> open = new ArrayDeque<>();
      open.add(new Column(minimumU, minimumV));
      while (!open.isEmpty()) {
         Column current = open.removeFirst();
         if (!exterior.add(current)) {
            continue;
         }
         for (int u = -1; u <= 1; u++) {
            for (int v = -1; v <= 1; v++) {
               if (u == 0 && v == 0) {
                  continue;
               }
               Column neighbor = new Column(current.u() + u, current.v() + v);
               if (neighbor.u() >= minimumU && neighbor.u() <= maximumU
                  && neighbor.v() >= minimumV && neighbor.v() <= maximumV
                  && !occupied.contains(neighbor)
                  && !exterior.contains(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
      }

      LinkedHashSet<Column> holes = new LinkedHashSet<>();
      for (int u = minimumU; u <= maximumU; u++) {
         for (int v = minimumV; v <= maximumV; v++) {
            Column column = new Column(u, v);
            if (!occupied.contains(column) && !exterior.contains(column)) {
               holes.add(column);
            }
         }
      }
      assertTrue(holes.isEmpty(), () -> "enclosed projected holes " + holes);
   }

   private static void assertProjectedDomainIsFourConnected(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill
   ) {
      Set<Column> unseen = new HashSet<>();
      fill.forEach(position -> unseen.add(project(frame, position)));
      assertFalse(unseen.isEmpty());
      ArrayDeque<Column> open = new ArrayDeque<>();
      Column first = unseen.iterator().next();
      unseen.remove(first);
      open.add(first);
      while (!open.isEmpty()) {
         Column current = open.removeFirst();
         for (Column neighbor : fourNeighbors(current)) {
            if (unseen.remove(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      assertTrue(unseen.isEmpty(), () -> "projected domain has disconnected columns " + unseen);
   }

   private static void assertColumnsContainNoHeightGap(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill
   ) {
      Map<Column, Set<Integer>> heights = new HashMap<>();
      for (BlockPos position : fill) {
         heights.computeIfAbsent(project(frame, position), ignored -> new HashSet<>())
            .add(localHeight(frame, position));
      }
      assertTrue(
         heights.values().stream().allMatch(column ->
            column.size() == column.stream().mapToInt(Integer::intValue).max().orElseThrow()
               - column.stream().mapToInt(Integer::intValue).min().orElseThrow() + 1
         ),
         () -> "transition column contains an internal height gap " + heights
      );
   }

   private static void assertNonOutlineColumnsAreAtMostTwoLayers(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill,
      Set<BlockPos> outline
   ) {
      Set<Column> outlineColumns = new HashSet<>();
      outline.forEach(position -> outlineColumns.add(project(frame, position)));
      Map<Column, Set<Integer>> heights = columnHeights(frame, fill);
      assertTrue(
         heights.entrySet().stream()
            .filter(entry -> !outlineColumns.contains(entry.getKey()))
            .allMatch(entry -> entry.getValue().size() <= 2),
         () -> "non-outline support exceeded two layers: " + heights.entrySet().stream()
            .filter(entry -> !outlineColumns.contains(entry.getKey()) && entry.getValue().size() > 2)
            .toList()
      );
   }

   private static void assertNoEnclosedEnvelopeBasin(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill
   ) {
      Map<Column, Set<Integer>> heights = columnHeights(frame, fill);
      Map<Column, Integer> upper = new HashMap<>();
      Map<Column, Integer> lower = new HashMap<>();
      heights.forEach((column, values) -> {
         upper.put(column, values.stream().mapToInt(Integer::intValue).max().orElseThrow());
         lower.put(column, values.stream().mapToInt(Integer::intValue).min().orElseThrow());
      });
      Set<Column> upperBasins = enclosedBasinCells(upper, true);
      Set<Column> lowerBasins = enclosedBasinCells(lower, false);
      assertTrue(upperBasins.isEmpty(), () -> "upper envelope has a volcano basin " + upperBasins);
      assertTrue(lowerBasins.isEmpty(), () -> "lower envelope has a volcano basin " + lowerBasins);
   }

   private static Map<Column, Set<Integer>> columnHeights(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill
   ) {
      Map<Column, Set<Integer>> heights = new HashMap<>();
      for (BlockPos position : fill) {
         heights.computeIfAbsent(project(frame, position), ignored -> new HashSet<>())
            .add(localHeight(frame, position));
      }
      return heights;
   }

   private static void assertEveryNonOwnedVoxelTouchesItsAnalyticHeightBand(
      ProjectedBresenhamFace.Frame frame,
      BresenhamFaceSweep.Result result
   ) {
      for (BlockPos position : result.fill()) {
         if (result.outline().contains(position)) {
            continue;
         }
         Column column = project(frame, position);
         assertTrue(
            Math.abs(frame.height(column.u(), column.v()) - localHeight(frame, position)) <= 1,
            "a non-owned connector escaped the layer adjacent to its analytic height band"
         );
      }
   }

   private static Set<Column> enclosedBasinCells(
      Map<Column, Integer> envelope,
      boolean upper
   ) {
      Set<Column> domain = envelope.keySet();
      Set<Column> boundary = new HashSet<>();
      for (Column column : domain) {
         if (fourNeighbors(column).stream().anyMatch(neighbor -> !domain.contains(neighbor))) {
            boundary.add(column);
         }
      }
      int minimum = envelope.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
      int maximum = envelope.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
      Set<Column> result = new HashSet<>();
      for (int threshold = minimum; threshold <= maximum; threshold++) {
         Set<Column> unseen = new HashSet<>();
         for (Map.Entry<Column, Integer> entry : envelope.entrySet()) {
            if (upper ? entry.getValue() < threshold : entry.getValue() > threshold) {
               unseen.add(entry.getKey());
            }
         }
         while (!unseen.isEmpty()) {
            Column start = unseen.iterator().next();
            unseen.remove(start);
            Set<Column> component = new HashSet<>();
            ArrayDeque<Column> open = new ArrayDeque<>();
            open.add(start);
            while (!open.isEmpty()) {
               Column current = open.removeFirst();
               if (!component.add(current)) {
                  continue;
               }
               for (Column neighbor : fourNeighbors(current)) {
                  if (unseen.remove(neighbor)) {
                     open.addLast(neighbor);
                  }
               }
            }
            if (component.stream().noneMatch(boundary::contains)) {
               result.addAll(component);
            }
         }
      }
      return result;
   }

   private static List<Column> fourNeighbors(Column column) {
      return List.of(
         new Column(column.u() + 1, column.v()),
         new Column(column.u() - 1, column.v()),
         new Column(column.u(), column.v() + 1),
         new Column(column.u(), column.v() - 1)
      );
   }

   private static void assertFillIsSixNeighborConnected(Set<BlockPos> fill) {
      assertFalse(fill.isEmpty());
      HashSet<BlockPos> visited = new HashSet<>();
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      open.add(fill.iterator().next());
      while (!open.isEmpty()) {
         BlockPos current = open.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (BlockPos offset : List.of(
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
         )) {
            BlockPos neighbor = current.offset(offset);
            if (fill.contains(neighbor) && !visited.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      assertEquals(fill.size(), visited.size(), "physical transition surface is not six-neighbor connected");
   }

   private static void assertLowerAndUpperAirAreSeparated(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill
   ) {
      Map<Column, Set<Integer>> heights = columnHeights(frame, fill);
      int minimum = heights.values().stream().flatMap(Set::stream).mapToInt(Integer::intValue).min().orElseThrow() - 2;
      int maximum = heights.values().stream().flatMap(Set::stream).mapToInt(Integer::intValue).max().orElseThrow() + 2;
      Set<AirCell> solid = new HashSet<>();
      heights.forEach((column, values) -> values.forEach(height -> solid.add(new AirCell(column, height))));
      ArrayDeque<AirCell> open = new ArrayDeque<>();
      Set<AirCell> visited = new HashSet<>();
      for (Column column : heights.keySet()) {
         AirCell start = new AirCell(column, minimum);
         if (!solid.contains(start) && visited.add(start)) {
            open.addLast(start);
         }
      }
      while (!open.isEmpty()) {
         AirCell current = open.removeFirst();
         assertTrue(current.height() < maximum, "lower air reached upper air through " + current);
         for (int delta : new int[]{-1, 1}) {
            int height = current.height() + delta;
            if (height < minimum || height > maximum) {
               continue;
            }
            AirCell next = new AirCell(current.column(), height);
            if (!solid.contains(next) && visited.add(next)) {
               open.addLast(next);
            }
         }
         for (Column neighbor : fourNeighbors(current.column())) {
            if (!heights.containsKey(neighbor)) {
               continue;
            }
            AirCell next = new AirCell(neighbor, current.height());
            if (!solid.contains(next) && visited.add(next)) {
               open.addLast(next);
            }
         }
      }
   }

   private static int localHeight(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return Math.toIntExact(delta[frame.order()[2]] * frame.signs()[2]);
   }

   private static void assertOutlineIsUnbroken26NeighborLoop(List<BlockPos> vertices, Set<BlockPos> outline) {
      assertTrue(outline.containsAll(vertices), "outline lost a geometry corner");
      for (int edge = 0; edge < vertices.size(); edge++) {
         List<BlockPos> path = LineGenerator.path(vertices.get(edge), vertices.get((edge + 1) % vertices.size()));
         for (int index = 1; index < path.size(); index++) {
            assertTrue(are26Neighbors(path.get(index - 1), path.get(index)), "owned edge is interrupted: " + path);
         }
      }

      HashSet<BlockPos> visited = new HashSet<>();
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      open.add(outline.iterator().next());
      while (!open.isEmpty()) {
         BlockPos current = open.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
               for (int z = -1; z <= 1; z++) {
                  if (x == 0 && y == 0 && z == 0) {
                     continue;
                  }
                  BlockPos neighbor = current.offset(x, y, z);
                  if (outline.contains(neighbor) && !visited.contains(neighbor)) {
                     open.addLast(neighbor);
                  }
               }
            }
         }
      }
      assertEquals(outline.size(), visited.size(), "outline has disconnected 26-neighbor components");
   }

   private static boolean are26Neighbors(BlockPos first, BlockPos second) {
      int x = Math.abs(first.getX() - second.getX());
      int y = Math.abs(first.getY() - second.getY());
      int z = Math.abs(first.getZ() - second.getZ());
      return Math.max(x, Math.max(y, z)) == 1;
   }

   private static Column project(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return new Column(
         Math.toIntExact(delta[frame.order()[0]] * frame.signs()[0]),
         Math.toIntExact(delta[frame.order()[1]] * frame.signs()[1])
      );
   }

   private static List<BlockPos> parallelogram(BlockPos origin, BlockPos first, BlockPos second) {
      return List.of(origin, origin.offset(first), origin.offset(first).offset(second), origin.offset(second));
   }

   private static List<BlockPos> orientedPath(BlockPos from, BlockPos to) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(from, to));
      if (!result.getFirst().equals(from)) {
         java.util.Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static BlockPos translatedGridPoint(BlockPos origin, BlockPos alongU, BlockPos alongV) {
      return new BlockPos(
         alongU.getX() + alongV.getX() - origin.getX(),
         alongU.getY() + alongV.getY() - origin.getY(),
         alongU.getZ() + alongV.getZ() - origin.getZ()
      );
   }

   private static BlockPos delta(BlockPos from, BlockPos to) {
      return new BlockPos(
         to.getX() - from.getX(),
         to.getY() - from.getY(),
         to.getZ() - from.getZ()
      );
   }

   private static BlockPos randomPoint(Random random) {
      return new BlockPos(random.nextInt(-8, 9), random.nextInt(-8, 9), random.nextInt(-8, 9));
   }

   private static BlockPos randomVector(Random random) {
      BlockPos result;
      do {
         result = new BlockPos(random.nextInt(-6, 7), random.nextInt(-6, 7), random.nextInt(-6, 7));
      } while (result.equals(BlockPos.ZERO));
      return result;
   }

   private static BlockPos cross(BlockPos first, BlockPos second) {
      return new BlockPos(
         first.getY() * second.getZ() - first.getZ() * second.getY(),
         first.getZ() * second.getX() - first.getX() * second.getZ(),
         first.getX() * second.getY() - first.getY() * second.getX()
      );
   }

   private static List<BlockPos> reorder(List<BlockPos> vertices, int start, int direction) {
      ArrayList<BlockPos> result = new ArrayList<>(vertices.size());
      for (int offset = 0; offset < vertices.size(); offset++) {
         result.add(vertices.get(Math.floorMod(start + direction * offset, vertices.size())));
      }
      return List.copyOf(result);
   }

   private static BlockPos transform(BlockPos position, int[] order, int[] signs) {
      int[] values = {position.getX(), position.getY(), position.getZ()};
      return new BlockPos(
         signs[0] * values[order[0]],
         signs[1] * values[order[1]],
         signs[2] * values[order[2]]
      );
   }

   private record Column(int u, int v) {
   }

   private record AirCell(Column column, int height) {
   }

   private record StepPair(BlockPos alongU, BlockPos alongV) {
   }

}
