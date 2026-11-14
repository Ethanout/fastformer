package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext;

import io.github.fastformer.client.interaction.binding.tool.ToolBindingCommand;

/** An owned selection subtree remains terminal when rules are disabled or yield to vanilla. */
public final class SelectionBindingInput {
   public record Result(boolean owns, boolean consumesVanilla) {}
   private SelectionBindingInput() {}
   public static Result mouse(boolean owns, SelectionBindingContext context, int action, int button,
      long time, ToolBindingCommand commands) {
      if (!owns) return new Result(false, false);
      if (action != 0 && action != 1) return new Result(true, false);
      String name = switch (button) { case 0 -> "left"; case 1 -> "right"; case 2 -> "middle"; default -> "mouse." + button; };
      return new Result(true, commands.dispatch(name, action == 0 ? "release" : "press", context.freeze(), time).consumesVanilla());
   }
}
