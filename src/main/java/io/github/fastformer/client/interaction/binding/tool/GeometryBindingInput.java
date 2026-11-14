package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.context.GeometryBindingContext;

/** An owned geometry subtree is terminal, including disabled rules and explicit vanilla actions. */
public final class GeometryBindingInput {
   public record Result(boolean owns, boolean consumesVanilla) {}
   private GeometryBindingInput() {}

   public static Result mouse(boolean owns, GeometryBindingContext context, int action, int button, long time,
      ToolBindingCommand commands) {
      if (!owns) return new Result(false, false);
      if (action != 0 && action != 1) return new Result(true, false);
      String name = switch (button) { case 0 -> "left"; case 1 -> "right"; case 2 -> "middle"; default -> "mouse." + button; };
      return new Result(true, commands.dispatch(name, action == 0 ? "release" : "press", context.freeze(), time).consumesVanilla());
   }
}
