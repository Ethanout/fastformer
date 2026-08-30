package io.github.fastformer.client.session;

/** A single top-level client workflow. */
public interface ClientSession {
   ClientSessionState state();

   boolean matches(ClientSessionSnapshot snapshot);

   default void enter() {
   }

   default void exit() {
   }
}
