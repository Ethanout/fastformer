package io.github.fastformer.client.session.scope;

import io.github.fastformer.client.input.ClientInputSession;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.clipboard.OperationClipboardRepository;
import io.github.fastformer.client.operation.controller.OperationContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owns world scope identity, activation boundaries, fallback gestures, and safe eviction. */
public final class ClientScopeRepository {
   private final Map<ClientScopeId, ClientScope> scopes = new HashMap<>();
   private final OperationClipboardRepository clipboard = new OperationClipboardRepository();
   private final ClientSelectionSession unboundSelection = new ClientSelectionSession();
   private final ClientInputSession unboundInput = new ClientInputSession(unboundSelection.gestures());
   private final OperationContext unboundOperations = new OperationContext(unboundSelection, clipboard);
   private ClientScope current;

   public ClientScope current() {
      return current;
   }

   public ClientScope resolve(ClientScopeId id) {
      return scopes.computeIfAbsent(Objects.requireNonNull(id, "id"), key -> new ClientScope(key, clipboard));
   }

   public OperationContext operations() {
      return current == null ? unboundOperations : current.operations();
   }

   public ClientInputSession inputSession() {
      return current == null ? unboundInput : current.inputSession();
   }

   public ClientSelectionSession selectionSession() {
      return current == null ? unboundSelection : current.selectionSession();
   }

   public void clearUnboundInteraction() {
      unboundOperations.leaveEnvironment();
      unboundInput.reset();
      unboundSelection.clearLiveInteraction();
   }

   public ClientScope activate(ClientScopeId id) {
      Objects.requireNonNull(id, "id");
      ClientScope previous = current;
      boolean first = previous == null;
      boolean changed = !first && !previous.id().equals(id);
      if (changed) detach(previous, id);
      ClientScope next = resolve(id);
      if (changed) {
         next.operations().leaveEnvironment();
         next.drafts().retryTransientFailure();
         if (next.receipts().retryTransientFailure()) next.drafts().rearmAfterReceipt();
      }
      if (changed || first) {
         next.armCleanupReplay();
         next.resetInput();
      }
      current = next;
      return next;
   }

   private static void detach(ClientScope previous, ClientScopeId next) {
      if (previous.id().connection().equals(next.connection())
         && previous.id().playerId().equals(next.playerId())) {
         // A dimension change clears live state; the staged draft remains owned by its old world.
         previous.detachEnvironment();
      } else {
         previous.operations().leaveEnvironment();
         previous.resetInput();
      }
   }

   /** The caller must wait for shared disk work and deferred saves before invoking eviction. */
   public void reclaimInactive() {
      scopes.values().removeIf(scope -> scope != current && scope.canEvict());
   }
}
