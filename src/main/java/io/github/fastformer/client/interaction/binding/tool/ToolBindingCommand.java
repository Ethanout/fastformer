package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.context.BindingContext;

/** Explicit input-owner port; tool rules never look up sessions or invoke world operations. */
@FunctionalInterface
public interface ToolBindingCommand {
   BindingAction.Result dispatch(String name, String gesture, BindingContext context, long occurredAtNanos);
}
