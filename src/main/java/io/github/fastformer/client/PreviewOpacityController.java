package io.github.fastformer.client;

/** Applies a caller-provided visibility policy without owning gameplay rules. */
public final class PreviewOpacityController {
   private final VisibilityInterpolator visibility;

   public PreviewOpacityController(long transitionNanos, boolean initiallyVisible) {
      this.visibility = new VisibilityInterpolator(transitionNanos, initiallyVisible);
   }

   public float update(Input input, long nowNanos) {
      Input safeInput = input == null ? Input.visible() : input;
      if (safeInput.forceVisible()) {
         this.visibility.reset(true);
         return 1.0F;
      }
      float value = this.visibility.update(!safeInput.fadeRequested(), nowNanos);
      return safeInput.minimumOpacity() + value * (1.0F - safeInput.minimumOpacity());
   }

   public void reset(boolean visible) {
      this.visibility.reset(visible);
   }

   public record Input(boolean fadeRequested, boolean forceVisible, float minimumOpacity) {
      public Input {
         minimumOpacity = Math.clamp(minimumOpacity, 0.0F, 1.0F);
      }

      public static Input visible() {
         return new Input(false, false, 0.0F);
      }
   }
}
