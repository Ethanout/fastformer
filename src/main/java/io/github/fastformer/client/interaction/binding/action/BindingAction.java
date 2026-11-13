package io.github.fastformer.client.interaction.binding.action;

import io.github.fastformer.client.interaction.binding.context.BindingContext;

/** One atomic operation, already decoded and validated when bindings are loaded. */
@FunctionalInterface
public interface BindingAction {
   Result execute(BindingContext context, BindingCommands commands);
   enum Result { PASS, HANDLED }
}
