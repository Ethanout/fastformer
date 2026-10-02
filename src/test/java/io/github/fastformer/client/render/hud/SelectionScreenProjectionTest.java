package io.github.fastformer.client.render.hud;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class SelectionScreenProjectionTest {
   private final SelectionScreenProjection projection = new SelectionScreenProjection(
      new Matrix4f().perspective((float)Math.toRadians(70), 1.6F, 0.05F, 1000), Vec3.ZERO);

   @Test void behindCameraBoxesNeverProduceAVisibleFrame() {
      assertNull(projection.bounds(new AABB(-1, -1, 2, 1, 1, 3), 800, 500));
      assertNotNull(projection.bounds(new AABB(-1, -1, -3, 1, 1, -2), 800, 500));
   }
   @Test void nearPlaneCrossingsClipToFiniteScreenCoordinates() {
      var rectangle = projection.bounds(new AABB(-1, -1, -1, 1, 1, 1), 800, 500);
      assertNotNull(rectangle);
      assertEquals(6, rectangle.left());
      assertEquals(794, rectangle.right());
      assertEquals(6, rectangle.top());
      assertEquals(494, rectangle.bottom());
   }
   @Test void locatorAlwaysStaysOnScreenEvenBehindTheCamera() {
      for (Vec3 position : new Vec3[] {new Vec3(100, 0, -1), new Vec3(0, 0, 10), new Vec3(-100, 50, 5)}) {
         var point = projection.point(position, 800, 500);
         assertFalse(point.onScreen());
         assertTrue(point.x() > 0 && point.x() < 800);
         assertTrue(point.y() > 0 && point.y() < 500);
      }
      assertTrue(projection.point(new Vec3(0, 0, -10), 800, 500).onScreen());
   }
}
