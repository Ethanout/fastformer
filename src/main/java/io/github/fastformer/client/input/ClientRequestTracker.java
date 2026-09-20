package io.github.fastformer.client.input;

import java.util.UUID;

/** Owns the one client request token that can block input at a time. */
public final class ClientRequestTracker {
   private ClientSemanticEvent.Submit current;

   public boolean beginPlacement(long requestId) {
      return begin(new ClientSemanticEvent.Submit.Placement(requestId));
   }

   public boolean beginWorkspace(UUID transferId) {
      return transferId != null && begin(new ClientSemanticEvent.Submit.Workspace(transferId));
   }

   public boolean ownsPlacement(long requestId) {
      return requestId > 0 && new ClientSemanticEvent.Submit.Placement(requestId).equals(this.current);
   }

   public boolean ownsWorkspace(UUID transferId) {
      return transferId != null && new ClientSemanticEvent.Submit.Workspace(transferId).equals(this.current);
   }

   public boolean active() {
      return this.current != null;
   }

   public void clear() {
      this.current = null;
   }

   ClientSemanticEvent.Submit current() {
      return this.current;
   }

   static boolean valid(ClientSemanticEvent.Submit request) {
      return request instanceof ClientSemanticEvent.Submit.Workspace
         || request instanceof ClientSemanticEvent.Submit.Placement placement && placement.requestId() > 0;
   }

   boolean begin(ClientSemanticEvent.Submit request) {
      if (active() || !valid(request)) {
         return false;
      }
      this.current = request;
      return true;
   }
}
