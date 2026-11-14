package io.github.fastformer.client.interaction.binding.input;

/** Resource names describe physical buttons, independently of the command they trigger. */
public final class MouseButtons {
   private MouseButtons() {}
   public static String name(int button) {
      return switch (button) {
         case 0 -> "left";
         case 1 -> "right";
         case 2 -> "middle";
         default -> "mouse." + button;
      };
   }
}
