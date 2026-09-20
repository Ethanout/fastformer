package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** A point command targets one selection instance, even when dispatch is delayed. */
public record SelectionPointPress(
   UUID owner, int partId, long instanceId, int button, boolean control, BlockPos point, long occurredAtNanos
) {
   public SelectionPointPress {
      Objects.requireNonNull(owner, "owner");
      if (button < 0 || button > 2) throw new IllegalArgumentException("Point press requires a supported mouse button");
      if (point != null) point = point.immutable();
   }

   public boolean matches(UUID currentOwner, ClientOperationWorkspace workspace) {
      return owner.equals(currentOwner) && !workspace.locked()
         && partId > 0 && workspace.activeId() == partId
         && workspace.part(partId).isPresent() && workspace.interactionId(partId) == instanceId;
   }
}
