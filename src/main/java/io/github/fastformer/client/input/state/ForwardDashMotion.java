package io.github.fastformer.client.input.state;

import net.minecraft.world.phys.Vec3;

/** Starts immediately and eases out, keeping combined movement below vanilla's speed check. */
public record ForwardDashMotion(Vec3 origin, Vec3 destination) {
   public static final int DURATION_TICKS = 10;
   // Vanilla rejects more than 10 blocks per tick; leave a margin for synchronization.
   private static final double MAX_COMBINED_STEP = 9.5;

   public record Step(double tick, Vec3 movement) { }

   public int durationTicks() {
      return Math.max(DURATION_TICKS, (int)Math.ceil(3.0 * origin.distanceTo(destination) / MAX_COMBINED_STEP));
   }

   public Vec3 position(double tick) {
      double t = Math.clamp(tick / durationTicks(), 0.0, 1.0);
      double remaining = 1.0 - t;
      double eased = 1.0 - remaining * remaining * remaining;
      return origin.lerp(destination, eased);
   }

   /** Relative steps preserve movement supplied by WASD instead of pulling back to an absolute path. */
   public Vec3 step(int tick) {
      return position(tick).subtract(position(tick - 1));
   }

   /** Keeps WASD movement and slows dash progress only when their combined displacement needs it. */
   public Step advance(double tick, Vec3 steering) {
      double nextTick = Math.min(tick + 1.0, durationTicks());
      Vec3 movement = position(nextTick).subtract(position(tick));
      double fraction = speedFraction(steering, movement);
      if (fraction >= 1.0) return new Step(nextTick, movement);
      if (fraction <= 0.0) return new Step(tick, Vec3.ZERO);
      Vec3 limited = movement.scale(fraction);
      double progress = (position(tick).distanceTo(origin) + limited.length()) / origin.distanceTo(destination);
      double limitedTick = durationTicks() * (1.0 - Math.cbrt(Math.max(0.0, 1.0 - progress)));
      return new Step(limitedTick, limited);
   }

   private static double speedFraction(Vec3 steering, Vec3 movement) {
      double limitSquared = MAX_COMBINED_STEP * MAX_COMBINED_STEP;
      if (steering.add(movement).lengthSqr() <= limitSquared) return 1.0;
      double a = movement.lengthSqr();
      double c = steering.lengthSqr() - limitSquared;
      if (a < 1.0E-12 || c >= 0.0) return 0.0;
      double b = steering.dot(movement);
      return Math.clamp((-b + Math.sqrt(b * b - a * c)) / a, 0.0, 1.0);
   }
}
