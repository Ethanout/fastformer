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
      assertTrue(Double.isFinite(rectangle.left()));
      assertTrue(Double.isFinite(rectangle.right()));
      assertTrue(Double.isFinite(rectangle.top()));
      assertTrue(Double.isFinite(rectangle.bottom()));
      assertTrue(rectangle.left() < 0 || rectangle.right() > 800
         || rectangle.top() < 0 || rectangle.bottom() > 500);
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

   @Test void offscreenPieceStillExtendsTheGroupFrame() {
      var visible = projection.bounds(new AABB(-1, -1, -10, 1, 1, -9), 800, 500);
      var distant = projection.bounds(new AABB(100, -1, -10, 101, 1, -9), 800, 500);
      assertNotNull(distant);
      assertFalse(distant.intersects(800, 500));
      var group = visible.union(distant);
      assertEquals(visible.left(), group.left());
      assertEquals(distant.right(), group.right());
      assertTrue(group.right() > 800);
      assertTrue(group.intersects(800, 500));
   }

   @Test void worldAnchorsDoNotBecomeEdgeLocators() {
      assertNull(projection.project(new Vec3(0, 0, 10), 800, 500));
      assertNull(projection.project(new Vec3(0, 0, -0.01), 800, 500));
      var outside = projection.project(new Vec3(100, 0, -10), 800, 500);
      assertNotNull(outside);
      assertFalse(outside.onScreen());
      assertTrue(outside.x() > 800);
   }

   @Test void cameraTranslationImmediatelyChangesAWorldAnchorProjection() {
      var anchor = new Vec3(0, 0, -10);
      var first = projection.project(anchor, 800, 500);
      var moved = new SelectionScreenProjection(new Matrix4f(projection.matrix()), new Vec3(1, 0, 0));
      var next = moved.project(anchor, 800, 500);
      assertEquals(400, first.x(), 0.001);
      assertTrue(next.x() < first.x());
      assertEquals(first.y(), next.y());
   }
}
