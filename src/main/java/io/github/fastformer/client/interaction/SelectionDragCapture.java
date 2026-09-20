package io.github.fastformer.client.interaction;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Frozen targets for a gesture; display numbers alone cannot identify a part instance. */
public record SelectionDragCapture(UUID owner, Map<Integer, Long> targets, int mouseButton, long gestureToken) {
   public SelectionDragCapture {
      Objects.requireNonNull(owner, "owner");
      targets = Map.copyOf(targets);
      if (targets.isEmpty() || mouseButton < 0 || gestureToken <= 0) {
         throw new IllegalArgumentException("Capture requires targets, a button and an active gesture");
      }
   }

   public static SelectionDragCapture create(
      UUID owner, ClientOperationWorkspace workspace, List<ClientSelectionPart> parts, int button, long token
   ) {
      Map<Integer, Long> targets = new LinkedHashMap<>();
      for (ClientSelectionPart part : parts) targets.put(part.id(), workspace.interactionId(part.id()));
      return new SelectionDragCapture(owner, targets, button, token);
   }

   public boolean matches(UUID currentOwner, ClientOperationWorkspace workspace, long currentGesture) {
      if (!this.owner.equals(currentOwner) || this.gestureToken != currentGesture) return false;
      for (var target : this.targets.entrySet()) {
         if (workspace.part(target.getKey()).isEmpty()
            || workspace.interactionId(target.getKey()) != target.getValue()) return false;
      }
      return true;
   }
}
