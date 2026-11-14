package io.github.fastformer.client.interaction.binding.input;

import io.github.fastformer.client.interaction.binding.action.CancellationCommand;
import java.util.Objects;

/** A queued cancellation carries only the captured command and restore-dismissal scope. */
public record CancellationIntent(CancellationCommand command, boolean dismissOperationRestore, boolean dismissPreviewRestore) {
   public CancellationIntent { Objects.requireNonNull(command, "command"); }
}
