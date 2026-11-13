package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntryDoubleClickToleranceTest {
   private static final Vec3 VIEW = new Vec3(0, 0, 1);
   private static final InteractionShapeSnapshot FULL = new InteractionShapeSnapshot(
      new BlockPos(-10, 4, 20), List.of(new AABB(0, 0, 0, 1, 1, 1)));

   @Test void exactHitLocationAndFaceAlwaysWinOverPaddedGeometry() {
      var eye = new Vec3(-9.01, 4.75, 0);
      var exact = FULL.clip(eye, VIEW);
      var hit = EntryDoubleClickTolerance.capture(FULL, eye, VIEW).clip(FULL, eye, VIEW);
      assertEquals(exact.getLocation(), hit.getLocation());
      assertEquals(exact.getDirection(), hit.getDirection());
      assertEquals(exact.isInside(), hit.isInside());
   }

   @Test void paddingIsDistanceBasedButNeverExceedsHalfABlock() {
      var near = EntryDoubleClickTolerance.capture(FULL, new Vec3(-9.5, 4.5, 6), VIEW);
      var far = EntryDoubleClickTolerance.capture(FULL, new Vec3(-9.5, 4.5, 0), VIEW);
      var remote = EntryDoubleClickTolerance.capture(FULL, new Vec3(-9.5, 4.5, -180), VIEW);
      assertEquals(0, near.shapePadding());
      assertTrue(far.shapePadding() > 0.13 && far.shapePadding() < 0.2);
      assertEquals(0.5, remote.shapePadding());
      assertNull(remote.clip(FULL, new Vec3(-8.49, 4.5, -180), VIEW));
   }

   @Test void paddingPreservesDisconnectedGeometryAndEmptyShapes() {
      var split = new InteractionShapeSnapshot(FULL.position(), List.of(
         new AABB(0, 0, 0, 0.125, 1, 1), new AABB(0.875, 0, 0, 1, 1, 1)));
      var eye = new Vec3(-9.95, 4.5, 0);
      var tolerance = EntryDoubleClickTolerance.capture(split, eye, VIEW);
      assertNull(tolerance.clip(split, new Vec3(-9.5, 4.5, 0), VIEW));
      assertNull(tolerance.clip(new InteractionShapeSnapshot(FULL.position(), List.of()), eye, VIEW));
   }
}
