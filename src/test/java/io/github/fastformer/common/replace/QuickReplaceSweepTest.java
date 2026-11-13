package io.github.fastformer.common.replace;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuickReplaceSweepTest {
   @Test void connectsVisibleWallAtHalfBlockSpacingAndReusesCurrentHit() {
      var first = new QuickReplaceSweep.Ray(Vec3.ZERO, new Vec3(0, 0, 1));
      var last = new QuickReplaceSweep.Ray(new Vec3(1, 0, 0), new Vec3(0.5, 0, 1).normalize());
      var calls = new AtomicInteger();
      var current = sample(last, 5);
      var samples = QuickReplaceSweep.trace(sample(first, 5), current, ray -> {
         calls.incrementAndGet();
         return wall(ray, 5);
      });
      assertSame(current, samples.getLast());
      assertEquals(samples.size() - 1, calls.get());
      assertTrue(samples.size() < 16, "Nearby wall should not use maximum interaction reach");
      var previous = wall(first, 5).getLocation();
      for (var sample : samples) {
         assertEquals(1, sample.ray().view().length(), 1.0E-10);
         assertTrue(previous.distanceTo(sample.hit().getLocation()) <= 0.51);
         previous = sample.hit().getLocation();
      }
   }

   @Test void teleportOppositeViewAndFreshPressNeverBridgeOldSurfaces() {
      var current = new QuickReplaceSweep.Ray(Vec3.ZERO, new Vec3(0, 0, 1));
      for (var previous : new QuickReplaceSweep.Ray[]{null,
         new QuickReplaceSweep.Ray(new Vec3(9, 0, 0), current.view()),
         new QuickReplaceSweep.Ray(Vec3.ZERO, current.view().reverse())}) {
         var end = sample(current, 5);
         assertEquals(java.util.List.of(end), QuickReplaceSweep.trace(previous == null ? null : sample(previous, 5), end,
            ray -> { fail("Discontinuous stroke must not cast intermediate rays"); return null; }));
      }
   }

   @Test void stationaryRayHasOneSampleAndExtremeDistanceIsBounded() {
      var current = new QuickReplaceSweep.Ray(Vec3.ZERO, new Vec3(0, 0, 1));
      assertEquals(1, trace(current, current, 5).size());
      var other = new QuickReplaceSweep.Ray(Vec3.ZERO, new Vec3(0.9, 0, 1).normalize());
      assertEquals(256, trace(current, other, 1_000_000).size());
      assertTrue(trace(current, other, 5).size() < trace(current, other, 50).size());
   }

   @Test void missingEndpointBreaksInterpolation() {
      var ray = new QuickReplaceSweep.Ray(Vec3.ZERO, new Vec3(0, 0, 1));
      var miss = new QuickReplaceSweep.Sample(ray, null);
      assertEquals(java.util.List.of(miss), QuickReplaceSweep.trace(sample(ray, 5), miss,
         unused -> { fail("Miss must break interpolation"); return null; }));
   }

   @Test void foregroundDepthTransitionGetsAnExtraRayWithoutBridgingHits() {
      var first = new QuickReplaceSweep.Ray(Vec3.ZERO, new Vec3(0, 0, 1));
      var last = new QuickReplaceSweep.Ray(new Vec3(0.25, 0, 0), first.view());
      var samples = QuickReplaceSweep.trace(sample(first, 1), sample(last, 5),
         ray -> wall(ray, ray.eye().x < 0.125 ? 1 : 5));
      assertTrue(samples.size() > 9, "Depth transition should add a midpoint to the nine base intervals");
      for (var sample : samples) {
         assertEquals(sample.ray().eye().x < 0.125 ? 1 : 5, sample.hit().getLocation().z);
      }
   }

   private static java.util.List<QuickReplaceSweep.Sample> trace(QuickReplaceSweep.Ray first,
      QuickReplaceSweep.Ray last, double depth) {
      return QuickReplaceSweep.trace(sample(first, depth), sample(last, depth), ray -> wall(ray, depth));
   }

   private static QuickReplaceSweep.Sample sample(QuickReplaceSweep.Ray ray, double depth) {
      return new QuickReplaceSweep.Sample(ray, wall(ray, depth));
   }

   private static BlockHitResult wall(QuickReplaceSweep.Ray ray, double z) {
      Vec3 point = ray.eye().add(ray.view().scale((z - ray.eye().z) / ray.view().z));
      return new BlockHitResult(point, Direction.NORTH, BlockPos.containing(point), false);
   }
}
