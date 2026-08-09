package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoundaryInterpolatedFaceRasterizerTest {
   private static final int[][] AXIS_ORDERS = {
      {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
   };

   @Test
   void coordinatePartialFullyTiltedAndVisualFixturesSatisfyTheSurfaceContract() {
      List<List<BlockPos>> fixtures = List.of(
         rectangle(BlockPos.ZERO, new BlockPos(6, 0, 0), new BlockPos(0, 0, 4)),
         rectangle(BlockPos.ZERO, new BlockPos(6, 0, 2), new BlockPos(0, 5, 0)),
         rectangle(new BlockPos(-2, 3, 1), new BlockPos(6, 2, -1), new BlockPos(-2, 5, 3)),
         List.of(
            new BlockPos(282, 63, 127),
            new BlockPos(279, 70, 131),
            new BlockPos(273, 70, 126),
            new BlockPos(276, 63, 122)
         )
      );

      for (List<BlockPos> vertices : fixtures) {
         assertSurfaceContract(vertices, solve(vertices));
      }
   }

   @Test
   void fixedVisualFixtureIsEquivariantUnderWindingAxisPermutationAndSignFlip() {
      List<BlockPos> vertices = List.of(
         new BlockPos(282, 63, 127),
         new BlockPos(279, 70, 131),
         new BlockPos(273, 70, 126),
         new BlockPos(276, 63, 122)
      );
      Set<BlockPos> baseline = solve(vertices).result().fill();
      for (int start = 0; start < 4; start++) {
         for (int direction : new int[]{1, -1}) {
            assertEquals(baseline, solve(reorder(vertices, start, direction)).result().fill());
         }
      }
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
            Set<BlockPos> expected = baseline.stream()
               .map(position -> transform(position, order, signs))
               .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<BlockPos> expectedBoundary = translatedBoundary(vertices).stream()
               .map(position -> transform(position, order, signs))
               .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            BresenhamFaceSweep.Attempt transformed = solve(transformedVertices);
            Set<BlockPos> actual = transformed.result().fill();
            HashSet<BlockPos> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            HashSet<BlockPos> extra = new HashSet<>(actual);
            extra.removeAll(expected);
            if (expectedBoundary.equals(translatedBoundary(transformedVertices))) {
               assertTrue(
                  expected.equals(actual) || java.util.stream.Stream.concat(missing.stream(), extra.stream())
                     .allMatch(block -> isExactHalfHeightTie(transformedVertices, block)),
                  () -> "order=" + java.util.Arrays.toString(order)
                     + " signs=" + java.util.Arrays.toString(signs)
                     + " baselineFrame=" + describe(frame(vertices))
                     + " transformedFrame=" + describe(frame(transformedVertices))
                     + " expectedSize=" + expected.size()
                     + " actualSize=" + actual.size()
                     + " missing=" + missing.stream().limit(12).toList()
                     + " extra=" + extra.stream().limit(12).toList()
               );
            }
            assertSurfaceContract(transformedVertices, transformed);
         }
      }
   }

   private static boolean isExactHalfHeightTie(List<BlockPos> vertices, BlockPos block) {
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      Column column = project(frame, block);
      double realHeight = -(frame.normal().x * column.u() + frame.normal().y * column.v()) / frame.normal().z;
      double twice = realHeight * 2.0;
      long rounded = Math.round(twice);
      return Math.abs(twice - rounded) < 1.0E-9 && Math.floorMod(rounded, 2L) == 1L;
   }

   @Test
   void exactHalfTieAndLimitHaveDeclaredOutcomes() {
      List<BlockPos> tie = rectangle(
         new BlockPos(0, 0, 0),
         new BlockPos(4, 2, 1),
         new BlockPos(-2, 4, 1)
      );
      BresenhamFaceSweep.Attempt success = solve(tie);
      assertSurfaceContract(tie, success);

      ProjectedBresenhamFace.Frame frame = frame(tie);
      BresenhamFaceSweep.Attempt limited = BoundaryInterpolatedFaceRasterizer.attempt(
         frame,
         LineTieBias.DEFAULT,
         1,
         BlockGenerationObserver.NONE
      );
      assertEquals(BresenhamFaceSweep.Status.LIMIT_EXCEEDED, limited.status());
   }

   @Test
   void quadProductionEntryUsesTheCandidateAndPublishesItsNaturalOutline() {
      List<BlockPos> vertices = List.of(
         new BlockPos(282, 63, 127),
         new BlockPos(279, 70, 131),
         new BlockPos(273, 70, 126),
         new BlockPos(276, 63, 122)
      );
      List<Vec3> centers = vertices.stream().map(Vec3::atCenterOf).toList();
      BresenhamFaceSweep.Attempt expected = solve(vertices);
      Set<BlockPos> surface = QuadFaceGenerator.generate(centers, FillMode.SOLID, 100_000);
      Set<BlockPos> outline = QuadFaceGenerator.generate(centers, FillMode.OUTLINE, 100_000);

      assertEquals(expected.result().fill(), surface);
      assertEquals(translatedBoundary(vertices), outline);
   }

   @Test
   void translatedDiscreteFrameDefinesTheProjectedDomainWithoutForcingDoubleColumns() {
      List<BlockPos> vertices = List.of(
         new BlockPos(282, 63, 127),
         new BlockPos(279, 70, 131),
         new BlockPos(273, 70, 126),
         new BlockPos(276, 63, 122)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      Set<BlockPos> boundary = translatedBoundary(vertices);
      Set<BlockPos> blocks = solve(vertices).result().fill();
      Set<Column> expectedDomain = scanlineDomain(frame, boundary);
      Set<Column> actualDomain = blocks.stream()
         .map(block -> project(frame, block))
         .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

      Set<Column> boundaryColumns = boundary.stream()
         .map(block -> project(frame, block))
         .collect(java.util.stream.Collectors.toSet());
      assertTrue(actualDomain.containsAll(boundaryColumns), "a translated-frame projected column was lost");
      assertEquals(expectedDomain, actualDomain, "the projected domain was not defined by the translated frame");
      assertEquals(actualDomain.size(), blocks.size(), "a projected column contains more than one block");
   }

   @Test
   void translatedParallelEdgesKeepEverySweepRowAtOneHeight() {
      List<BlockPos> vertices = List.of(
         new BlockPos(-88, 63, 222),
         new BlockPos(-99, 63, 222),
         new BlockPos(-99, 70, 234),
         new BlockPos(-88, 70, 234)
      );
      Set<BlockPos> blocks = solve(vertices).result().fill();

      for (int z = 222; z <= 234; z++) {
         int row = z;
         Set<Integer> heights = blocks.stream()
            .filter(block -> block.getZ() == row)
            .map(BlockPos::getY)
            .collect(java.util.stream.Collectors.toSet());
         assertEquals(1, heights.size(), () -> "parallel sweep row split at z=" + row + ": " + heights);
      }
   }

   @Test
   void experimentalGradientCrossInterpolationKeepsTheSurfaceContractAndIsASeparateCandidate() {
      List<BlockPos> vertices = List.of(
         BlockPos.ZERO,
         new BlockPos(-2, -3, -3),
         new BlockPos(-2, 0, -6),
         new BlockPos(0, 3, -3)
      );
      BresenhamFaceSweep.Attempt pointSweep = attempt(
         vertices, FaceRasterizationMode.POINT_SWEEP
      );
      BresenhamFaceSweep.Attempt cross = attempt(
         vertices, FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL
      );

      assertSurfaceContract(vertices, pointSweep);
      assertExperimentalOutcome(vertices, cross);
   }

   @Test
   void experimentalGradientCrossInterpolationIsEquivariantUnderWindingAxisPermutationAndSignFlip() {
      List<BlockPos> vertices = List.of(
         BlockPos.ZERO,
         new BlockPos(-2, -3, -3),
         new BlockPos(-2, 0, -6),
         new BlockPos(0, 3, -3)
      );
      BresenhamFaceSweep.Attempt baseline = attempt(
         vertices, FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL
      );
      assertExperimentalOutcome(vertices, baseline);
      for (int start = 0; start < 4; start++) {
         for (int direction : new int[]{1, -1}) {
            List<BlockPos> reordered = reorder(vertices, start, direction);
            assertExperimentalOutcome(
               reordered,
               attempt(reordered, FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL)
            );
         }
      }
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
            BresenhamFaceSweep.Attempt transformed = attempt(
               transformedVertices,
               FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL
            );
            assertExperimentalOutcome(transformedVertices, transformed);
         }
      }
   }

   @Test
   void diagonalProjectedFrameIsClosedBeforeHeightInterpolation() {
      List<BlockPos> vertices = List.of(
         new BlockPos(4, -8, -1),
         new BlockPos(-4, -11, -7),
         new BlockPos(3, -10, 1),
         new BlockPos(11, -7, 7)
      );
      BresenhamFaceSweep.Attempt attempt = solve(vertices);
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      Set<Column> domain = attempt.result().fill().stream()
         .map(block -> project(frame, block))
         .collect(java.util.stream.Collectors.toSet());

      assertTrue(isFourConnected(domain));
      assertTrue(hasNoEnclosedHole(domain));
      assertTrue(isTwentySixConnected(attempt.result().fill()));
      assertEquals(domain.size(), attempt.result().fill().size());
   }

   @Test
   void exhaustiveSmallPerpendicularVectorsAndSeededRectanglesHaveOnlyDeclaredOutcomes() {
      ArrayList<BlockPos> vectors = new ArrayList<>();
      for (int x = -2; x <= 2; x++) {
         for (int y = -2; y <= 2; y++) {
            for (int z = -2; z <= 2; z++) {
               if (x != 0 || y != 0 || z != 0) {
                  vectors.add(new BlockPos(x, y, z));
               }
            }
         }
      }
      int smallSuccess = 0;
      int smallFailure = 0;
      for (int firstIndex = 0; firstIndex < vectors.size(); firstIndex++) {
         for (int secondIndex = firstIndex + 1; secondIndex < vectors.size(); secondIndex++) {
            BlockPos first = vectors.get(firstIndex);
            BlockPos second = vectors.get(secondIndex);
            if (dot(first, second) != 0 || cross(first, second).equals(BlockPos.ZERO)) {
               continue;
            }
            List<BlockPos> vertices = rectangle(BlockPos.ZERO, first, second);
            BresenhamFaceSweep.Attempt attempt = attempt(vertices);
            if (attempt.succeeded()) {
               assertSurfaceContract(vertices, attempt);
               smallSuccess++;
            } else {
               assertEquals(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE, attempt.status());
               smallFailure++;
            }
         }
      }

      Random random = new Random(0x42494C494E454152L);
      int randomSuccess = 0;
      int randomFailure = 0;
      int experimentalSuccess = 0;
      int experimentalFailure = 0;
      for (int sample = 0; sample < 500; sample++) {
         BlockPos first;
         BlockPos second;
         do {
            first = randomVector(random);
            second = cross(first, randomVector(random));
         } while (first.equals(BlockPos.ZERO)
            || second.equals(BlockPos.ZERO)
            || cross(first, second).equals(BlockPos.ZERO));
         List<BlockPos> vertices = rectangle(
            new BlockPos(random.nextInt(-8, 9), random.nextInt(-8, 9), random.nextInt(-8, 9)),
            first,
            second
         );
         BresenhamFaceSweep.Attempt attempt = attempt(vertices);
         BresenhamFaceSweep.Attempt crossAttempt = attempt(
            vertices, FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL
         );
         assertExperimentalOutcome(vertices, crossAttempt);
         if (crossAttempt.succeeded()) {
            experimentalSuccess++;
         } else {
            experimentalFailure++;
         }
         if (attempt.succeeded()) {
            assertSurfaceContract(vertices, attempt);
            randomSuccess++;
         } else {
            assertEquals(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE, attempt.status());
            randomFailure++;
         }
      }
      System.out.println(
         "BoundaryInterpolatedFaceRasterizer outcomes: smallSuccess=" + smallSuccess
            + " smallFailure=" + smallFailure
            + " randomSuccess=" + randomSuccess
            + " randomFailure=" + randomFailure
            + " experimentalSuccess=" + experimentalSuccess
            + " experimentalFailure=" + experimentalFailure
      );
      assertTrue(smallSuccess > 0);
      assertTrue(randomSuccess >= 400, "random success rate fell below 80%");
   }

   private static BresenhamFaceSweep.Attempt solve(List<BlockPos> vertices) {
      return solve(vertices, FaceRasterizationMode.POINT_SWEEP);
   }

   private static BresenhamFaceSweep.Attempt solve(
      List<BlockPos> vertices,
      FaceRasterizationMode rasterizationMode
   ) {
      BresenhamFaceSweep.Attempt attempt = attempt(vertices, rasterizationMode);
      assertEquals(BresenhamFaceSweep.Status.SUCCESS, attempt.status(), () -> "vertices=" + vertices);
      return attempt;
   }

   private static Set<BlockPos> translatedBoundary(List<BlockPos> vertices) {
      List<BlockPos> first = orientedPath(vertices.get(0), vertices.get(1));
      List<BlockPos> second = orientedPath(vertices.get(0), vertices.get(3));
      BlockPos firstOffset = vertices.get(3).subtract(vertices.get(0));
      BlockPos secondOffset = vertices.get(1).subtract(vertices.get(0));
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(first);
      result.addAll(second);
      first.forEach(block -> result.add(block.offset(firstOffset)));
      second.forEach(block -> result.add(block.offset(secondOffset)));
      return result;
   }

   private static List<BlockPos> orientedPath(BlockPos from, BlockPos to) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(from, to, LineTieBias.DEFAULT));
      if (!result.isEmpty() && !result.getFirst().equals(from)) {
         java.util.Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static Set<Column> scanlineDomain(ProjectedBresenhamFace.Frame frame, Set<BlockPos> boundary) {
      Map<Integer, int[]> spans = new HashMap<>();
      for (BlockPos block : boundary) {
         Column column = project(frame, block);
         spans.compute(column.v(), (ignored, span) -> span == null
            ? new int[]{column.u(), column.u()}
            : new int[]{Math.min(span[0], column.u()), Math.max(span[1], column.u())});
      }
      LinkedHashSet<Column> result = new LinkedHashSet<>();
      spans.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
         for (int u = entry.getValue()[0]; u <= entry.getValue()[1]; u++) {
            result.add(new Column(u, entry.getKey()));
         }
      });
      return result;
   }

   private static BresenhamFaceSweep.Attempt attempt(List<BlockPos> vertices) {
      return attempt(vertices, FaceRasterizationMode.POINT_SWEEP);
   }

   private static BresenhamFaceSweep.Attempt attempt(
      List<BlockPos> vertices,
      FaceRasterizationMode rasterizationMode
   ) {
      return BoundaryInterpolatedFaceRasterizer.attempt(
         frame(vertices),
         LineTieBias.DEFAULT,
         100_000,
         BlockGenerationObserver.NONE,
         rasterizationMode
      );
   }

   private static void assertSurfaceContract(List<BlockPos> vertices, BresenhamFaceSweep.Attempt attempt) {
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      Set<BlockPos> blocks = attempt.result().fill();
      assertTrue(blocks.containsAll(vertices), "a corner was lost");
      assertTrue(isTwentySixConnected(blocks), "surface is not 26-connected");

      Set<BlockPos> translatedBoundary = translatedBoundary(vertices);
      Set<Column> expectedDomain = scanlineDomain(frame, translatedBoundary);
      Set<Column> boundaryColumns = translatedBoundary.stream()
         .map(block -> project(frame, block))
         .collect(java.util.stream.Collectors.toSet());
      Map<Column, Set<Integer>> occupied = new HashMap<>();
      for (BlockPos block : blocks) {
         occupied.computeIfAbsent(project(frame, block), ignored -> new LinkedHashSet<>())
            .add(localHeight(frame, block));
      }
      Set<Column> domain = occupied.keySet();
      assertTrue(domain.containsAll(boundaryColumns), "a translated-frame projected column was lost");
      assertEquals(expectedDomain, domain, "projected domain differs from the translated-frame scanlines");
      assertTrue(isFourConnected(domain), "projected domain is not four-connected");
      assertTrue(hasNoEnclosedHole(domain), "projected domain contains an enclosed hole");
      for (Map.Entry<Column, Set<Integer>> entry : occupied.entrySet()) {
         Set<Integer> heights = entry.getValue();
         assertEquals(1, heights.size(), () -> "projected column is not single-valued at " + entry);
      }
      for (Column column : domain) {
         for (Column neighbor : List.of(new Column(column.u() + 1, column.v()), new Column(column.u(), column.v() + 1))) {
            if (domain.contains(neighbor)) {
               assertNeighborSlope(occupied.get(column), occupied.get(neighbor), column, neighbor);
            }
         }
      }
   }

   private static void assertExperimentalOutcome(
      List<BlockPos> vertices,
      BresenhamFaceSweep.Attempt attempt
   ) {
      if (attempt.succeeded()) {
         assertSurfaceContract(vertices, attempt);
      } else {
         assertEquals(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE, attempt.status());
      }
   }

   private static void assertNeighborSlope(Set<Integer> first, Set<Integer> second, Column a, Column b) {
      int firstHeight = first.iterator().next();
      int secondHeight = second.iterator().next();
      assertTrue(
         Math.abs((long)firstHeight - secondHeight) <= 1L,
         () -> "adjacent height jump exceeds one: " + a + " -> " + b
      );
   }

   private static boolean isTwentySixConnected(Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return false;
      }
      Set<BlockPos> unseen = new HashSet<>(blocks);
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      BlockPos first = unseen.iterator().next();
      unseen.remove(first);
      open.add(first);
      while (!open.isEmpty()) {
         BlockPos current = open.removeFirst();
         for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
               for (int z = -1; z <= 1; z++) {
                  if (x == 0 && y == 0 && z == 0) {
                     continue;
                  }
                  BlockPos neighbor = current.offset(x, y, z);
                  if (unseen.remove(neighbor)) {
                     open.addLast(neighbor);
                  }
               }
            }
         }
      }
      return unseen.isEmpty();
   }

   private static boolean isFourConnected(Set<Column> domain) {
      if (domain.isEmpty()) {
         return false;
      }
      Set<Column> unseen = new HashSet<>(domain);
      ArrayDeque<Column> open = new ArrayDeque<>();
      Column first = unseen.iterator().next();
      unseen.remove(first);
      open.add(first);
      while (!open.isEmpty()) {
         Column current = open.removeFirst();
         for (Column neighbor : neighbors(current)) {
            if (unseen.remove(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      return unseen.isEmpty();
   }

   private static boolean hasNoEnclosedHole(Set<Column> domain) {
      int minimumU = domain.stream().mapToInt(Column::u).min().orElseThrow() - 1;
      int maximumU = domain.stream().mapToInt(Column::u).max().orElseThrow() + 1;
      int minimumV = domain.stream().mapToInt(Column::v).min().orElseThrow() - 1;
      int maximumV = domain.stream().mapToInt(Column::v).max().orElseThrow() + 1;
      Set<Column> exterior = new HashSet<>();
      ArrayDeque<Column> open = new ArrayDeque<>();
      open.add(new Column(minimumU, minimumV));
      while (!open.isEmpty()) {
         Column current = open.removeFirst();
         if (!exterior.add(current)) {
            continue;
         }
         for (Column neighbor : neighbors(current)) {
            if (neighbor.u() >= minimumU && neighbor.u() <= maximumU
               && neighbor.v() >= minimumV && neighbor.v() <= maximumV
               && !domain.contains(neighbor) && !exterior.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      for (int u = minimumU; u <= maximumU; u++) {
         for (int v = minimumV; v <= maximumV; v++) {
            Column column = new Column(u, v);
            if (!domain.contains(column) && !exterior.contains(column)) {
               return false;
            }
         }
      }
      return true;
   }

   private static List<Column> neighbors(Column column) {
      return List.of(
         new Column(column.u() + 1, column.v()),
         new Column(column.u() - 1, column.v()),
         new Column(column.u(), column.v() + 1),
         new Column(column.u(), column.v() - 1)
      );
   }

   private static ProjectedBresenhamFace.Frame frame(List<BlockPos> vertices) {
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(
         vertices.stream().map(Vec3::atCenterOf).toList()
      );
      assertNotNull(frame);
      return frame;
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

   private static int localHeight(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return Math.toIntExact(delta[frame.order()[2]] * frame.signs()[2]);
   }

   private static List<BlockPos> rectangle(BlockPos origin, BlockPos first, BlockPos second) {
      return List.of(origin, origin.offset(first), origin.offset(first).offset(second), origin.offset(second));
   }

   private static List<BlockPos> reorder(List<BlockPos> vertices, int start, int direction) {
      ArrayList<BlockPos> result = new ArrayList<>(4);
      for (int offset = 0; offset < 4; offset++) {
         result.add(vertices.get(Math.floorMod(start + direction * offset, 4)));
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

   private static BlockPos randomVector(Random random) {
      return new BlockPos(
         random.nextInt(-3, 4),
         random.nextInt(-3, 4),
         random.nextInt(-3, 4)
      );
   }

   private static long dot(BlockPos first, BlockPos second) {
      return (long)first.getX() * second.getX()
         + (long)first.getY() * second.getY()
         + (long)first.getZ() * second.getZ();
   }

   private static BlockPos cross(BlockPos first, BlockPos second) {
      return new BlockPos(
         first.getY() * second.getZ() - first.getZ() * second.getY(),
         first.getZ() * second.getX() - first.getX() * second.getZ(),
         first.getX() * second.getY() - first.getY() * second.getX()
      );
   }

   private static String describe(ProjectedBresenhamFace.Frame frame) {
      return "{anchor=" + frame.anchor()
         + ",order=" + java.util.Arrays.toString(frame.order())
         + ",signs=" + java.util.Arrays.toString(frame.signs())
         + ",normal=" + frame.normal() + "}";
   }

   private record Column(int u, int v) {
   }
}
