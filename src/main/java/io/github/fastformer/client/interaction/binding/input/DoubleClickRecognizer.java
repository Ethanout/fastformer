package io.github.fastformer.client.interaction.binding.input;

import io.github.fastformer.common.interaction.binding.DoubleClickPolicy;
import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Pairs distinct physical presses using the first press's clock, scope, and frozen shape. */
public final class DoubleClickRecognizer {
   public record Sample(Object scope, Object item, InteractionShapeSnapshot shape, Vec3 eye, Vec3 view) {
      public Sample {
         Objects.requireNonNull(scope);
         Objects.requireNonNull(eye);
         Objects.requireNonNull(view);
      }
   }

   private record Key(int button, ResourceLocation rule) {}
   private record First(long occurredAt, Sample sample, DoubleClickPolicy.Captured tolerance) {}
   private final Map<Key, First> firstPresses = new HashMap<>();
   private final PhysicalButtons down = new PhysicalButtons();

   public boolean press(int button) { return down.press(button); }
   public void release(int button) { down.release(button); }

   public boolean matches(int button, ResourceLocation rule, long now, Sample sample, int windowMillis) {
      First first = firstPresses.get(new Key(button, rule));
      if (first == null || now < first.occurredAt) return false;
      long window = first.tolerance == null ? windowMillis * 1_000_000L : first.tolerance.windowNanos();
      if (now - first.occurredAt > window) return false;
      if (first.sample == null || sample == null) return first.sample == sample;
      if (first.sample.scope != sample.scope || !Objects.equals(first.sample.item, sample.item)) return false;
      if (first.sample.shape == null || sample.shape == null) return first.sample.shape == sample.shape;
      return first.tolerance.match(first.sample.shape, first.sample.eye, first.sample.view, sample.eye, sample.view) != null;
   }

   public void remember(int button, ResourceLocation rule, long now, Sample sample, DoubleClickPolicy policy) {
      DoubleClickPolicy.Captured tolerance = sample == null || sample.shape == null ? null
         : policy.capture(sample.shape, sample.eye, sample.view);
      firstPresses.put(new Key(button, rule), new First(now, sample, tolerance));
   }

   public void consume(int button) { firstPresses.keySet().removeIf(key -> key.button == button); }
   public void reset() { firstPresses.clear(); down.clear(); }
}
