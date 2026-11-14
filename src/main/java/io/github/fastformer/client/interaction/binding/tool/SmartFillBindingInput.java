package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.context.BindingContext;

/** Owns fill pointer dispatch only while the mode delegates that input kind to binding data. */
public final class SmartFillBindingInput {
   public record Result(boolean owns, boolean consumesVanilla) {}

   private SmartFillBindingInput() {}

   public static Result mouse(boolean owns, BindingContext context, int action, int button, long now, ToolBindingCommand dispatcher) {
      return dispatch(owns, context, switch (button) {
         case 0 -> "left";
         case 1 -> "right";
         case 2 -> "middle";
         default -> "mouse." + button;
      }, action == 0 ? "release" : "press", now, dispatcher);
   }

   public static Result interaction(boolean owns, BindingContext context, boolean useItem, long now, ToolBindingCommand dispatcher) {
      return dispatch(owns, context, useItem ? "use" : "attack", "interaction", now, dispatcher);
   }

   public static Result scroll(boolean owns, BindingContext context, double delta, long now, ToolBindingCommand dispatcher) {
      return dispatch(owns, context, "scroll", delta > 0 ? "scroll_up" : "scroll_down", now, dispatcher);
   }

   private static Result dispatch(boolean owns, BindingContext context, String name, String gesture, long now, ToolBindingCommand dispatcher) {
      if (!owns) return new Result(false, false);
      boolean consumes = dispatcher.dispatch(name, gesture, context, now).consumesVanilla();
      return new Result(true, consumes);
   }
}
