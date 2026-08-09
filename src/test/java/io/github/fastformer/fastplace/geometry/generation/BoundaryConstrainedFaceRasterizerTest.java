package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoundaryConstrainedFaceRasterizerTest {
   private static final int[][] AXIS_ORDERS = {
      {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
   };

   @Test
   void fullyTiltedParallelogramProducesACompleteBoundaryConstrainedHeightField() {
      List<BlockPos> vertices = parallelogram(
         BlockPos.ZERO,
         new BlockPos(5, 2, -1),
         new BlockPos(-2, 4, 3)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);

      BoundaryConstrainedFaceRasterizer.Success success = assertInstanceOf(
         BoundaryConstrainedFaceRasterizer.Success.class,
         BoundaryConstrainedFaceRasterizer.solve(frame)
      );

      assertSuccessContract(vertices, frame, success);
   }

   @Test
   void cyclicWindingIsStableAndSignedAxisFramesPreserveTheirOwnedEdges() {
      List<BlockPos> vertices = parallelogram(
         new BlockPos(-3, 2, 5),
         new BlockPos(5, 2, -1),
         new BlockPos(-2, 4, 3)
      );
      BoundaryConstrainedFaceRasterizer.Success baseline = solveSuccess(vertices);
      Set<BlockPos> baselinePrimary = Set.copyOf(baseline.primary().values());

      for (int start = 0; start < vertices.size(); start++) {
         for (int direction : new int[]{1, -1}) {
            List<BlockPos> reordered = reorder(vertices, start, direction);
            BoundaryConstrainedFaceRasterizer.Success reorderedResult = solveSuccess(reordered);
            assertEquals(baselinePrimary, Set.copyOf(reorderedResult.primary().values()));
            assertEquals(baseline.outline(), reorderedResult.outline());
            assertSuccessContract(reordered, frame(reordered), reorderedResult);
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
            BoundaryConstrainedFaceRasterizer.Success transformed = solveSuccess(transformedVertices);
            assertSuccessContract(transformedVertices, frame(transformedVertices), transformed);
         }
      }
   }

   @Test
   void seededCoordinatePartialAndFullyTiltedInputsHaveOnlyDeclaredOutcomes() {
      Random random = new Random(0x424F554E44415259L);
      int[] successesByCategory = new int[3];
      int successes = 0;
      int multiHeightFailures = 0;
      int nonLipschitzFailures = 0;
      int nonSmoothFailures = 0;
      int attempts = 0;
      while (successes < 300 && attempts < 20_000) {
         int category = attempts % 3;
         int orderIndex = attempts % AXIS_ORDERS.length;
         int mask = (attempts / AXIS_ORDERS.length) % 8;
         attempts++;
         List<BlockPos> vertices = randomParallelogram(random, category);
         int[] signs = {
            (mask & 1) == 0 ? -1 : 1,
            (mask & 2) == 0 ? -1 : 1,
            (mask & 4) == 0 ? -1 : 1
         };
         List<BlockPos> transformed = vertices.stream()
            .map(position -> transform(position, AXIS_ORDERS[orderIndex], signs))
            .toList();
         ProjectedBresenhamFace.Frame frame = frame(transformed);
         BoundaryConstrainedFaceRasterizer.SolveResult result = BoundaryConstrainedFaceRasterizer.solve(frame);
         if (result instanceof BoundaryConstrainedFaceRasterizer.Success success) {
            assertSuccessContract(transformed, frame, success);
            assertSuccessIsStableUnderCyclicStartsAndWinding(transformed, success);
            successes++;
            successesByCategory[category]++;
         } else {
            BoundaryConstrainedFaceRasterizer.Failure failure =
               assertInstanceOf(BoundaryConstrainedFaceRasterizer.Failure.class, result);
            if (failure.kind() == BoundaryConstrainedFaceRasterizer.FailureKind.MULTI_HEIGHT_BOUNDARY) {
               multiHeightFailures++;
            } else if (failure.kind() == BoundaryConstrainedFaceRasterizer.FailureKind.NON_LIPSCHITZ_BOUNDARY) {
               nonLipschitzFailures++;
            } else if (failure.kind() == BoundaryConstrainedFaceRasterizer.FailureKind.NON_SMOOTH_HEIGHT_FIELD) {
               nonSmoothFailures++;
            } else {
               throw new AssertionError(
                  "unexpected failure kind=" + failure.kind()
                     + " category=" + category
                     + " vertices=" + transformed
                     + " detail=" + failure.detail()
               );
            }
         }
      }

      System.out.println(
         "BoundaryConstrainedFaceRasterizer random distribution: success=" + successes
            + " coordinateSuccess=" + successesByCategory[0]
            + " partialSuccess=" + successesByCategory[1]
            + " fullyTiltedSuccess=" + successesByCategory[2]
            + " multiHeightFailure=" + multiHeightFailures
            + " nonLipschitzFailure=" + nonLipschitzFailures
            + " nonSmoothFailure=" + nonSmoothFailures
            + " attempts=" + attempts
      );
      assertTrue(successes >= 300, "fewer than 300 random inputs were solvable");
      assertTrue(successesByCategory[0] >= 50, "coordinate-plane success coverage is too small");
      assertTrue(successesByCategory[1] >= 50, "partially tilted success coverage is too small");
      assertTrue(successesByCategory[2] >= 50, "fully tilted success coverage is too small");
   }

   @Test
   void boundaryWithTwoHeightsInOnePrimaryColumnFailsExplicitly() {
      List<BlockPos> vertices = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(4, -3, -3),
         new BlockPos(4, -2, -4),
         new BlockPos(0, 1, -1)
      );
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      Set<BlockPos> outline = ownedOutline(vertices);
      Map<TestColumn, Set<BlockPos>> outlineColumns = new HashMap<>();
      outline.forEach(position -> outlineColumns
         .computeIfAbsent(project(frame, position), ignored -> new LinkedHashSet<>())
         .add(position));
      assertTrue(
         outlineColumns.values().stream().anyMatch(column -> column.size() > 1),
         "fixture must own multiple boundary heights in one primary column"
      );

      BoundaryConstrainedFaceRasterizer.Failure failure = assertInstanceOf(
         BoundaryConstrainedFaceRasterizer.Failure.class,
         BoundaryConstrainedFaceRasterizer.solve(frame)
      );

      assertEquals(BoundaryConstrainedFaceRasterizer.FailureKind.MULTI_HEIGHT_BOUNDARY, failure.kind());
   }

   private static BoundaryConstrainedFaceRasterizer.Success solveSuccess(List<BlockPos> vertices) {
      ProjectedBresenhamFace.Frame frame = frame(vertices);
      return assertInstanceOf(
         BoundaryConstrainedFaceRasterizer.Success.class,
         BoundaryConstrainedFaceRasterizer.solve(frame)
      );
   }

   private static void assertSuccessIsStableUnderCyclicStartsAndWinding(
      List<BlockPos> vertices,
      BoundaryConstrainedFaceRasterizer.Success expected
   ) {
      Set<BlockPos> expectedPrimary = Set.copyOf(expected.primary().values());
      for (int start = 0; start < vertices.size(); start++) {
         for (int direction : new int[]{1, -1}) {
            List<BlockPos> reordered = reorder(vertices, start, direction);
            BoundaryConstrainedFaceRasterizer.SolveResult result =
               BoundaryConstrainedFaceRasterizer.solve(frame(reordered));
            if (!(result instanceof BoundaryConstrainedFaceRasterizer.Success success)) {
               throw new AssertionError(
                  "a successful input changed outcome after reordering: vertices=" + vertices
                     + " start=" + start
                     + " direction=" + direction
                     + " result=" + result
               );
            }
            assertEquals(expectedPrimary, Set.copyOf(success.primary().values()));
            assertEquals(expected.outline(), success.outline());
            assertSuccessContract(reordered, frame(reordered), success);
         }
      }
   }

   private static void assertSuccessContract(
      List<BlockPos> vertices,
      ProjectedBresenhamFace.Frame frame,
      BoundaryConstrainedFaceRasterizer.Success success
   ) {
      Set<BlockPos> expectedOutline = ownedOutline(vertices);
      Set<BlockPos> primaryBlocks = Set.copyOf(success.primary().values());

      assertEquals(expectedOutline, success.outline(), "outline changed an owned LineGenerator path");
      assertEquals(success.domain(), success.primary().keySet(), "domain and primary columns diverged");
      assertEquals(success.primary().size(), primaryBlocks.size(), "two primary columns restored the same block");
      assertTrue(primaryBlocks.containsAll(success.outline()), "primary height field lost an outline voxel");
      assertFalse(success.domain().isEmpty());
      success.primary().forEach((column, block) -> assertEquals(
         column,
         new BoundaryConstrainedFaceRasterizer.Column(project(frame, block).u(), project(frame, block).v()),
         "primary block was stored under the wrong column"
      ));
      assertTrue(isFourConnected(success.domain()), "primary domain is not four-neighbor connected");
      assertTrue(hasNoEnclosedHole(success.domain()), "primary domain contains an enclosed projected hole");
      assertLocalHeightSteps(frame, success.primary(), success.domain());
      assertNoStrictInteriorExtrema(frame, success.primary(), success.domain());
   }

   private static void assertNoStrictInteriorExtrema(
      ProjectedBresenhamFace.Frame frame,
      Map<BoundaryConstrainedFaceRasterizer.Column, BlockPos> primary,
      Set<BoundaryConstrainedFaceRasterizer.Column> domain
   ) {
      for (BoundaryConstrainedFaceRasterizer.Column column : domain) {
         List<BoundaryConstrainedFaceRasterizer.Column> neighbors = cardinalNeighbors(column);
         if (!domain.containsAll(neighbors)) {
            continue;
         }
         int height = localHeight(frame, primary.get(column));
         boolean strictMinimum = neighbors.stream().allMatch(neighbor -> localHeight(frame, primary.get(neighbor)) > height);
         boolean strictMaximum = neighbors.stream().allMatch(neighbor -> localHeight(frame, primary.get(neighbor)) < height);
         assertFalse(
            strictMinimum || strictMaximum,
            () -> "strict interior height extremum at " + column + " height=" + height
         );
      }
   }

   private static void assertLocalHeightSteps(
      ProjectedBresenhamFace.Frame frame,
      Map<BoundaryConstrainedFaceRasterizer.Column, BlockPos> primary,
      Set<BoundaryConstrainedFaceRasterizer.Column> domain
   ) {
      for (BoundaryConstrainedFaceRasterizer.Column column : domain) {
         int height = localHeight(frame, primary.get(column));
         for (BoundaryConstrainedFaceRasterizer.Column neighbor : cardinalNeighbors(column)) {
            if (domain.contains(neighbor)) {
               int neighborHeight = localHeight(frame, primary.get(neighbor));
               assertTrue(
                  Math.abs(height - neighborHeight) <= 1,
                  () -> "local height jump " + column + "=" + height
                     + " -> " + neighbor + "=" + neighborHeight
               );
            }
         }
      }
   }

   private static List<BoundaryConstrainedFaceRasterizer.Column> cardinalNeighbors(
      BoundaryConstrainedFaceRasterizer.Column column
   ) {
      return List.of(
         new BoundaryConstrainedFaceRasterizer.Column(column.u() + 1, column.v()),
         new BoundaryConstrainedFaceRasterizer.Column(column.u() - 1, column.v()),
         new BoundaryConstrainedFaceRasterizer.Column(column.u(), column.v() + 1),
         new BoundaryConstrainedFaceRasterizer.Column(column.u(), column.v() - 1)
      );
   }

   private static boolean isFourConnected(Set<BoundaryConstrainedFaceRasterizer.Column> domain) {
      HashSet<BoundaryConstrainedFaceRasterizer.Column> visited = new HashSet<>();
      ArrayDeque<BoundaryConstrainedFaceRasterizer.Column> open = new ArrayDeque<>();
      open.add(domain.iterator().next());
      while (!open.isEmpty()) {
         BoundaryConstrainedFaceRasterizer.Column current = open.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (BoundaryConstrainedFaceRasterizer.Column neighbor : cardinalNeighbors(current)) {
            if (domain.contains(neighbor) && !visited.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      return visited.size() == domain.size();
   }

   private static boolean hasNoEnclosedHole(Set<BoundaryConstrainedFaceRasterizer.Column> domain) {
      int minimumU = domain.stream().mapToInt(BoundaryConstrainedFaceRasterizer.Column::u).min().orElseThrow() - 1;
      int maximumU = domain.stream().mapToInt(BoundaryConstrainedFaceRasterizer.Column::u).max().orElseThrow() + 1;
      int minimumV = domain.stream().mapToInt(BoundaryConstrainedFaceRasterizer.Column::v).min().orElseThrow() - 1;
      int maximumV = domain.stream().mapToInt(BoundaryConstrainedFaceRasterizer.Column::v).max().orElseThrow() + 1;
      HashSet<BoundaryConstrainedFaceRasterizer.Column> exterior = new HashSet<>();
      ArrayDeque<BoundaryConstrainedFaceRasterizer.Column> open = new ArrayDeque<>();
      open.add(new BoundaryConstrainedFaceRasterizer.Column(minimumU, minimumV));
      while (!open.isEmpty()) {
         BoundaryConstrainedFaceRasterizer.Column current = open.removeFirst();
         if (!exterior.add(current)) {
            continue;
         }
         for (int u = -1; u <= 1; u++) {
            for (int v = -1; v <= 1; v++) {
               if (u == 0 && v == 0) {
                  continue;
               }
               BoundaryConstrainedFaceRasterizer.Column neighbor =
                  new BoundaryConstrainedFaceRasterizer.Column(current.u() + u, current.v() + v);
               if (neighbor.u() >= minimumU && neighbor.u() <= maximumU
                  && neighbor.v() >= minimumV && neighbor.v() <= maximumV
                  && !domain.contains(neighbor)
                  && !exterior.contains(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
      }
      for (int u = minimumU; u <= maximumU; u++) {
         for (int v = minimumV; v <= maximumV; v++) {
            BoundaryConstrainedFaceRasterizer.Column column = new BoundaryConstrainedFaceRasterizer.Column(u, v);
            if (!domain.contains(column) && !exterior.contains(column)) {
               return false;
            }
         }
      }
      return true;
   }

   private static Set<BlockPos> ownedOutline(List<BlockPos> vertices) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int index = 0; index < vertices.size(); index++) {
         result.addAll(LineGenerator.path(vertices.get(index), vertices.get((index + 1) % vertices.size())));
      }
      return result;
   }

   private static ProjectedBresenhamFace.Frame frame(List<BlockPos> vertices) {
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(
         vertices.stream().map(Vec3::atCenterOf).toList()
      );
      assertNotNull(frame);
      return frame;
   }

   private static TestColumn project(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return new TestColumn(
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

   private static List<BlockPos> parallelogram(BlockPos origin, BlockPos first, BlockPos second) {
      return List.of(origin, origin.offset(first), origin.offset(first).offset(second), origin.offset(second));
   }

   private static List<BlockPos> randomParallelogram(Random random, int category) {
      BlockPos origin = new BlockPos(
         random.nextInt(-8, 9),
         random.nextInt(-8, 9),
         random.nextInt(-8, 9)
      );
      while (true) {
         BlockPos first;
         BlockPos second;
         if (category == 0) {
            int normalAxis = random.nextInt(3);
            int firstAxis = (normalAxis + 1) % 3;
            int secondAxis = (normalAxis + 2) % 3;
            int[] firstValues = new int[3];
            int[] secondValues = new int[3];
            firstValues[firstAxis] = nonZero(random, 1, 8);
            firstValues[secondAxis] = random.nextBoolean() ? 0 : nonZero(random, 1, 8);
            secondValues[firstAxis] = random.nextBoolean() ? 0 : nonZero(random, 1, 8);
            secondValues[secondAxis] = nonZero(random, 1, 8);
            first = new BlockPos(firstValues[0], firstValues[1], firstValues[2]);
            second = new BlockPos(secondValues[0], secondValues[1], secondValues[2]);
         } else if (category == 1) {
            int zeroNormalAxis = random.nextInt(3);
            int firstValue = nonZero(random, 1, 8);
            int secondValue = nonZero(random, 1, 8);
            int height = nonZero(random, 1, 8);
            switch (zeroNormalAxis) {
               case 0 -> {
                  first = new BlockPos(0, secondValue, -firstValue);
                  second = new BlockPos(height, 0, 0);
               }
               case 1 -> {
                  first = new BlockPos(secondValue, 0, -firstValue);
                  second = new BlockPos(0, height, 0);
               }
               default -> {
                  first = new BlockPos(secondValue, -firstValue, 0);
                  second = new BlockPos(0, 0, height);
               }
            }
         } else {
            first = randomFullyTiltedVector(random);
            second = randomFullyTiltedVector(random);
         }
         BlockPos normal = cross(first, second);
         if (!normal.equals(BlockPos.ZERO)
            && (category != 2 || allComponentsNonZero(normal))) {
            return parallelogram(origin, first, second);
         }
      }
   }

   private static BlockPos randomFullyTiltedVector(Random random) {
      return new BlockPos(
         nonZero(random, 1, 8),
         nonZero(random, 1, 8),
         nonZero(random, 1, 8)
      );
   }

   private static BlockPos cross(BlockPos first, BlockPos second) {
      return new BlockPos(
         first.getY() * second.getZ() - first.getZ() * second.getY(),
         first.getZ() * second.getX() - first.getX() * second.getZ(),
         first.getX() * second.getY() - first.getY() * second.getX()
      );
   }

   private static boolean allComponentsNonZero(BlockPos vector) {
      return vector.getX() != 0 && vector.getY() != 0 && vector.getZ() != 0;
   }

   private static int nonZero(Random random, int minimum, int maximum) {
      int magnitude = random.nextInt(minimum, maximum + 1);
      return random.nextBoolean() ? magnitude : -magnitude;
   }

   private static List<BlockPos> reorder(List<BlockPos> vertices, int start, int direction) {
      java.util.ArrayList<BlockPos> result = new java.util.ArrayList<>(vertices.size());
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

   private record TestColumn(int u, int v) {
   }
}
