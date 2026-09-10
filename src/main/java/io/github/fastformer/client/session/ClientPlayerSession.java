package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import java.util.Objects;
import java.util.UUID;

/** Player-owned client state that survives LocalPlayer replacement. */
public final class ClientPlayerSession {
   private final UUID playerId;
   private final ClientOperationWorkspace operationWorkspace = new ClientOperationWorkspace();
   private final ClientSelectionSession selectionSession = new ClientSelectionSession();

   public ClientPlayerSession(UUID playerId) {
      this.playerId = Objects.requireNonNull(playerId, "playerId");
   }

   public UUID playerId() {
      return playerId;
   }

   /** Player-owned editable workspace shared by all state cells. */
   public ClientOperationWorkspace operationWorkspace() {
      return operationWorkspace;
   }

   /** Player-owned selection gesture and focus state. */
   public ClientSelectionSession selectionSession() {
      return selectionSession;
   }

}
