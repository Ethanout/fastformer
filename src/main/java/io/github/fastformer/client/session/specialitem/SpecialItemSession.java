package io.github.fastformer.client.session.specialitem;

import io.github.fastformer.client.session.ClientSession;
import io.github.fastformer.client.session.ClientSessionSnapshot;
import io.github.fastformer.client.session.ClientSessionState;

/** Session for special-item workflows such as quick replace. */
public final class SpecialItemSession implements ClientSession {
   @Override
   public ClientSessionState state() {
      return ClientSessionState.SPECIAL_ITEM;
   }

   @Override
   public boolean matches(ClientSessionSnapshot snapshot) {
      return snapshot.specialItemActive();
   }
}
