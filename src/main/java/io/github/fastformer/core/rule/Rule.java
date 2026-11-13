package io.github.fastformer.core.rule;

import java.util.Optional;

/** An empty result defers to the next rule; any present result terminates evaluation. */
@FunctionalInterface
public interface Rule<C, R> {
   Optional<R> apply(C context);
}
