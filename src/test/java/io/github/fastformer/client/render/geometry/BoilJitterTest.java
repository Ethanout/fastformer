package io.github.fastformer.client.render.geometry;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoilJitterTest {
   @Test
   void staticCornersAndDisabledJitterRemainStill() {
      assertEquals(Vec3.ZERO, BoilJitter.cornerOffset(new Vec3(2, 3, 4), 0, 0.03, 1.2));
      assertEquals(Vec3.ZERO, BoilJitter.cornerOffset(new Vec3(2, 3, 4), 2, 0.03, 0));
   }

   @Test
   void incidentEdgesShareTheCornerButDifferentSheetsRedrawIt() {
      Vec3 corner = new Vec3(-12.75, 63, 7.125);
      Vec3 first = BoilJitter.cornerOffset(corner, 1, 0.05, 1.2);
      assertEquals(first, BoilJitter.cornerOffset(corner, 1, 0.05, 1.2));
      assertNotEquals(first, BoilJitter.cornerOffset(corner, 2, 0.05, 1.2));
      assertEquals(first.scale(2), BoilJitter.cornerOffset(corner, 1, 0.1, 1.2));
   }

   @Test
   void offsetsStayWithinPixelBudget() {
      for (int i = -100; i <= 100; i++) {
         Vec3 offset = BoilJitter.cornerOffset(new Vec3(i, 12.5, -i), 3, 0.04, 1.2);
         assertTrue(offset.length() <= Math.sqrt(3) * 0.04 * 1.2);
      }
   }

   @Test
   void reversingAnEdgeKeepsItsBow() {
      Vec3 from = new Vec3(-2, 4, 6);
      Vec3 to = new Vec3(8, 1, 0);
      assertEquals(BoilJitter.bow(from, to, 2), BoilJitter.bow(to, from, 2));
      assertEquals(0, BoilJitter.bow(from, to, 0));
   }
}
