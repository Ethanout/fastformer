package io.github.fastformer.common.replace;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Samples camera motion against visible surfaces, without bridging world-space hit points. */
public final class QuickReplaceSweep {
   private static final int MAX_SAMPLES = 256;
   private static final double SAMPLE_SPACING = 0.5;
   public record Ray(Vec3 eye, Vec3 view) {
      Ray interpolate(Ray other, double fraction) {
         return new Ray(eye.lerp(other.eye, fraction), view.lerp(other.view, fraction).normalize());
      }
   }
   public record Sample(Ray ray, BlockHitResult hit) {
      double depth() { return ray.eye.distanceTo(hit.getLocation()); }
   }

   /** Current has already been traced; the cached previous sample costs no additional ray. */
   public static List<Sample> trace(Sample previous, Sample current, Function<Ray, BlockHitResult> raycast) {
      if (!canConnect(previous, current)) return List.of(current);
      int count = sampleCount(previous, current);
      var coarse = new ArrayList<Sample>(count);
      for (int index = 1; index < count; index++) {
         Ray ray = previous.ray.interpolate(current.ray, (double)index / count);
         coarse.add(new Sample(ray, raycast.apply(ray)));
      }
      coarse.add(current);
      return refineTransitions(previous, coarse, raycast);
   }

   private static boolean canConnect(Sample previous, Sample current) {
      return previous != null && previous.hit != null && current.hit != null
         && previous.ray.eye.distanceToSqr(current.ray.eye) <= 64
         && previous.ray.view.dot(current.ray.view) > 0;
   }

   private static int sampleCount(Sample previous, Sample current) {
      double dot = net.minecraft.util.Mth.clamp(previous.ray.view.dot(current.ray.view), 0, 1);
      // nlerp is fastest at the midpoint: 2*tan(angle/2) bounds its angular speed.
      double angularTravel = 2 * Math.sqrt((1 - dot) / (1 + dot)) * Math.max(previous.depth(), current.depth());
      double travel = Math.max(previous.ray.eye.distanceTo(current.ray.eye) + angularTravel,
         previous.hit.getLocation().distanceTo(current.hit.getLocation()));
      return (int)Math.clamp(Math.ceil(travel / SAMPLE_SPACING), 1, MAX_SAMPLES);
   }

   private static List<Sample> refineTransitions(Sample previous, List<Sample> coarse,
      Function<Ray, BlockHitResult> raycast) {
      var result = new ArrayList<Sample>(coarse.size());
      int remaining = MAX_SAMPLES - coarse.size();
      for (Sample sample : coarse) {
         if (remaining > 0 && surfaceChanges(previous, sample)) {
            Ray middle = previous.ray.interpolate(sample.ray, 0.5);
            result.add(new Sample(middle, raycast.apply(middle)));
            remaining--;
         }
         result.add(sample);
         previous = sample;
      }
      return result;
   }

   private static boolean surfaceChanges(Sample previous, Sample current) {
      if (previous.hit == null || current.hit == null) return previous.hit != current.hit;
      return previous.hit.getDirection() != current.hit.getDirection()
         || Math.abs(previous.depth() - current.depth()) > SAMPLE_SPACING
         || previous.hit.getLocation().distanceToSqr(current.hit.getLocation()) > SAMPLE_SPACING * SAMPLE_SPACING;
   }
}
