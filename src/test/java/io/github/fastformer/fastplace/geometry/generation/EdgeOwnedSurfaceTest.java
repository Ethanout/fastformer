package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.FillMode;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class EdgeOwnedSurfaceTest {
   @Test
   void faceRetainsEveryAuthoredEdgeWithBothTieRules() {
      List<Vec3> face = base(new Vec3(23, 4, 7), new Vec3(-5, 19, 11));
      for (LineTieBias bias : LineTieBias.values()) {
         Set<BlockPos> outline = quad(face, FillMode.OUTLINE, bias);
         Set<BlockPos> surface = quad(face, FillMode.SOLID, bias);
         assertTrue(surface.containsAll(outline), "surface moved authored edge blocks");
         assertEquals(QuadFaceGenerator.generate(face, FillMode.OUTLINE, 100_000,
            BlockGenerationObserver.NONE, bias, FaceRasterizationMode.POINT_SWEEP), outline);
      }
   }

   @Test
   void uprightRotatedBoxHasTheSameSectionAtEveryHeight() {
      List<Vec3> base = base(new Vec3(20, 0, 7), new Vec3(-7, 0, 20));
      Vec3 extrusion = new Vec3(0, 24, 0);
      for (LineTieBias bias : LineTieBias.values()) {
         Set<BlockPos> expected = quad(base, FillMode.SOLID, bias);
         Set<BlockPos> solid = box(base, extrusion, FillMode.SOLID, bias);
         for (int y = 0; y <= 24; y++) {
            Set<BlockPos> section = new HashSet<>();
            for (BlockPos p : solid) {
               if (p.getY() == y) section.add(new BlockPos(p.getX(), 0, p.getZ()));
            }
            assertTrue(expected.equals(section), "side groove at height " + y + " with " + bias
               + " missing=" + difference(expected, section) + " extra=" + difference(section, expected));
         }
      }
   }

   @Test
   void tiltedBoxesKeepEdgesAndHaveOneSharedSolidAndHollowBoundary() {
      Random random = new Random(470139);
      int checked = 0;
      while (checked < 40) {
         Vec3 a = new Vec3(random.nextInt(1, 5), random.nextInt(-4, 5), random.nextInt(-4, 5));
         Vec3 b = new Vec3(-a.y, a.x, 0);
         Vec3 normal = a.cross(b);
         double divisor = gcd(gcd((int)normal.x, (int)normal.y), (int)normal.z);
         Vec3 extrusion = new Vec3(normal.x / divisor, normal.y / divisor, normal.z / divisor);
         List<Vec3> base = base(a, b);
         for (LineTieBias bias : LineTieBias.values()) {
            Set<BlockPos> solid = box(base, extrusion, FillMode.SOLID, bias);
            assertFalse(GenerationFailed.is(solid), "failed solid: " + base + " / " + extrusion);
            assertFalse(solid.isEmpty());
            Set<BlockPos> hollow = box(base, extrusion, FillMode.HOLLOW, bias);
            Set<BlockPos> outline = box(base, extrusion, FillMode.OUTLINE, bias);
            assertTrue(solid.containsAll(outline), "solid lost fixed edges");
            assertTrue(hollow.containsAll(outline), "solid buried fixed edges " + difference(outline, hollow)
               + " base=" + base + " extrusion=" + extrusion + " bias=" + bias);
            Set<BlockPos> expected = new HashSet<>();
            for (BlockPos block : solid) {
               for (Direction direction : Direction.values()) {
                  if (!solid.contains(block.relative(direction))) {
                     expected.add(block);
                     break;
                  }
               }
            }
            assertTrue(hollow.containsAll(expected), "hollow lost the solid's outer boundary");
            assertTrue(solid.containsAll(hollow), "hollow extended the solid's outer boundary");
            Set<BlockPos> shell = new HashSet<>(quad(base, FillMode.SOLID, bias));
            List<Vec3> top = base.stream().map(p -> p.add(extrusion)).toList();
            shell.addAll(quad(top, FillMode.SOLID, bias));
            for (int i = 0; i < 4; i++) {
               int next = (i + 1) % 4;
               shell.addAll(quad(List.of(base.get(i), base.get(next), top.get(next), top.get(i)), FillMode.SOLID, bias));
            }
            assertTrue(solid.containsAll(shell), "volume discarded its source faces");
            assertTrue(hollow.containsAll(shell), "hollow discarded fixed face or edge samples");
            assertTrue(shell.containsAll(expected), "volume added exterior blocks outside its source faces "
               + difference(expected, shell) + " base=" + base + " extrusion=" + extrusion);
         }
         checked++;
      }
   }

   @Test
   void acuteBoxKeepsThickEdgeSamplesInHollowMode() {
      List<Vec3> base = base(new Vec3(2, -4, -1), new Vec3(0, -6, -7));
      Vec3 extrusion = new Vec3(5, 7, 6);
      for (LineTieBias bias : LineTieBias.values()) {
         Set<BlockPos> outline = box(base, extrusion, FillMode.OUTLINE, bias);
         Set<BlockPos> hollow = box(base, extrusion, FillMode.HOLLOW, bias);
         Set<BlockPos> solid = box(base, extrusion, FillMode.SOLID, bias);
         assertFalse(GenerationFailed.is(solid));
         assertTrue(hollow.containsAll(outline));
         assertTrue(solid.containsAll(hollow));
      }
   }

   @Test
   void fractionalNormalExtrusionUsesTheUnchangedWireframeTranslation() {
      List<Vec3> base = base(new Vec3(8, 2, 4), new Vec3(-2, 8, 0));
      for (Vec3 extrusion : List.of(new Vec3(2.8, -1.2, 7.6), new Vec3(-2.2, 1.8, -7.4), new Vec3(0.2, 0.3, 0.4))) {
         Vec3 snapped = Vec3.atLowerCornerOf(BlockPos.containing(extrusion));
         for (LineTieBias bias : LineTieBias.values()) {
            Set<BlockPos> outline = box(base, extrusion, FillMode.OUTLINE, bias);
            assertEquals(TiltedBoxGenerator.generate(base, extrusion, FillMode.OUTLINE, 100_000,
               BlockGenerationObserver.NONE, bias, FaceRasterizationMode.POINT_SWEEP), outline);
            Set<BlockPos> solid = box(base, extrusion, FillMode.SOLID, bias);
            assertFalse(GenerationFailed.is(solid));
            assertEquals(box(base, snapped, FillMode.SOLID, bias), solid);
            assertTrue(solid.containsAll(outline), "fractional extrusion lost fixed edges");
         }
      }
   }

   @Test
   void constrainedFaceReportsLimitsAndCancellation() {
      List<Vec3> face = base(new Vec3(23, 4, 7), new Vec3(-5, 19, 11));
      Set<BlockPos> exact = quad(face, FillMode.SOLID, LineTieBias.DEFAULT);
      Set<BlockPos> limited = QuadFaceGenerator.generate(face, FillMode.SOLID, exact.size() - 1,
         BlockGenerationObserver.NONE, LineTieBias.DEFAULT, FaceRasterizationMode.DEFAULT);
      assertTrue(GenerationLimitExceeded.is(limited));
      assertThrows(java.util.concurrent.CancellationException.class, () -> QuadFaceGenerator.generate(
         face, FillMode.SOLID, 100_000, new BlockGenerationObserver() {
            @Override public void checkCancelled() { throw new java.util.concurrent.CancellationException(); }
         }, LineTieBias.DEFAULT, FaceRasterizationMode.DEFAULT));
   }

   private static int gcd(int a, int b) {
      a = Math.abs(a);
      b = Math.abs(b);
      while (b != 0) {
         int next = a % b;
         a = b;
         b = next;
      }
      return a;
   }

   private static Set<BlockPos> difference(Set<BlockPos> first, Set<BlockPos> second) {
      Set<BlockPos> result = new HashSet<>(first);
      result.removeAll(second);
      return result;
   }

   private static List<Vec3> base(Vec3 a, Vec3 b) {
      Vec3 origin = new Vec3(0.5, 0.5, 0.5);
      return List.of(origin, origin.add(a), origin.add(a).add(b), origin.add(b));
   }

   private static Set<BlockPos> quad(List<Vec3> face, FillMode mode, LineTieBias bias) {
      return QuadFaceGenerator.generate(face, mode, 100_000, BlockGenerationObserver.NONE, bias, FaceRasterizationMode.DEFAULT);
   }

   private static Set<BlockPos> box(List<Vec3> base, Vec3 extrusion, FillMode mode, LineTieBias bias) {
      return TiltedBoxGenerator.generate(base, extrusion, mode, 100_000,
         BlockGenerationObserver.NONE, bias, FaceRasterizationMode.DEFAULT);
   }
}
