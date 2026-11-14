package io.github.fastformer.client.interaction.binding.tool;

/** Keeps interaction callbacks bound to the physical press that selected the line policy. */
public final class QuickShapeLineInputState {
   private final QuickShapeLineBindingInput.Result[] presses = new QuickShapeLineBindingInput.Result[3];
   private long generation;

   public void pressed(int button, QuickShapeLineBindingInput.Result result) {
      if (button >= 0 && button < presses.length) presses[button] = result;
   }

   public QuickShapeLineBindingInput.Result interaction(int button) {
      return button >= 0 && button < presses.length ? presses[button] : null;
   }

   public long generation() { return generation; }
   public boolean accepts(long capturedGeneration) { return capturedGeneration == generation; }

   public void clear() {
      java.util.Arrays.fill(presses, null);
      generation++;
   }
}
