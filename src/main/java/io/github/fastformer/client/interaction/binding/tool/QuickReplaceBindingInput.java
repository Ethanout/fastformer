package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.context.ReplacementContext;

/** Adapts replacement events to bindings; the tool owns stroke state and bindings own policy. */
public final class QuickReplaceBindingInput {
   public record Result(boolean owns, boolean consumesVanilla) {}

   private QuickReplaceBindingInput() {}

   public static Result mouse(ReplacementContext context, boolean validPress, boolean useButton,
      int action, int button, long now, ToolBindingCommand dispatcher) {
      boolean active = context.active();
      boolean consumes = false;
      if (active && (action == 0 || validPress)) {
         String name = useButton ? "@use" : "mouse." + button;
         consumes = dispatcher.dispatch(name, action == 0 ? "release" : "press", context.freeze(), now).consumesVanilla();
      }
      return new Result(active, consumes);
   }

   public static Result interaction(ReplacementContext context, boolean useItem, long now, ToolBindingCommand dispatcher) {
      boolean active = context.active();
      boolean consumes = active && dispatcher.dispatch(useItem ? "use" : "attack",
         "interaction", context.freeze(), now).consumesVanilla();
      return new Result(active, consumes);
   }

   public static boolean tick(ReplacementContext context, long now, ToolBindingCommand dispatcher) {
      boolean active = context.active();
      if (active) dispatcher.dispatch("client", "tick", context.freeze(), now);
      return active;
   }

   public static Result scroll(ReplacementContext context, double delta, long now, ToolBindingCommand dispatcher) {
      boolean active = context.active();
      boolean consumes = active && dispatcher.dispatch("scroll", delta > 0 ? "scroll_up" : "scroll_down",
         context.freeze(), now).consumesVanilla();
      return new Result(active, consumes);
   }

}
