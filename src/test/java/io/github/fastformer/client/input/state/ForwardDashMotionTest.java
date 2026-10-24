package io.github.fastformer.client.input.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ForwardDashMotionTest {
   @Test
   void longDistancesRespectVanillaSpeedWithoutChangingShortDashTiming() {
      assertEquals(10, new ForwardDashMotion(Vec3.ZERO, new Vec3(15, 0, 0)).durationTicks());
      for (double distance : new double[]{1, 15, 50, 200}) {
         Vec3 destination = new Vec3(1, 1, 1).normalize().scale(distance);
         var motion = new ForwardDashMotion(Vec3.ZERO, destination);
         Vec3 position = Vec3.ZERO;
         for (int tick = 1; tick <= motion.durationTicks(); tick++) {
            Vec3 step = motion.step(tick);
            assertTrue(step.length() < 9.5);
            position = position.add(step);
         }
         assertTrue(position.distanceTo(destination) < 1.0E-8);
         assertEquals(Vec3.ZERO, motion.step(motion.durationTicks() + 1));
      }
   }

   @Test
   void steeringUsesTheSameSpeedBudgetWithoutLosingDashDistance() {
      var motion = new ForwardDashMotion(Vec3.ZERO, new Vec3(200, 0, 0));
      for (Vec3 steering : new Vec3[]{new Vec3(3, 0, 0), new Vec3(0, 0, 3), new Vec3(-3, 0, 0)}) {
         Vec3 total = Vec3.ZERO;
         double tick = 0;
         int iterations = 0;
         while (tick < motion.durationTicks() && iterations++ < 200) {
            var step = motion.advance(tick, steering);
            assertTrue(steering.add(step.movement()).length() <= 9.500001);
            assertTrue(step.tick() > tick);
            tick = step.tick();
            total = total.add(step.movement());
         }
         assertEquals(motion.durationTicks(), tick);
         assertTrue(total.distanceTo(new Vec3(200, 0, 0)) < 1.0E-8);
      }
   }

   @Test
   void steeringAtTheLimitPausesProgressWithoutAddingMoreSpeed() {
      var motion = new ForwardDashMotion(Vec3.ZERO, new Vec3(200, 0, 0));
      var step = motion.advance(0, new Vec3(10, 0, 0));
      assertEquals(0, step.tick());
      assertEquals(Vec3.ZERO, step.movement());
      assertTrue(motion.advance(step.tick(), Vec3.ZERO).movement().x > 0);
   }

   @Test
   void steeringOffsetsAccumulateAndRemainAfterTheDashEnds() {
      Vec3 start = new Vec3(8, 70, -9);
      Vec3 end = start.add(15, 0, 0);
      var motion = new ForwardDashMotion(start, end);
      Vec3 position = start;
      Vec3 steering = new Vec3(0.1, 0, 0.2);
      for (int tick = 1; tick <= ForwardDashMotion.DURATION_TICKS; tick++) {
         position = position.add(steering).add(motion.step(tick));
      }
      assertTrue(position.distanceTo(end.add(steering.scale(ForwardDashMotion.DURATION_TICKS))) < 1.0E-8);
   }

   @Test
   void retriggeringStartsANewForwardStepAtTheCurrentPosition() {
      var first = new ForwardDashMotion(Vec3.ZERO, new Vec3(15, 0, 0));
      Vec3 position = first.position(4).add(0, 0, 1);
      var next = new ForwardDashMotion(position, position.add(15, 0, 0));
      assertTrue(next.step(1).x >= first.step(4).x);
      assertEquals(0, next.step(1).z);
      assertEquals(1, position.add(next.step(1)).z);
   }

   @Test
   void travelStartsImmediatelyAndDeceleratesWithoutOvershootingOrDriftingSideways() {
      Vec3 start = new Vec3(8, 70, -9);
      Vec3 direction = new Vec3(1, 1, -1).normalize();
      Vec3 end = start.add(direction.scale(15));
      var motion = new ForwardDashMotion(start, end);
      Vec3 previous = start;
      double previousDistance = 0;
      double previousStep = Double.POSITIVE_INFINITY;
      for (int tick = 1; tick <= ForwardDashMotion.DURATION_TICKS; tick++) {
         Vec3 position = motion.position(tick);
         double distance = position.distanceTo(start);
         assertTrue(distance >= previousDistance && distance <= 15.000001);
         assertTrue(position.subtract(start).cross(direction).length() < 1.0E-8);
         double step = position.distanceTo(previous);
         assertTrue(step <= previousStep + 1.0E-8);
         previousStep = step;
         previousDistance = distance;
         previous = position;
      }
      double firstStep = motion.position(1).distanceTo(start);
      assertTrue(firstStep > 15.0 / ForwardDashMotion.DURATION_TICKS);
      assertTrue(motion.position(ForwardDashMotion.DURATION_TICKS - 1).distanceTo(end) < firstStep * 0.1);
      assertEquals(start, motion.position(-1));
      assertTrue(motion.position(ForwardDashMotion.DURATION_TICKS + 1).distanceTo(end) < 1.0E-8);
   }
}
