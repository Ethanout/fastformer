package io.github.fastformer.client;

import java.util.Optional;

/** A component contributes an intent when it owns the current pointer target. */
@FunctionalInterface
public interface InteractionIntentProvider {
   Optional<OperationInteractionIntent> resolve(InteractionContext context);
}
