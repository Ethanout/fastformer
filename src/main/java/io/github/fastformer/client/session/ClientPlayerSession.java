package io.github.fastformer.client.session;

import io.github.fastformer.client.session.empty.EmptySession;
import io.github.fastformer.client.session.quickshape.QuickShapeSession;
import io.github.fastformer.client.session.specialitem.SpecialItemSession;
import io.github.fastformer.client.session.specialshape.SpecialShapeSession;
import io.github.fastformer.client.session.tree.ClientSessionInspection;
import io.github.fastformer.client.session.tree.ClientSessionNode;
import io.github.fastformer.client.session.tree.SessionSignal;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Player-owned client session tree. Its state survives LocalPlayer replacement. */
public final class ClientPlayerSession {
   private final UUID playerId;
   private final ClientSessionNode root = new ClientSessionNode("player");
   private final List<ClientSession> sessionDefinitions = List.of(
      new SpecialItemSession(),
      new SpecialShapeSession(),
      new QuickShapeSession(),
      new EmptySession()
   );
   private ClientSession currentDefinition = sessionDefinitions.getLast();
   private ClientSessionNode currentNode;

   public ClientPlayerSession(UUID playerId) {
      this.playerId = Objects.requireNonNull(playerId, "playerId");
      // Player-owned objects live in the session box. They are deliberately
      // independent from the LocalPlayer instance and from top-level state
      // transitions.
      root.put("operationWorkspace", new ClientOperationWorkspace());
      root.put("selectionSession", new ClientSelectionSession());
      for (ClientSession definition : sessionDefinitions) {
         ClientSessionNode node = root.child(definition.state().name().toLowerCase());
         definition.configureSignals(node);
         node.onEnter(definition::enter).onExit(definition::exit);
         if (definition == currentDefinition) {
            currentNode = node;
         }
      }
      currentNode.enter();
   }

   public UUID playerId() {
      return playerId;
   }

   public ClientSessionState state() {
      return currentDefinition.state();
   }

   public ClientSessionNode root() {
      return root;
   }

   public ClientSessionInspection inspect() {
      return currentNode.inspect();
   }

   public ClientSessionNode currentNode() {
      return currentNode;
   }

   /** Player-owned editable workspace shared by all state cells. */
   public ClientOperationWorkspace operationWorkspace() {
      return root.value("operationWorkspace", ClientOperationWorkspace.class);
   }

   /** Player-owned selection gesture and focus state. */
   public ClientSelectionSession selectionSession() {
      return root.value("selectionSession", ClientSelectionSession.class);
   }

   public ClientPlayerSession put(String key, Object value) {
      currentNode.put(key, value);
      return this;
   }

   public <T> T value(String key, Class<T> type) {
      return currentNode.value(key, type);
   }

   public void refresh(ClientSessionSnapshot snapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
      ClientSession next = sessionDefinitions.stream()
         .filter(session -> session.matches(snapshot))
         .findFirst()
         .orElseThrow(() -> new IllegalStateException("No client session matches the current snapshot"));
      if (next == currentDefinition) {
         return;
      }
      currentNode.exit();
      currentDefinition = next;
      currentNode = root.child(next.state().name().toLowerCase());
      currentNode.enter();
   }

   /** Ends the active workflow without deleting objects stored in the player box. */
   public void clear() {
      refresh(new ClientSessionSnapshot(false, false, false));
   }

   public int signal(SessionSignal signal) {
      return currentNode.emit(signal);
   }
}
