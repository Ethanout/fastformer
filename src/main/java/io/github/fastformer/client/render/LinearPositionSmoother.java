package io.github.fastformer.client.render;

import java.util.ArrayDeque;
import net.minecraft.world.phys.Vec3;

/** Each position change completes a linear transition within one fixed time window. */
public final class LinearPositionSmoother {
   private record Sample(long time, Vec3 point) { }
   private final long durationNanos;
   private final ArrayDeque<Sample> samples = new ArrayDeque<>();
   private long lastUpdate;

   public LinearPositionSmoother(long durationNanos) {
      this.durationNanos = Math.max(1L, durationNanos);
   }

   public Vec3 update(Vec3 target, long now) {
      if (samples.isEmpty() || now < lastUpdate || now - lastUpdate > durationNanos) {
         samples.clear();
         samples.add(new Sample(now - durationNanos, target));
      }
      lastUpdate = now;
      if (!samples.getLast().point().equals(target)) {
         if (samples.getLast().time() == now) samples.removeLast();
         samples.add(new Sample(now, target));
      }
      long start = now - durationNanos;
      while (samples.size() > 1) {
         var iterator = samples.iterator();
         iterator.next();
         if (iterator.next().time() > start) break;
         samples.removeFirst();
      }
      // A step becomes a finite linear ramp. Consecutive steps blend without
      // restarting the transition each frame.
      Vec3 sum = Vec3.ZERO;
      Vec3 point = samples.getFirst().point();
      long time = start;
      for (Sample sample : samples) {
         if (sample.time() <= start) continue;
         sum = sum.add(point.scale((double)(sample.time() - time) / durationNanos));
         point = sample.point();
         time = sample.time();
      }
      return sum.add(point.scale((double)(now - time) / durationNanos));
   }

   public void reset() { samples.clear(); }
}
