package io.github.fastformer.client.interaction.selectiongesture;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact identity of a borrowed pointer and the frozen targets it owns. */
public record SelectionDragCapture(UUID owner, Map<Integer, Long> targets, int mouseButton, long gestureToken) {
   public SelectionDragCapture {
      Objects.requireNonNull(owner, "owner");
      targets = Map.copyOf(targets);
      if (targets.isEmpty() || mouseButton < 0 || gestureToken <= 0)
         throw new IllegalArgumentException("Capture requires targets, a button and an active gesture");
   }
}
