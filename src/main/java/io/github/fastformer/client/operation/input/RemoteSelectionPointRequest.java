package io.github.fastformer.client.operation.input;

import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import java.util.Objects;
import java.util.UUID;

/** Retains the selection addressed by a role-only request until the client sends it. */
public record RemoteSelectionPointRequest(
   UUID owner, OperationSelectionMode mode, OperationDraftIdentity selection, OperationPointPayload.Role role,
   long revision, OperationCallbackScope scope
) {
   public RemoteSelectionPointRequest {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(scope, "scope");
   }

   public boolean matches(UUID currentOwner, OperationSelectionMode currentMode, OperationDraftIdentity currentSelection,
      long currentRevision, OperationCallbackScope currentScope) {
      return owner.equals(currentOwner) && mode == currentMode && Objects.equals(selection, currentSelection)
         && revision == currentRevision && scope.equals(currentScope);
   }

}
