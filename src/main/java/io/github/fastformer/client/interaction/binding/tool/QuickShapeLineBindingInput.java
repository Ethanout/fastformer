package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.context.QuickShapeLineContext;

/** One owned line subtree is terminal even when its rules preserve the vanilla event. */
public final class QuickShapeLineBindingInput {
   public record Result(boolean owns, boolean consumesVanilla) { }
   private QuickShapeLineBindingInput() { }

   public static Result mouse(boolean owns, QuickShapeLineContext context, int action, int button, long time, ToolBindingCommand commands) {
      if (!owns) return new Result(false, false);
      if (action != 0 && action != 1) return new Result(true, false);
      String name = switch (button) { case 0 -> "left"; case 1 -> "right"; case 2 -> "middle"; default -> "mouse." + button; };
      return new Result(true, commands.dispatch(name, action == 0 ? "release" : "press", context.freeze(), time).consumesVanilla());
   }

   public static Result scroll(boolean owns, QuickShapeLineContext context, double delta, long time, ToolBindingCommand commands) {
      if (!owns) return new Result(false, false);
      if (delta == 0) return new Result(true, false);
      return new Result(true, commands.dispatch("scroll", delta > 0 ? "scroll_up" : "scroll_down", context.freeze(), time).consumesVanilla());
   }
}
