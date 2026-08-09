package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PlanarFaceRasterizerTest {
   @Test
   void yDominantPlaneUsesXzAsInputAndKeepsContiguousPhysicalTransitions() {
      List<Vec3> vertices = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(4.5, 1.5, 0.5),
         new Vec3(4.5, 3.5, 4.5),
         new Vec3(0.5, 2.5, 4.5)
      );

      Set<BlockPos> result = PlanarFaceRasterizer.generate(vertices, 1000);
      Set<String> xzColumns = result.stream()
         .map(position -> position.getX() + "," + position.getZ())
         .collect(Collectors.toSet());

      assertEquals(25, xzColumns.size());
      assertTrue(result.size() >= xzColumns.size());
      for (int x = 0; x <= 4; x++) {
         for (int z = 0; z <= 4; z++) {
            assertTrue(xzColumns.contains(x + "," + z), x + "," + z);
         }
      }
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices);
      assertColumnSpansContiguous(frame, result);
      assertLowerAndUpperAirAreSeparated(frame, result);
   }

   @Test
   void cyclicAndReversedVertexOrderProduceTheSameVoxels() {
      List<Vec3> vertices = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(5.5, 1.5, -1.5),
         new Vec3(3.5, 5.5, 1.5),
         new Vec3(-1.5, 4.5, 3.5)
      );
      List<Vec3> cycled = List.of(vertices.get(1), vertices.get(2), vertices.get(3), vertices.get(0));
      List<Vec3> reversed = List.of(vertices.get(3), vertices.get(2), vertices.get(1), vertices.get(0));

      Set<BlockPos> expected = PlanarFaceRasterizer.generate(vertices, 1000);
      ProjectedBresenhamFace.Raster expectedRaster = ProjectedBresenhamFace.raster(vertices);
      ProjectedBresenhamFace.Raster reversedRaster = ProjectedBresenhamFace.raster(reversed);

      assertEquals(expected, PlanarFaceRasterizer.generate(cycled, 1000));
      assertEquals(expectedRaster.logical(), reversedRaster.logical(), "logical scan layer changed with winding");
      assertEquals(expected, PlanarFaceRasterizer.generate(reversed, 1000));
   }

   @Test
   void outputLimitIsStrict() {
      List<Vec3> vertices = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(20.5, 4.5, 1.5),
         new Vec3(17.5, 16.5, 9.5),
         new Vec3(-2.5, 12.5, 8.5)
      );

      int fullSize = PlanarFaceRasterizer.generate(vertices, 10000).size();
      for (int limit = 1; limit <= Math.min(40, fullSize); limit++) {
         assertEquals(limit, PlanarFaceRasterizer.generate(vertices, limit).size(), "limit " + limit);
      }
   }

   @Test
   void fixedFaceLimitReturnsARejectableSentinelInsteadOfAnotherRasterizer() {
      List<Vec3> vertices = List.of(
         Vec3.atCenterOf(new BlockPos(282, 63, 127)),
         Vec3.atCenterOf(new BlockPos(279, 70, 131)),
         Vec3.atCenterOf(new BlockPos(273, 70, 126)),
         Vec3.atCenterOf(new BlockPos(276, 63, 122))
      );

      Set<BlockPos> limited = PlanarFaceRasterizer.generate(vertices, 58);
      Set<BlockPos> exact = PlanarFaceRasterizer.generate(vertices, 59);

      assertEquals(58, limited.size(), "the public API must preserve the one-past-limit rejection signal");
      assertEquals(59, exact.size());
      assertTrue(
         !limited.equals(ProjectedBresenhamFace.raster(vertices).fill()),
         "a translated-face limit silently switched to the old projected raster"
      );
   }

   @Test
   void noValidCandidateUsesOwnedAnalyticFallbackAndLimitRemainsAtomic() {
      List<Vec3> vertices = List.of(
         Vec3.atCenterOf(new BlockPos(282, 63, 127)),
         Vec3.atCenterOf(new BlockPos(279, 70, 131)),
         Vec3.atCenterOf(new BlockPos(273, 70, 126)),
         Vec3.atCenterOf(new BlockPos(276, 63, 122))
      );
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices);
      Set<BlockPos> expected = TranslatedScanFaceRasterizer.forceEmergencyFallbackForTesting(
         frame,
         LineTieBias.DEFAULT,
         1_000
      ).result().fill();

      Set<BlockPos> fallback = PlanarFaceRasterizer.forceCandidateFailureForTesting(
         vertices,
         1_000,
         BlockGenerationObserver.NONE,
         LineTieBias.DEFAULT
      );

      assertEquals(expected, fallback, "NO_VALID must use the translated analytic fallback");
      assertTrue(fallback.containsAll(PlanarFaceRasterizer.outline(vertices, 1_000, BlockGenerationObserver.NONE)));
      assertColumnSpansContiguous(frame, fallback);
      assertLowerAndUpperAirAreSeparated(frame, fallback);

      Set<BlockPos> generated = new HashSet<>();
      BlockGenerationObserver observer = new BlockGenerationObserver() {
         @Override
         public void onGenerated(BlockPos position) {
            generated.add(position.immutable());
         }
      };
      int limit = fallback.size() - 1;
      Set<BlockPos> limited = PlanarFaceRasterizer.forceCandidateFailureForTesting(
         vertices,
         limit,
         observer,
         LineTieBias.DEFAULT
      );
      assertTrue(GenerationLimitExceeded.is(limited));
      assertEquals(limit, limited.size());
      assertTrue(generated.isEmpty(), "a limited emergency face published a prefix");
   }

   @Test
   void steepOwnedEdgesCanShareAProjectionColumnWithoutLeavingHoles() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(-3.0, -2.0, -1.0);
      Vec3 edgeB = new Vec3(-3.0, -1.0, -2.0);
      List<Vec3> vertices = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));

      Set<BlockPos> result = PlanarFaceRasterizer.generate(vertices, 1000);
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(vertices);
      BresenhamFaceSweep.Result swept = BresenhamFaceSweep.generate(frame);

      assertEquals(swept.fill(), result);
      assertTrue(result.containsAll(swept.outline()));
      assertColumnSpansContiguous(frame, result);
      assertLowerAndUpperAirAreSeparated(frame, result);
      assertTrue(
         result.stream().allMatch(position ->
            position.getX() >= -6 && position.getX() <= 0
               && position.getY() >= -3 && position.getY() <= 0
               && position.getZ() >= -3 && position.getZ() <= 0
         ),
         () -> "transition escaped hull bounds " + result
      );
   }

   @Test
   void diagonalScanTransitionsAddProjectedColumnsWithoutStackingHeight() {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      Vec3 edgeA = new Vec3(3.0, 3.0, 0.0);
      Vec3 edgeB = new Vec3(-1.0, 1.0, 4.0);
      List<Vec3> vertices = List.of(first, first.add(edgeA), first.add(edgeA).add(edgeB), first.add(edgeB));

      ProjectedBresenhamFace.Raster raster = ProjectedBresenhamFace.raster(vertices);

      assertEquals(1, raster.maximumThickness());
      assertEquals(raster.logical(), raster.fill());
      assertTrue(projectedFourConnected(raster));
   }

   private static boolean projectedFourConnected(ProjectedBresenhamFace.Raster raster) {
      if (raster.fill().isEmpty()) {
         return true;
      }
      Set<String> columns = raster.fill().stream().map(position -> projectedKey(raster.frame(), position)).collect(Collectors.toSet());
      java.util.HashSet<String> visited = new java.util.HashSet<>();
      java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
      open.add(projected(raster.frame(), raster.fill().iterator().next()));
      while (!open.isEmpty()) {
         int[] current = open.removeFirst();
         String key = current[0] + "," + current[1];
         if (!visited.add(key)) {
            continue;
         }
         for (int[] step : List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, 1}, new int[]{0, -1})) {
            int[] neighbor = {current[0] + step[0], current[1] + step[1]};
            String neighborKey = neighbor[0] + "," + neighbor[1];
            if (columns.contains(neighborKey) && !visited.contains(neighborKey)) {
               open.addLast(neighbor);
            }
         }
      }
      return visited.size() == columns.size();
   }

   private static String projectedKey(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      int[] point = projected(frame, position);
      return point[0] + "," + point[1];
   }

   private static int[] projected(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return new int[]{
         (int)(delta[frame.order()[0]] * frame.signs()[0]),
         (int)(delta[frame.order()[1]] * frame.signs()[1])
      };
   }

   private static void assertColumnSpansContiguous(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> blocks
   ) {
      Map<String, Set<Integer>> columns = new HashMap<>();
      for (BlockPos block : blocks) {
         columns.computeIfAbsent(projectedKey(frame, block), ignored -> new HashSet<>())
            .add(localHeight(frame, block));
      }
      assertTrue(columns.values().stream().allMatch(heights ->
         heights.size() == heights.stream().mapToInt(Integer::intValue).max().orElseThrow()
            - heights.stream().mapToInt(Integer::intValue).min().orElseThrow() + 1
      ));
   }

   private static int localHeight(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return Math.toIntExact(delta[frame.order()[2]] * frame.signs()[2]);
   }

   private static void assertLowerAndUpperAirAreSeparated(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> blocks
   ) {
      Map<Projection, Set<Integer>> columns = new HashMap<>();
      for (BlockPos block : blocks) {
         int[] projected = projected(frame, block);
         columns.computeIfAbsent(new Projection(projected[0], projected[1]), ignored -> new HashSet<>())
            .add(localHeight(frame, block));
      }
      int minimum = columns.values().stream().flatMap(Set::stream).mapToInt(Integer::intValue).min().orElseThrow() - 2;
      int maximum = columns.values().stream().flatMap(Set::stream).mapToInt(Integer::intValue).max().orElseThrow() + 2;
      Set<AirCell> solid = new HashSet<>();
      columns.forEach((column, heights) -> heights.forEach(height -> solid.add(new AirCell(column, height))));
      Set<AirCell> visited = new HashSet<>();
      ArrayDeque<AirCell> open = new ArrayDeque<>();
      for (Projection column : columns.keySet()) {
         AirCell cell = new AirCell(column, minimum);
         if (visited.add(cell)) {
            open.addLast(cell);
         }
      }
      while (!open.isEmpty()) {
         AirCell current = open.removeFirst();
         assertTrue(current.height() < maximum, "lower air reached upper air through " + current);
         for (int step : new int[]{-1, 1}) {
            int height = current.height() + step;
            if (height < minimum || height > maximum) {
               continue;
            }
            AirCell next = new AirCell(current.column(), height);
            if (!solid.contains(next) && visited.add(next)) {
               open.addLast(next);
            }
         }
         for (Projection neighbor : List.of(
            new Projection(current.column().u() + 1, current.column().v()),
            new Projection(current.column().u() - 1, current.column().v()),
            new Projection(current.column().u(), current.column().v() + 1),
            new Projection(current.column().u(), current.column().v() - 1)
         )) {
            AirCell next = new AirCell(neighbor, current.height());
            if (columns.containsKey(neighbor) && !solid.contains(next) && visited.add(next)) {
               open.addLast(next);
            }
         }
      }
   }

   private static boolean isSixConnected(Set<BlockPos> blocks) {
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
         for (BlockPos offset : List.of(
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
         )) {
            BlockPos neighbor = current.offset(offset);
            if (blocks.contains(neighbor) && !visited.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      return visited.size() == blocks.size();
   }

   private static List<Integer> sixNeighborComponentSizes(Set<BlockPos> blocks) {
      HashSet<BlockPos> remaining = new HashSet<>(blocks);
      java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
      while (!remaining.isEmpty()) {
         int size = 0;
         ArrayDeque<BlockPos> open = new ArrayDeque<>();
         open.add(remaining.iterator().next());
         while (!open.isEmpty()) {
            BlockPos current = open.removeFirst();
            if (!remaining.remove(current)) {
               continue;
            }
            size++;
            for (BlockPos offset : List.of(
               new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
               new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
               new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
            )) {
               BlockPos neighbor = current.offset(offset);
               if (remaining.contains(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
         result.add(size);
      }
      result.sort(java.util.Comparator.reverseOrder());
      return List.copyOf(result);
   }

   private record Projection(int u, int v) {
   }

   private record AirCell(Projection column, int height) {
   }
}
