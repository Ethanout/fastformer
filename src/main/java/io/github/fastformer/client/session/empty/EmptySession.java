package io.github.fastformer.client.session.empty;

import io.github.fastformer.client.session.ClientSession;
import io.github.fastformer.client.session.ClientSessionSnapshot;
import io.github.fastformer.client.session.ClientSessionState;

/** Session used when no client workflow owns the input. */
public final class EmptySession implements ClientSession {
   @Override
   public ClientSessionState state() {
      return ClientSessionState.EMPTY;
   }

   @Override
   public boolean matches(ClientSessionSnapshot snapshot) {
      return !snapshot.anyActive();
   }
}
