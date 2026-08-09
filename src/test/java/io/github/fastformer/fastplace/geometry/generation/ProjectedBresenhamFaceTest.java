package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProjectedBresenhamFaceTest {
   private static final int[][] ORDERS = {
      {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
   };

   @Test
   void allSignedAxisFramesRemainCompleteSingleLayersWithSharedOutlines() {
      List<Vec3> vertices = parallelogram(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(7.0, 4.0, 2.0),
         new Vec3(-2.0, 6.0, 5.0)
      );
      ProjectedBresenhamFace.Raster expected = ProjectedBresenhamFace.raster(vertices);
      for (int[] order : ORDERS) {
         for (int mask = 0; mask < 8; mask++) {
            int[] signs = {
               (mask & 1) == 0 ? -1 : 1,
               (mask & 2) == 0 ? -1 : 1,
               (mask & 4) == 0 ? -1 : 1
            };
            List<Vec3> transformed = vertices.stream().map(vertex -> transform(vertex, order, signs)).toList();
            ProjectedBresenhamFace.Raster raster = ProjectedBresenhamFace.raster(transformed);
            assertEquals(expected.fill().size(), raster.fill().size(), () -> frame(order, signs));
            assertEquals(expected.outline().size(), raster.outline().size(), () -> frame(order, signs));
            assertTrue(raster.logicalSingleLayer(), () -> frame(order, signs));
            assertEquals(1, raster.maximumThickness(), () -> frame(order, signs));
            assertTrue(raster.fill().containsAll(raster.outline()), () -> frame(order, signs));
         }
      }
   }

   @Test
   void reportedDiagonalFaceHasNoMissingOrDoubleProjectionColumn() {
      List<Vec3> vertices = parallelogram(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(-3.0, -3.0, -3.0),
         new Vec3(-3.0, 3.0, -3.0)
      );
      ProjectedBresenhamFace.Raster raster = ProjectedBresenhamFace.raster(vertices);
      Set<BlockPos> solid = QuadFaceGenerator.generate(vertices, FillMode.SOLID, 10000);
      assertEquals(projectedColumns(raster), projectedColumns(raster.frame(), solid));
      assertTrue(solid.containsAll(raster.outline()));
      assertEquals(raster.logical().size(), raster.projectedColumnCount());
      assertTrue(raster.logicalSingleLayer());
      assertEquals(1, raster.maximumThickness());
      assertTrue(is26Connected(solid));
   }

   @Test
   void polygonStartVertexAndWindingKeepTheSameFaceContract() {
      List<Vec3> vertices = parallelogram(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(7.0, 4.0, 2.0),
         new Vec3(-2.0, 6.0, 5.0)
      );
      Set<BlockPos> expected = ProjectedBresenhamFace.raster(vertices).fill();

      for (int start = 0; start < vertices.size(); start++) {
         for (int direction : new int[]{1, -1}) {
            java.util.ArrayList<Vec3> reordered = new java.util.ArrayList<>();
            for (int offset = 0; offset < vertices.size(); offset++) {
               reordered.add(vertices.get(Math.floorMod(start + direction * offset, vertices.size())));
            }
            int startIndex = start;
            int winding = direction;
            ProjectedBresenhamFace.Raster raster = ProjectedBresenhamFace.raster(reordered);
            assertEquals(expected, raster.fill(), () -> "start=" + startIndex + " direction=" + winding);
            assertTrue(raster.logicalSingleLayer());
            assertEquals(1, raster.maximumThickness());
            assertTrue(is26Connected(raster.fill()));
         }
      }
   }

   @Test
   void coordinatePlaneRandomDirectionsAndSlopesRemainCompleteAndOrderInvariant() {
      java.util.Random random = new java.util.Random(0x504C414E45434F4FL);
      int checked = 0;
      for (int normalAxis = 0; normalAxis < 3; normalAxis++) {
         int firstAxis = (normalAxis + 1) % 3;
         int secondAxis = (normalAxis + 2) % 3;
         for (int sample = 0; sample < 32; sample++) {
            Vec3 first = sample % 4 == 0
               ? vector(firstAxis, nonZero(random, 1, 12), secondAxis, 0)
               : vector(firstAxis, nonZero(random, 1, 12), secondAxis, nonZero(random, 1, 12));
            Vec3 second = sample % 4 == 0
               ? vector(firstAxis, 0, secondAxis, nonZero(random, 1, 12))
               : vector(firstAxis, nonZero(random, 1, 12), secondAxis, nonZero(random, 1, 12));
            if (first.cross(second).lengthSqr() < 1.0E-7) {
               sample--;
               continue;
            }
            assertFaceContract(parallelogram(randomCenter(random), first, second), "coordinate-" + checked);
            checked++;
         }
      }
      assertEquals(96, checked);
   }

   @Test
   void fullyTiltedRandomAnglesAndSlopesRemainCompleteAndOrderInvariant() {
      java.util.Random random = new java.util.Random(0x46554C4C54494C54L);
      int checked = 0;
      int attempts = 0;
      while (checked < 128 && attempts++ < 10000) {
         Vec3 first = tiltedVector(random, 1, 12);
         Vec3 second = tiltedVector(random, 1, 12);
         Vec3 normal = first.cross(second);
         if (normal.lengthSqr() < 2.0 || !allComponentsNonZero(normal)) {
            continue;
         }
         assertFaceContract(parallelogram(randomCenter(random), first, second), "tilted-" + checked);
         checked++;
      }
      assertEquals(128, checked);
   }

   @Test
   void partiallyTiltedRandomPlanesWithEachZeroNormalAxisRemainValid() {
      java.util.Random random = new java.util.Random(0x5041525449414C4CL);
      int checked = 0;
      for (int zeroAxis = 0; zeroAxis < 3; zeroAxis++) {
         for (int sample = 0; sample < 16; sample++) {
            int first = nonZero(random, 1, 12);
            int second = nonZero(random, 1, 12);
            int height = nonZero(random, 1, 12);
            Vec3[] basis = partialTiltBasis(zeroAxis, first, second, height);
            assertFaceContract(parallelogram(randomCenter(random), basis[0], basis[1]), "partial-" + checked);
            checked++;
         }
      }
      assertEquals(48, checked);
   }

   @Test
   void equalAndNearEqualDominantNormalComponentsRemainStable() {
      List<Vec3> normals = List.of(
         new Vec3(1.0, 1.0, 1.0),
         new Vec3(2.0, 2.0, 1.0),
         new Vec3(3.0, 2.0, 1.0)
      );
      for (Vec3 normal : normals) {
         Vec3 first = new Vec3(normal.y, -normal.x, 0.0);
         Vec3 second = normal.cross(first);
         List<Vec3> vertices = parallelogram(new Vec3(0.5, 0.5, 0.5), first, second);
         for (int[] order : ORDERS) {
            for (int mask = 0; mask < 8; mask++) {
               int[] signs = {
                  (mask & 1) == 0 ? -1 : 1,
                  (mask & 2) == 0 ? -1 : 1,
                  (mask & 4) == 0 ? -1 : 1
               };
               assertFaceContract(
                  vertices.stream().map(point -> transform(point, order, signs)).toList(),
                  "normal=" + normal + " " + frame(order, signs)
               );
            }
         }
      }
   }

   @Test
   void shallowAndSteepPlanesRemainValidInEverySignedAxisFrame() {
      for (List<Vec3> vertices : List.of(
         parallelogram(new Vec3(0.5, 0.5, 0.5), new Vec3(24.0, 1.0, 0.0), new Vec3(0.0, 24.0, 1.0)),
         parallelogram(new Vec3(0.5, 0.5, 0.5), new Vec3(1.0, 24.0, 0.0), new Vec3(0.0, 1.0, 24.0))
      )) {
         for (int[] order : ORDERS) {
            for (int mask = 0; mask < 8; mask++) {
               int[] signs = {
                  (mask & 1) == 0 ? -1 : 1,
                  (mask & 2) == 0 ? -1 : 1,
                  (mask & 4) == 0 ? -1 : 1
               };
               assertFaceContract(
                  vertices.stream().map(point -> transform(point, order, signs)).toList(),
                  frame(order, signs)
               );
            }
         }
      }
   }

   private static void assertFaceContract(List<Vec3> vertices, String name) {
      try {
         ProjectedBresenhamFace.Raster expected = ProjectedBresenhamFace.raster(vertices);
         assertTrue(expected.logicalSingleLayer());
         assertEquals(expected.logical().size(), expected.projectedColumnCount());
         assertEquals(1, expected.maximumThickness());
         assertTrue(expected.fill().containsAll(expected.outline()));
         assertEquals(expected.logical(), expected.fill());
         assertTrue(is26Connected(expected.fill()));
         assertTrue(projectedFourConnected(expected));
         assertTrue(projectedDomainHasNoEnclosedHole(expected));
         assertTrue(vertices.stream().map(BlockPos::containing).allMatch(expected.outline()::contains));
         for (int start = 0; start < vertices.size(); start++) {
            for (int direction : new int[]{1, -1}) {
               java.util.ArrayList<Vec3> reordered = new java.util.ArrayList<>(vertices.size());
               for (int offset = 0; offset < vertices.size(); offset++) {
                  reordered.add(vertices.get(Math.floorMod(start + direction * offset, vertices.size())));
               }
               assertEquals(expected.fill(), ProjectedBresenhamFace.raster(reordered).fill());
            }
         }
      } catch (AssertionError error) {
         throw new AssertionError(name + " vertices=" + vertices + ": " + error.getMessage(), error);
      }
   }

   private static List<Vec3> parallelogram(Vec3 origin, Vec3 first, Vec3 second) {
      return List.of(origin, origin.add(first), origin.add(first).add(second), origin.add(second));
   }

   private static Vec3 transform(Vec3 point, int[] order, int[] signs) {
      double[] values = {point.x, point.y, point.z};
      return new Vec3(
         signs[0] * values[order[0]],
         signs[1] * values[order[1]],
         signs[2] * values[order[2]]
      );
   }

   private static Vec3 randomCenter(java.util.Random random) {
      return new Vec3(
         random.nextInt(-16, 17) + 0.5,
         random.nextInt(-16, 17) + 0.5,
         random.nextInt(-16, 17) + 0.5
      );
   }

   private static Vec3 tiltedVector(java.util.Random random, int minimum, int maximum) {
      return new Vec3(
         nonZero(random, minimum, maximum),
         nonZero(random, minimum, maximum),
         nonZero(random, minimum, maximum)
      );
   }

   private static boolean allComponentsNonZero(Vec3 vector) {
      return vector.x != 0.0 && vector.y != 0.0 && vector.z != 0.0;
   }

   private static Vec3[] partialTiltBasis(int zeroAxis, int first, int second, int height) {
      return switch (zeroAxis) {
         case 0 -> new Vec3[]{new Vec3(0.0, second, -first), new Vec3(height, 0.0, 0.0)};
         case 1 -> new Vec3[]{new Vec3(second, 0.0, -first), new Vec3(0.0, height, 0.0)};
         default -> new Vec3[]{new Vec3(second, -first, 0.0), new Vec3(0.0, 0.0, height)};
      };
   }

   private static int nonZero(java.util.Random random, int minimum, int maximum) {
      int magnitude = random.nextInt(minimum, maximum + 1);
      return random.nextBoolean() ? magnitude : -magnitude;
   }

   private static Vec3 vector(int firstAxis, int firstValue, int secondAxis, int secondValue) {
      double[] values = {0.0, 0.0, 0.0};
      values[firstAxis] = firstValue;
      values[secondAxis] = secondValue;
      return new Vec3(values[0], values[1], values[2]);
   }

   private static String frame(int[] order, int[] signs) {
      return "order=" + java.util.Arrays.toString(order) + " signs=" + java.util.Arrays.toString(signs);
   }

   private static boolean is26Connected(Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return true;
      }
      java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
      java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
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

   private static boolean projectedFourConnected(ProjectedBresenhamFace.Raster raster) {
      Set<Pixel> columns = projectedColumns(raster);
      if (columns.isEmpty()) {
         return true;
      }
      java.util.HashSet<Pixel> visited = new java.util.HashSet<>();
      java.util.ArrayDeque<Pixel> open = new java.util.ArrayDeque<>();
      open.add(columns.iterator().next());
      while (!open.isEmpty()) {
         Pixel current = open.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (Pixel step : CARDINAL_STEPS) {
            Pixel neighbor = current.offset(step.first(), step.second());
            if (columns.contains(neighbor) && !visited.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      return visited.size() == columns.size();
   }

   private static boolean projectedDomainHasNoEnclosedHole(ProjectedBresenhamFace.Raster raster) {
      Set<Pixel> occupied = projectedColumns(raster);
      if (occupied.isEmpty()) {
         return true;
      }
      int minimumFirst = occupied.stream().mapToInt(Pixel::first).min().orElseThrow() - 1;
      int maximumFirst = occupied.stream().mapToInt(Pixel::first).max().orElseThrow() + 1;
      int minimumSecond = occupied.stream().mapToInt(Pixel::second).min().orElseThrow() - 1;
      int maximumSecond = occupied.stream().mapToInt(Pixel::second).max().orElseThrow() + 1;
      java.util.HashSet<Pixel> exterior = new java.util.HashSet<>();
      java.util.ArrayDeque<Pixel> open = new java.util.ArrayDeque<>();
      open.add(new Pixel(minimumFirst, minimumSecond));
      while (!open.isEmpty()) {
         Pixel current = open.removeFirst();
         if (!exterior.add(current)) {
            continue;
         }
         for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
               if (first == 0 && second == 0) {
                  continue;
               }
               Pixel neighbor = current.offset(first, second);
               if (neighbor.first() >= minimumFirst && neighbor.first() <= maximumFirst
                  && neighbor.second() >= minimumSecond && neighbor.second() <= maximumSecond
                  && !occupied.contains(neighbor) && !exterior.contains(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
      }
      for (int first = minimumFirst; first <= maximumFirst; first++) {
         for (int second = minimumSecond; second <= maximumSecond; second++) {
            Pixel pixel = new Pixel(first, second);
            if (!occupied.contains(pixel) && !exterior.contains(pixel)) {
               return false;
            }
         }
      }
      return true;
   }

   private static Set<Pixel> projectedColumns(ProjectedBresenhamFace.Raster raster) {
      return projectedColumns(raster.frame(), raster.fill());
   }

   private static Set<Pixel> projectedColumns(ProjectedBresenhamFace.Frame frame, Set<BlockPos> blocks) {
      return blocks.stream().map(position -> {
         long[] delta = {
            (long)position.getX() - frame.anchor().getX(),
            (long)position.getY() - frame.anchor().getY(),
            (long)position.getZ() - frame.anchor().getZ()
         };
         return new Pixel(
            (int)(delta[frame.order()[0]] * frame.signs()[0]),
            (int)(delta[frame.order()[1]] * frame.signs()[1])
         );
      }).collect(java.util.stream.Collectors.toSet());
   }

   private static final List<Pixel> CARDINAL_STEPS = List.of(
      new Pixel(1, 0), new Pixel(-1, 0), new Pixel(0, 1), new Pixel(0, -1)
   );

   private record Pixel(int first, int second) {
      Pixel offset(int firstOffset, int secondOffset) {
         return new Pixel(this.first + firstOffset, this.second + secondOffset);
      }
   }
}
