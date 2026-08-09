package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FaceTieBiasGenerationTest {
   @Test
   void randomStandaloneOutlinesTranslateTheirAuthoredEdgesForBothBiases() {
      Random random = new Random(0x54494542494153L);
      int checked = 0;
      while (checked < 256) {
         Vec3 first = randomVector(random);
         Vec3 second = randomVector(random);
         if (first.cross(second).lengthSqr() < 1.0) {
            continue;
         }
         Vec3 origin = new Vec3(
            random.nextInt(-8, 9) + 0.5,
            random.nextInt(-8, 9) + 0.5,
            random.nextInt(-8, 9) + 0.5
         );
         List<Vec3> vertices = List.of(origin, origin.add(first), origin.add(first).add(second), origin.add(second));
         for (LineTieBias bias : LineTieBias.values()) {
            Set<BlockPos> outline = QuadFaceGenerator.generate(
               vertices, FillMode.OUTLINE, 100_000, BlockGenerationObserver.NONE, bias
            );
            assertEquals(expectedFaceOutline(vertices, bias), outline, bias + " vertices=" + vertices);
            assertTrue(
               vertices.stream().map(BlockPos::containing).allMatch(outline::contains),
               bias + " vertices=" + vertices
            );
         }
         checked++;
      }
   }

   @Test
   void quadOutlineUsesTranslatedEdgesAtAnExactHalfTie() {
      List<Vec3> vertices = tiedBase();
      Set<BlockPos> defaultOutline = expectedFaceOutline(vertices, LineTieBias.DEFAULT);
      Set<BlockPos> oppositeOutline = expectedFaceOutline(vertices, LineTieBias.OPPOSITE);

      assertNotEquals(defaultOutline, oppositeOutline);
      for (LineTieBias bias : LineTieBias.values()) {
         Set<BlockPos> outline = QuadFaceGenerator.generate(
            vertices, FillMode.OUTLINE, 10_000, BlockGenerationObserver.NONE, bias
         );
         assertEquals(expectedFaceOutline(vertices, bias), outline, bias.name());
         assertTrue(vertices.stream().map(BlockPos::containing).allMatch(outline::contains), bias.name());
      }
   }

   @Test
   void tiltedVolumeUsesTheSelectedBiasForEveryFinalFaceOutline() {
      List<Vec3> base = tiedBase();
      Vec3 extrusion = new Vec3(1.0, -2.0, 0.0);

      for (LineTieBias bias : LineTieBias.values()) {
         LinkedHashSet<BlockPos> expectedOutline = new LinkedHashSet<>();
         LinkedHashSet<BlockPos> expectedShell = new LinkedHashSet<>();
         for (List<Vec3> face : boxFaces(base, extrusion)) {
            ProjectedBresenhamFace.Frame faceFrame = ProjectedBresenhamFace.Frame.create(face);
            BresenhamFaceSweep.Attempt attempt = BoundaryInterpolatedFaceRasterizer.attempt(
               faceFrame,
               bias,
               10_000,
               BlockGenerationObserver.NONE
            );
            assertTrue(attempt.succeeded(), bias + " face=" + face + " status=" + attempt.status());
            BresenhamFaceSweep.Result rasterized = attempt.result();
            expectedOutline.addAll(rasterized.outline());
            expectedShell.addAll(clipToOwnedBoundary(faceFrame, rasterized.fill(), rasterized.outline()));
         }

         Set<BlockPos> outline = TiltedBoxGenerator.generate(
            base, extrusion, FillMode.OUTLINE, 10_000, BlockGenerationObserver.NONE, bias
         );
         Set<BlockPos> solid = TiltedBoxGenerator.generate(
            base, extrusion, FillMode.SOLID, 10_000, BlockGenerationObserver.NONE, bias
         );
         Set<BlockPos> hollow = TiltedBoxGenerator.generate(
            base, extrusion, FillMode.HOLLOW, 10_000, BlockGenerationObserver.NONE, bias
         );
         BresenhamColumnVolume.Result volume = BresenhamColumnVolume.generate(
            base, extrusion, 10_000, BlockGenerationObserver.NONE, bias
         );

         assertEquals(expectedOutline, outline, bias.name());
         assertEquals(expectedShell, volume.shell(), bias.name());
         assertTrue(solid.containsAll(corners(base, extrusion)), bias.name());
         assertTrue(hollow.stream().allMatch(solid::contains), bias.name());
      }
   }

   private static List<List<Vec3>> boxFaces(List<Vec3> base, Vec3 extrusion) {
      List<Vec3> top = base.stream().map(point -> point.add(extrusion)).toList();
      java.util.ArrayList<List<Vec3>> result = new java.util.ArrayList<>();
      result.add(List.copyOf(base));
      result.add(List.copyOf(top));
      for (int index = 0; index < 4; index++) {
         int next = (index + 1) % 4;
         result.add(List.of(base.get(index), base.get(next), top.get(next), top.get(index)));
      }
      return result;
   }

   private static Set<BlockPos> corners(List<Vec3> base, Vec3 extrusion) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (Vec3 vertex : base) {
         result.add(BlockPos.containing(vertex));
         result.add(BlockPos.containing(vertex.add(extrusion)));
      }
      return result;
   }

   private static Set<BlockPos> clipToOwnedBoundary(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill,
      Set<BlockPos> outline
   ) {
      Set<FaceColumn> boundaryColumns = outline.stream()
         .map(block -> faceColumn(frame, block))
         .collect(java.util.stream.Collectors.toSet());
      return fill.stream()
         .filter(block -> outline.contains(block) || !boundaryColumns.contains(faceColumn(frame, block)))
         .collect(java.util.stream.Collectors.toSet());
   }

   private static FaceColumn faceColumn(ProjectedBresenhamFace.Frame frame, BlockPos block) {
      long[] delta = {
         (long)block.getX() - frame.anchor().getX(),
         (long)block.getY() - frame.anchor().getY(),
         (long)block.getZ() - frame.anchor().getZ()
      };
      int[] order = frame.order();
      int[] signs = frame.signs();
      return new FaceColumn(
         Math.toIntExact(delta[order[0]] * signs[0]),
         Math.toIntExact(delta[order[1]] * signs[1])
      );
   }

   private record FaceColumn(int u, int v) {
   }

   private static List<Vec3> tiedBase() {
      Vec3 origin = new Vec3(0.5, 0.5, 0.5);
      Vec3 first = new Vec3(2.0, 1.0, 0.0);
      Vec3 second = new Vec3(0.0, 0.0, 2.0);
      return List.of(origin, origin.add(first), origin.add(first).add(second), origin.add(second));
   }

   private static Vec3 randomVector(Random random) {
      return new Vec3(nonZero(random), nonZero(random), nonZero(random));
   }

   private static int nonZero(Random random) {
      int value = random.nextInt(1, 9);
      return random.nextBoolean() ? value : -value;
   }

   private static Set<BlockPos> expectedFaceOutline(List<Vec3> vertices, LineTieBias bias) {
      List<BlockPos> corners = vertices.stream().map(BlockPos::containing).toList();
      List<BlockPos> first = LineGenerator.path(corners.get(0), corners.get(1), bias);
      List<BlockPos> second = LineGenerator.path(corners.get(0), corners.get(3), bias);
      BlockPos firstOffset = corners.get(3).subtract(corners.get(0));
      BlockPos secondOffset = corners.get(1).subtract(corners.get(0));
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      result.addAll(first);
      result.addAll(second);
      first.forEach(block -> result.add(block.offset(firstOffset)));
      second.forEach(block -> result.add(block.offset(secondOffset)));
      return result;
   }
}
