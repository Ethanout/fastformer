package io.github.fastformer.client.session;

import io.github.fastformer.client.session.tree.ClientSessionNode;

/** A single top-level client workflow. */
public interface ClientSession {
   ClientSessionState state();

   boolean matches(ClientSessionSnapshot snapshot);

   /** Registers handlers owned by this state on its persistent tree node. */
   default void configureSignals(ClientSessionNode node) {
   }

   default void enter() {
   }

   default void exit() {
   }
}
