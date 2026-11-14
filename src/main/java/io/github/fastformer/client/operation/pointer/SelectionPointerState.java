package io.github.fastformer.client.operation.pointer;

import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One selection owner's immutable visual pointer result, shared by HUD and world rendering. */
public record SelectionPointerState(UUID owner, Optional<OperationInteractionIntent> target) {
   public SelectionPointerState {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(target, "target");
   }

   public static SelectionPointerState empty(UUID owner) { return new SelectionPointerState(owner, Optional.empty()); }

   public OperationInteractionIntent.Gizmo gizmo() {
      return target.orElse(null) instanceof OperationInteractionIntent.Gizmo gizmo ? gizmo : null;
   }
}
