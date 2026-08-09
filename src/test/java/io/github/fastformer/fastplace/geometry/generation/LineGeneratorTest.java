package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LineGeneratorTest {
   @Test
   void everyOwnedEdgeInteriorAvoidsRightAnglePixelConnectionsInEveryProjection() {
      BlockPos origin = BlockPos.ZERO;
      for (int x = -8; x <= 8; x++) {
         for (int y = -8; y <= 8; y++) {
            for (int z = -8; z <= 8; z++) {
               if (x == 0 && y == 0 && z == 0) {
                  continue;
               }
               List<BlockPos> path = LineGenerator.path(origin, new BlockPos(x, y, z));
               assertOwnedEdgeInteriorHasNoRightAngle(path, 0);
               assertOwnedEdgeInteriorHasNoRightAngle(path, 1);
               assertOwnedEdgeInteriorHasNoRightAngle(path, 2);
            }
         }
      }
   }

   @Test
   void sharedGeometryCornerMayContainARightAngle() {
      List<BlockPos> firstEdge = LineGenerator.path(BlockPos.ZERO, new BlockPos(4, 0, 0));
      List<BlockPos> secondEdge = LineGenerator.path(BlockPos.ZERO, new BlockPos(0, 4, 0));

      assertOwnedEdgeInteriorHasNoRightAngle(firstEdge, 2);
      assertOwnedEdgeInteriorHasNoRightAngle(secondEdge, 2);

      Set<Pixel> outline = new HashSet<>();
      firstEdge.forEach(point -> outline.add(project(point, 2)));
      secondEdge.forEach(point -> outline.add(project(point, 2)));
      assertTrue(hasRightAngleAt(outline, new Pixel(0, 0)));
   }

   @Test
   void leastSlopeAxisAdvancesOnlyWithTheMiddleSlopeAxis() {
      assertEquals(
         List.of(
            new BlockPos(0, 0, 0),
            new BlockPos(1, 1, 1),
            new BlockPos(2, 1, 1),
            new BlockPos(3, 2, 1)
         ),
         LineGenerator.path(BlockPos.ZERO, new BlockPos(3, 2, 1))
      );
   }

   @Test
   void slopeRankingIsIndependentOfWhichWorldAxisIsDominant() {
      assertDominantAxisStepPattern(new BlockPos(11, 4, 4), 0);
      assertDominantAxisStepPattern(new BlockPos(4, 11, 4), 1);
      assertDominantAxisStepPattern(new BlockPos(4, 4, 11), 2);
   }

   @Test
   void endpointOrderProducesTheSameVoxelSet() {
      for (int x = -6; x <= 6; x++) {
         for (int y = -6; y <= 6; y++) {
            for (int z = -6; z <= 6; z++) {
               BlockPos end = new BlockPos(x, y, z);
               assertEquals(
                  Set.copyOf(LineGenerator.path(BlockPos.ZERO, end)),
                  Set.copyOf(LineGenerator.path(end, BlockPos.ZERO)),
                  end.toShortString()
               );
            }
         }
      }
   }

   @Test
   void tieFreeLineIsEquivariantUnderAllFortyEightSignedAxisTransforms() {
      BlockPos from = new BlockPos(3, -5, 7);
      BlockPos to = from.offset(7, 3, 1);
      assertFalse(hasHalfCellTie(from, to));
      int[][] axisOrders = {
         {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
         {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
      };
      for (LineTieBias bias : List.of(LineTieBias.DEFAULT, LineTieBias.OPPOSITE)) {
         Set<BlockPos> expected = Set.copyOf(LineGenerator.path(from, to, bias));
         for (int[] axisOrder : axisOrders) {
            for (int signMask = 0; signMask < 8; signMask++) {
               int transformMask = signMask;
               BlockPos transformedFrom = signedAxisTransform(from, axisOrder, transformMask);
               BlockPos transformedTo = signedAxisTransform(to, axisOrder, transformMask);
               Set<BlockPos> transformedExpected = expected.stream()
                  .map(point -> signedAxisTransform(point, axisOrder, transformMask))
                  .collect(java.util.stream.Collectors.toSet());
               assertEquals(
                  transformedExpected,
                  Set.copyOf(LineGenerator.path(transformedFrom, transformedTo, bias)),
                  bias + ": axes=" + java.util.Arrays.toString(axisOrder) + ", signs=" + transformMask
               );
            }
         }
      }
   }

   @Test
   void halfCellTieBiasChoosesEitherExactVoxelSet() {
      BlockPos from = BlockPos.ZERO;
      BlockPos to = new BlockPos(2, 1, 0);

      // This is the minimal A=2, B=1 witness.  Endpoint-swapping center
      // inversion (a signed reflection plus translation) exchanges the two
      // middle voxels, so a one-voxel tie answer cannot also satisfy the
      // 48-transform contract above.
      assertEquals(
         Set.of(from, new BlockPos(1, 1, 0), to),
         Set.copyOf(LineGenerator.path(from, to, LineTieBias.DEFAULT))
      );
      assertEquals(
         Set.of(from, new BlockPos(1, 0, 0), to),
         Set.copyOf(LineGenerator.path(from, to, LineTieBias.OPPOSITE))
      );
   }

   @Test
   void exactHalfTieDocumentsTheKnownSignedTransformConflict() {
      BlockPos first = new BlockPos(-1, -1, 0);
      BlockPos second = new BlockPos(1, 0, 1);
      assertEquals(second, tieWitnessTransform(first));
      assertEquals(first, tieWitnessTransform(second));
      for (LineTieBias bias : List.of(LineTieBias.DEFAULT, LineTieBias.OPPOSITE)) {
         Set<BlockPos> forward = Set.copyOf(LineGenerator.path(first, second, bias));
         Set<BlockPos> reverse = Set.copyOf(LineGenerator.path(second, first, bias));
         Set<BlockPos> transformed = forward.stream().map(LineGeneratorTest::tieWitnessTransform)
            .collect(java.util.stream.Collectors.toSet());
         assertEquals(forward, reverse, bias.name());
         assertNotEquals(forward, transformed, bias.name());
      }
      assertEquals(
         Set.copyOf(LineGenerator.path(first, second, LineTieBias.OPPOSITE)),
         Set.copyOf(LineGenerator.path(first, second, LineTieBias.DEFAULT)).stream()
            .map(LineGeneratorTest::tieWitnessTransform)
            .collect(java.util.stream.Collectors.toSet())
      );
   }

   @Test
   void defaultTieBiasMatchesThePreBiasAlgorithmVoxelForVoxel() {
      BlockPos origin = new BlockPos(17, -9, 4);
      for (int x = -8; x <= 8; x++) {
         for (int y = -8; y <= 8; y++) {
            for (int z = -8; z <= 8; z++) {
               BlockPos end = origin.offset(x, y, z);
               assertEquals(
                  legacyPath(origin, end),
                  LineGenerator.path(origin, end, LineTieBias.DEFAULT),
                  end.toShortString()
               );
            }
         }
      }
   }

   @Test
   void tieBiasDoesNotChangeNonHalfRandomSlopes() {
      Random random = new Random(0x5EEDB1A5L);
      int checked = 0;
      for (int attempt = 0; attempt < 20_000 && checked < 1_000; attempt++) {
         BlockPos from = randomPoint(random, 128);
         BlockPos to = from.offset(randomPoint(random, 48));
         if (from.equals(to) || hasHalfCellTie(from, to)) {
            continue;
         }

         assertEquals(
            LineGenerator.path(from, to, LineTieBias.DEFAULT),
            LineGenerator.path(from, to, LineTieBias.OPPOSITE),
            from.toShortString() + " -> " + to.toShortString()
         );
         checked++;
      }
      assertEquals(1_000, checked);
   }

   @Test
   void eachTieBiasIsInvariantUnderEndpointReversal() {
      for (LineTieBias bias : List.of(LineTieBias.DEFAULT, LineTieBias.OPPOSITE)) {
         for (int x = -6; x <= 6; x++) {
            for (int y = -6; y <= 6; y++) {
               for (int z = -6; z <= 6; z++) {
                  BlockPos end = new BlockPos(x, y, z);
                  assertEquals(
                     Set.copyOf(LineGenerator.path(BlockPos.ZERO, end, bias)),
                     Set.copyOf(LineGenerator.path(end, BlockPos.ZERO, bias)),
                     bias + ": " + end.toShortString()
                  );
               }
            }
         }
      }
   }

   @Test
   void everyTieBiasPathUsesOnlyAAbOrAbcStepsAndHasMajorLength() {
      for (LineTieBias bias : List.of(LineTieBias.DEFAULT, LineTieBias.OPPOSITE)) {
         for (int x = -8; x <= 8; x++) {
            for (int y = -8; y <= 8; y++) {
               for (int z = -8; z <= 8; z++) {
                  BlockPos end = new BlockPos(x, y, z);
                  List<BlockPos> path = LineGenerator.path(BlockPos.ZERO, end, bias);
                  int majorLength = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z));
                  assertEquals(majorLength + 1, path.size(), bias + ": " + end.toShortString());
                  assertOnlyAAbOrAbcSteps(path, BlockPos.ZERO, end, bias);
               }
            }
         }
      }
   }

   @Test
   void oppositeTieBiasAvoidsRightAngleEdgeInteriorPixelsInEveryProjection() {
      for (int x = -8; x <= 8; x++) {
         for (int y = -8; y <= 8; y++) {
            for (int z = -8; z <= 8; z++) {
               if (x == 0 && y == 0 && z == 0) {
                  continue;
               }
               List<BlockPos> path = LineGenerator.path(
                  BlockPos.ZERO,
                  new BlockPos(x, y, z),
                  LineTieBias.OPPOSITE
               );
               assertOwnedEdgeInteriorHasNoRightAngle(path, 0);
               assertOwnedEdgeInteriorHasNoRightAngle(path, 1);
               assertOwnedEdgeInteriorHasNoRightAngle(path, 2);
            }
         }
      }
   }

   @Test
   void pathKeepsEndpointsLengthAndOutputLimit() {
      BlockPos from = new BlockPos(-3, 8, 2);
      BlockPos to = new BlockPos(8, 2, -5);
      List<BlockPos> path = LineGenerator.path(from, to);

      assertTrue(path.contains(from));
      assertTrue(path.contains(to));
      assertEquals(12, path.size());
      assertEquals(7, LineGenerator.generate(from, to, 7).size());
   }

   private static void assertOwnedEdgeInteriorHasNoRightAngle(List<BlockPos> path, int droppedAxis) {
      ArrayList<Pixel> projectedPath = new ArrayList<>();
      Set<Pixel> pixels = new HashSet<>();
      for (BlockPos point : path) {
         Pixel pixel = project(point, droppedAxis);
         if (projectedPath.isEmpty() || !projectedPath.getLast().equals(pixel)) {
            projectedPath.add(pixel);
         }
         pixels.add(pixel);
      }
      for (int index = 1; index < projectedPath.size() - 1; index++) {
         Pixel center = projectedPath.get(index);
         assertFalse(
            hasRightAngleAt(pixels, center),
            () -> "right-angle edge-interior pixel at " + center
               + " in projection " + droppedAxis + ": " + projectedPath
         );
      }
   }

   private static List<BlockPos> legacyPath(BlockPos from, BlockPos to) {
      BlockPos start = compare(from, to) <= 0 ? from : to;
      BlockPos end = start == from ? to : from;
      long[] delta = {
         (long)end.getX() - start.getX(),
         (long)end.getY() - start.getY(),
         (long)end.getZ() - start.getZ()
      };
      long[] distance = {Math.abs(delta[0]), Math.abs(delta[1]), Math.abs(delta[2])};
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
      int major = axes[0];
      int middle = axes[1];
      int minor = axes[2];
      long majorSteps = distance[major];
      if (majorSteps == 0L) {
         return List.of(start);
      }
      int[] sign = {
         Long.compare(delta[0], 0L),
         Long.compare(delta[1], 0L),
         Long.compare(delta[2], 0L)
      };
      long middleError = 2L * distance[middle] - majorSteps;
      long minorError = 2L * distance[minor] - distance[middle];
      long[] position = {start.getX(), start.getY(), start.getZ()};
      ArrayList<BlockPos> result = new ArrayList<>();
      for (long step = 0; step <= majorSteps; step++) {
         result.add(new BlockPos((int)position[0], (int)position[1], (int)position[2]));
         if (step == majorSteps) {
            break;
         }
         position[major] += sign[major];
         if (middleError >= 0L) {
            position[middle] += sign[middle];
            middleError -= 2L * majorSteps;
            if (minorError >= 0L) {
               position[minor] += sign[minor];
               minorError -= 2L * distance[middle];
            }
            minorError += 2L * distance[minor];
         }
         middleError += 2L * distance[middle];
      }
      return List.copyOf(result);
   }

   private static int compare(BlockPos first, BlockPos second) {
      int compared = Integer.compare(first.getX(), second.getX());
      if (compared == 0) {
         compared = Integer.compare(first.getY(), second.getY());
      }
      return compared != 0 ? compared : Integer.compare(first.getZ(), second.getZ());
   }

   private static boolean hasRightAngleAt(Set<Pixel> pixels, Pixel center) {
      ArrayList<Pixel> neighbors = new ArrayList<>();
      for (int first = -1; first <= 1; first++) {
         for (int second = -1; second <= 1; second++) {
            if ((first != 0 || second != 0) && pixels.contains(center.offset(first, second))) {
               neighbors.add(new Pixel(first, second));
            }
         }
      }
      for (int first = 0; first < neighbors.size(); first++) {
         for (int second = first + 1; second < neighbors.size(); second++) {
            Pixel a = neighbors.get(first);
            Pixel b = neighbors.get(second);
            if (a.first() * b.first() + a.second() * b.second() == 0) {
               return true;
            }
         }
      }
      return false;
   }

   private static void assertDominantAxisStepPattern(BlockPos end, int dominantAxis) {
      List<BlockPos> path = LineGenerator.path(BlockPos.ZERO, end);
      for (int index = 1; index < path.size(); index++) {
         BlockPos previous = path.get(index - 1);
         BlockPos current = path.get(index);
         Set<Integer> changedAxes = new HashSet<>();
         if (previous.getX() != current.getX()) {
            changedAxes.add(0);
         }
         if (previous.getY() != current.getY()) {
            changedAxes.add(1);
         }
         if (previous.getZ() != current.getZ()) {
            changedAxes.add(2);
         }
         assertTrue(
            changedAxes.equals(Set.of(dominantAxis)) || changedAxes.equals(Set.of(0, 1, 2)),
            () -> "unexpected slope-ranked step " + changedAxes + " in " + path
         );
      }
   }

   private static void assertOnlyAAbOrAbcSteps(
      List<BlockPos> path,
      BlockPos from,
      BlockPos to,
      LineTieBias bias
   ) {
      int[] axes = LineGenerator.axesByDescendingSlope(from, to);
      Set<Integer> a = Set.of(axes[0]);
      Set<Integer> ab = Set.of(axes[0], axes[1]);
      Set<Integer> abc = Set.of(axes[0], axes[1], axes[2]);
      for (int index = 1; index < path.size(); index++) {
         BlockPos previous = path.get(index - 1);
         BlockPos current = path.get(index);
         Set<Integer> changedAxes = changedAxes(previous, current);
         assertTrue(
            changedAxes.equals(a) || changedAxes.equals(ab) || changedAxes.equals(abc),
            () -> bias + ": unexpected step " + changedAxes + " in " + path
         );
         assertTrue(
            Math.abs(current.getX() - previous.getX()) <= 1
               && Math.abs(current.getY() - previous.getY()) <= 1
               && Math.abs(current.getZ() - previous.getZ()) <= 1,
            () -> bias + ": non-unit step in " + path
         );
      }
   }

   private static Set<Integer> changedAxes(BlockPos previous, BlockPos current) {
      Set<Integer> changedAxes = new HashSet<>();
      if (previous.getX() != current.getX()) {
         changedAxes.add(0);
      }
      if (previous.getY() != current.getY()) {
         changedAxes.add(1);
      }
      if (previous.getZ() != current.getZ()) {
         changedAxes.add(2);
      }
      return changedAxes;
   }

   private static boolean hasHalfCellTie(BlockPos from, BlockPos to) {
      int[] axes = LineGenerator.axesByDescendingSlope(from, to);
      long[] distance = {
         Math.abs((long)to.getX() - from.getX()),
         Math.abs((long)to.getY() - from.getY()),
         Math.abs((long)to.getZ() - from.getZ())
      };
      return hasHalfCellTie(distance[axes[0]], distance[axes[1]])
         || hasHalfCellTie(distance[axes[1]], distance[axes[2]]);
   }

   private static boolean hasHalfCellTie(long majorDistance, long secondaryDistance) {
      for (long step = 1; step < majorDistance; step++) {
         if ((2L * step * secondaryDistance) % (2L * majorDistance) == majorDistance) {
            return true;
         }
      }
      return false;
   }

   private static BlockPos randomPoint(Random random, int radius) {
      int diameter = radius * 2 + 1;
      return new BlockPos(
         random.nextInt(diameter) - radius,
         random.nextInt(diameter) - radius,
         random.nextInt(diameter) - radius
      );
   }

   private static BlockPos tieWitnessTransform(BlockPos point) {
      return new BlockPos(-point.getX(), -point.getZ(), -point.getY());
   }

   private static BlockPos signedAxisTransform(BlockPos point, int[] axisOrder, int signMask) {
      int[] coordinates = {point.getX(), point.getY(), point.getZ()};
      int[] transformed = new int[3];
      for (int axis = 0; axis < transformed.length; axis++) {
         int sign = (signMask & 1 << axis) == 0 ? -1 : 1;
         transformed[axis] = sign * coordinates[axisOrder[axis]];
      }
      return new BlockPos(transformed[0], transformed[1], transformed[2]);
   }

   private static Pixel project(BlockPos point, int droppedAxis) {
      return switch (droppedAxis) {
         case 0 -> new Pixel(point.getY(), point.getZ());
         case 1 -> new Pixel(point.getX(), point.getZ());
         case 2 -> new Pixel(point.getX(), point.getY());
         default -> throw new IllegalArgumentException("axis " + droppedAxis);
      };
   }

   private record Pixel(int first, int second) {
      Pixel offset(int firstOffset, int secondOffset) {
         return new Pixel(this.first + firstOffset, this.second + secondOffset);
      }
   }
}
