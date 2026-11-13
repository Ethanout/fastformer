package io.github.fastformer.core.command;

import java.util.Objects;

/** Captures the actor and source once, before admission and action handling. */
public record IntentContext<C>(C actor, IntentSource source, long requestId) {
   public IntentContext {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(source, "source");
   }
}
