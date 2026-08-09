package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PrismGeneratorTest {
   private static final int[][] POLYGON = {
      {0, 0}, {2, 0}, {3, 1}, {2, 3}, {0, 3}, {-1, 1}
   };

   @Test
   void coordinatePlaneAndFullyTiltedPolygonsShareTheSweepContract() {
      assertPrismContract(
         polygon(new Vec3(0.5, 0.5, 0.5), new Vec3(3.0, 0.0, 0.0), new Vec3(0.0, 0.0, 3.0)),
         new Vec3(0.0, 7.0, 0.0),
         "coordinate"
      );
      assertPrismContract(
         polygon(new Vec3(-3.5, 4.5, 2.5), new Vec3(2.0, 1.0, -1.0), new Vec3(-1.0, 2.0, 2.0)),
         new Vec3(5.0, -4.0, 3.0),
         "tilted"
      );
   }

   @Test
   void randomCoordinateAndFullyTiltedPolygonPrismsRemainClosed() {
      Random random = new Random(0x504F4C5950524953L);
      for (int sample = 0; sample < 64; sample++) {
         int normalAxis = sample % 3;
         int firstAxis = (normalAxis + 1) % 3;
         int secondAxis = (normalAxis + 2) % 3;
         Vec3 first = sample % 4 == 0
            ? vector(firstAxis, nonZero(random, 1, 4), secondAxis, 0)
            : vector(firstAxis, nonZero(random, 1, 4), secondAxis, nonZero(random, 1, 4));
         Vec3 second = sample % 4 == 0
            ? vector(firstAxis, 0, secondAxis, nonZero(random, 1, 4))
            : vector(firstAxis, nonZero(random, 1, 4), secondAxis, nonZero(random, 1, 4));
         if (first.cross(second).lengthSqr() < 1.0E-7) {
            sample--;
            continue;
         }
         double[] delta = {nonZero(random, 1, 5), nonZero(random, 1, 5), nonZero(random, 1, 5)};
         delta[normalAxis] = nonZero(random, 2, 7);
         assertPrismContract(
            polygon(randomCenter(random), first, second),
            new Vec3(delta[0], delta[1], delta[2]),
            "coordinate-random-" + sample
         );
      }

      int checked = 0;
      int attempts = 0;
      while (checked < 64 && attempts++ < 10000) {
         Vec3 first = tiltedVector(random);
         Vec3 second = tiltedVector(random);
         Vec3 extrusion = tiltedVector(random);
         Vec3 normal = first.cross(second);
         if (normal.lengthSqr() < 2.0 || !allComponentsNonZero(normal) || Math.abs(normal.dot(extrusion)) < 1.0) {
            continue;
         }
         assertPrismContract(
            polygon(randomCenter(random), first, second), extrusion, "tilted-random-" + checked
         );
         checked++;
      }
      assertEquals(64, checked);
   }

   @Test
   void cyclicOrderAndWindingDoNotChangeThePrism() {
      List<Vec3> base = polygon(
         new Vec3(-3.5, 4.5, 2.5),
         new Vec3(2.0, 1.0, -1.0),
         new Vec3(-1.0, 2.0, 2.0)
      );
      Vec3 extrusion = new Vec3(5.0, -4.0, 3.0);
      for (FillMode mode : FillMode.values()) {
         Set<BlockPos> expected = PrismGenerator.generatePolygon(base, extrusion, mode, 100000);
         for (int start = 0; start < base.size(); start++) {
            for (int direction : new int[]{1, -1}) {
               ArrayList<Vec3> reordered = new ArrayList<>(base.size());
               for (int offset = 0; offset < base.size(); offset++) {
                  reordered.add(base.get(Math.floorMod(start + direction * offset, base.size())));
               }
               assertEquals(expected, PrismGenerator.generatePolygon(reordered, extrusion, mode, 100000));
            }
         }

      }
   }

   private static void assertPrismContract(List<Vec3> base, Vec3 extrusion, String name) {
      try {
         Set<BlockPos> face = PolygonFaceGenerator.generate(base, FillMode.SOLID, 100000);
         Set<BlockPos> solid = PrismGenerator.generatePolygon(base, extrusion, FillMode.SOLID, 100000);
         Set<BlockPos> hollow = PrismGenerator.generatePolygon(base, extrusion, FillMode.HOLLOW, 100000);
         Set<BlockPos> outline = PrismGenerator.generatePolygon(base, extrusion, FillMode.OUTLINE, 100000);
         LinkedHashSet<BlockPos> required = new LinkedHashSet<>();
         LinkedHashSet<BlockPos> allowed = new LinkedHashSet<>();
         List<BlockPos> offsets = directedPath(BlockPos.ZERO, BlockPos.containing(extrusion));
         for (BlockPos offset : offsets) {
            for (BlockPos point : face) {
               BlockPos translated = point.offset(offset);
               required.add(translated);
               allowed.add(translated);
            }
         }
         for (int index = 1; index < offsets.size(); index++) {
            for (BlockPos point : face) {
               addShortestTransitionCells(
                  allowed,
                  point.offset(offsets.get(index - 1)),
                  point.offset(offsets.get(index))
               );
            }
         }

         assertFalse(face.isEmpty());
         assertTrue(solid.containsAll(required));
         assertTrue(allowed.containsAll(solid), () -> "escaped shortest transitions " + difference(solid, allowed));
         assertTrue(is26Connected(solid));
         assertFalse(hasEnclosedAir(solid));
         assertEquals(expectedHollow(solid), hollow);
         assertTrue(solid.containsAll(outline));
      } catch (AssertionError error) {
         throw new AssertionError(name + " base=" + base + " extrusion=" + extrusion + ": " + error.getMessage(), error);
      }
   }

   private static List<Vec3> polygon(Vec3 origin, Vec3 first, Vec3 second) {
      ArrayList<Vec3> result = new ArrayList<>(POLYGON.length);
      for (int[] point : POLYGON) {
         result.add(origin.add(first.scale(point[0])).add(second.scale(point[1])));
      }
      return List.copyOf(result);
   }

   private static Vec3 randomCenter(Random random) {
      return new Vec3(
         random.nextInt(-8, 9) + 0.5,
         random.nextInt(-8, 9) + 0.5,
         random.nextInt(-8, 9) + 0.5
      );
   }

   private static Vec3 tiltedVector(Random random) {
      return new Vec3(nonZero(random, 1, 4), nonZero(random, 1, 4), nonZero(random, 1, 4));
   }

   private static boolean allComponentsNonZero(Vec3 vector) {
      return vector.x != 0.0 && vector.y != 0.0 && vector.z != 0.0;
   }

   private static int nonZero(Random random, int minimum, int maximum) {
      int value = random.nextInt(minimum, maximum + 1);
      return random.nextBoolean() ? value : -value;
   }

   private static Vec3 vector(int firstAxis, int firstValue, int secondAxis, int secondValue) {
      double[] values = {0.0, 0.0, 0.0};
      values[firstAxis] = firstValue;
      values[secondAxis] = secondValue;
      return new Vec3(values[0], values[1], values[2]);
   }

   private static List<BlockPos> directedPath(BlockPos start, BlockPos end) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(start, end));
      if (!result.isEmpty() && !result.getFirst().equals(start)) {
         Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static void addShortestTransitionCells(Set<BlockPos> result, BlockPos from, BlockPos to) {
      int distance = manhattan(from, to);
      for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++) {
         for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()); y++) {
            for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
               BlockPos candidate = new BlockPos(x, y, z);
               if (manhattan(from, candidate) + manhattan(candidate, to) == distance) {
                  result.add(candidate);
               }
            }
         }
      }
   }

   private static int manhattan(BlockPos first, BlockPos second) {
      return Math.abs(first.getX() - second.getX())
         + Math.abs(first.getY() - second.getY())
         + Math.abs(first.getZ() - second.getZ());
   }

   private static Set<BlockPos> difference(Set<BlockPos> first, Set<BlockPos> second) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(first);
      result.removeAll(second);
      return result;
   }

   private static Set<BlockPos> expectedHollow(Set<BlockPos> solid) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (BlockPos position : solid) {
         for (Direction direction : Direction.values()) {
            if (!solid.contains(position.relative(direction))) {
               result.add(position);
               break;
            }
         }
      }
      return result;
   }

   private static boolean is26Connected(Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return true;
      }
      HashSet<BlockPos> visited = new HashSet<>();
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      open.add(blocks.iterator().next());
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
                  if (blocks.contains(neighbor) && !visited.contains(neighbor)) {
                     open.addLast(neighbor);
                  }
               }
            }
         }
      }
      return visited.size() == blocks.size();
   }

   private static boolean hasEnclosedAir(Set<BlockPos> solid) {
      int minX = solid.stream().mapToInt(BlockPos::getX).min().orElse(0) - 1;
      int minY = solid.stream().mapToInt(BlockPos::getY).min().orElse(0) - 1;
      int minZ = solid.stream().mapToInt(BlockPos::getZ).min().orElse(0) - 1;
      int maxX = solid.stream().mapToInt(BlockPos::getX).max().orElse(0) + 1;
      int maxY = solid.stream().mapToInt(BlockPos::getY).max().orElse(0) + 1;
      int maxZ = solid.stream().mapToInt(BlockPos::getZ).max().orElse(0) + 1;
      HashSet<BlockPos> exterior = new HashSet<>();
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      open.add(new BlockPos(minX, minY, minZ));
      while (!open.isEmpty()) {
         BlockPos current = open.removeFirst();
         if (!exterior.add(current)) {
            continue;
         }
         for (Direction direction : Direction.values()) {
            BlockPos neighbor = current.relative(direction);
            if (neighbor.getX() >= minX && neighbor.getX() <= maxX
               && neighbor.getY() >= minY && neighbor.getY() <= maxY
               && neighbor.getZ() >= minZ && neighbor.getZ() <= maxZ
               && !solid.contains(neighbor) && !exterior.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      for (int x = minX; x <= maxX; x++) {
         for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
               BlockPos position = new BlockPos(x, y, z);
               if (!solid.contains(position) && !exterior.contains(position)) {
                  return true;
               }
            }
         }
      }
      return false;
   }
}
