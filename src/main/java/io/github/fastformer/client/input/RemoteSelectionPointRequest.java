package io.github.fastformer.client.input;

import io.github.fastformer.client.session.OperationDraftIdentity;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import java.util.Objects;
import java.util.UUID;

/** Retains the selection addressed by a role-only request until the client sends it. */
public record RemoteSelectionPointRequest(
   UUID owner, OperationSelectionMode mode, OperationDraftIdentity selection, OperationPointPayload.Role role
) {
   public RemoteSelectionPointRequest {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(role, "role");
   }

   public boolean matches(UUID currentOwner, OperationSelectionMode currentMode, OperationDraftIdentity currentSelection) {
      return owner.equals(currentOwner) && mode == currentMode && Objects.equals(selection, currentSelection);
   }

}
