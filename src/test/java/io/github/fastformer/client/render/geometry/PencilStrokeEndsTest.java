package io.github.fastformer.client.render.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PencilStrokeEndsTest {
   @Test
   void eachEdgeHasDistinctLongAndShortEndsWithinRequestedRanges() {
      for (int x = -20; x <= 20; x++) {
         Vec3 from = new Vec3(x, 7, -13);
         for (Vec3 delta : new Vec3[] {new Vec3(3, 0, 0), new Vec3(0, 3, 0), new Vec3(0, 0, 3), new Vec3(-3, 2, 1)}) {
            PencilStrokeEnds ends = ends(from, from.add(delta));
            double longer = Math.max(ends.start(), ends.end());
            double shorter = Math.min(ends.start(), ends.end());
            assertTrue(longer >= 0.15 && longer <= 0.35);
            assertTrue(shorter >= 0.02 && shorter <= 0.15);
            assertTrue(longer - shorter >= 0.129);
         }
      }
   }

   @Test
   void reversingTheEdgePreservesTheLengthAtEachPhysicalEndpoint() {
      Vec3 from = new Vec3(-17.25, 62.125, 34.5);
      Vec3 to = new Vec3(6.5, 68.375, -4.25);
      PencilStrokeEnds forward = ends(from, to);
      PencilStrokeEnds reverse = ends(to, from);
      assertEquals(forward.start(), reverse.end());
      assertEquals(forward.end(), reverse.start());
      assertEquals(forward, ends(from, to));
   }

   @Test
   void shortControlPointEdgesKeepProportionateOvershoots() {
      PencilStrokeEnds ends = ends(Vec3.ZERO, new Vec3(0.1, 0, 0));
      assertTrue(Math.max(ends.start(), ends.end()) <= 0.0351);
      assertTrue(Math.min(ends.start(), ends.end()) <= 0.0151);
   }

   @Test
   void adjacentAxesDoNotAllPreferTheSameEndpoint() {
      PencilStrokeEnds x = ends(Vec3.ZERO, new Vec3(1, 0, 0));
      PencilStrokeEnds y = ends(Vec3.ZERO, new Vec3(0, 1, 0));
      assertTrue(x.start() > x.end());
      assertTrue(y.start() < y.end());
   }

   private static PencilStrokeEnds ends(Vec3 from, Vec3 to) {
      return PencilStrokeEnds.forLine(from, to, 0.15, 0.35, 0.02, 0.15);
   }

   @Test
   void sheetsVaryLengthsWithoutSwappingEndsOrChangingStaticOutput() {
      Vec3 from = new Vec3(-7, 63, 4);
      Vec3 to = new Vec3(2, 63, 4);
      PencilStrokeEnds base = ends(from, to);
      assertEquals(base, PencilStrokeEnds.forLine(from, to, 0.15, 0.35, 0.02, 0.15, 0, 0.3));
      PencilStrokeEnds previous = base;
      for (int sheet = 1; sheet <= 3; sheet++) {
         PencilStrokeEnds value = PencilStrokeEnds.forLine(from, to, 0.15, 0.35, 0.02, 0.15, sheet, 0.3);
         assertEquals(value, PencilStrokeEnds.forLine(from, to, 0.15, 0.35, 0.02, 0.15, sheet, 0.3));
         org.junit.jupiter.api.Assertions.assertNotEquals(previous, value);
         assertTrue(value.start() >= base.start() * 0.7 && value.start() <= base.start() * 1.3);
         assertTrue(value.end() >= base.end() * 0.7 && value.end() <= base.end() * 1.3);
         assertEquals(base.start() > base.end(), value.start() > value.end());
         PencilStrokeEnds reverse = PencilStrokeEnds.forLine(to, from, 0.15, 0.35, 0.02, 0.15, sheet, 0.3);
         assertEquals(value.start(), reverse.end());
         assertEquals(value.end(), reverse.start());
         previous = value;
      }
   }
}
