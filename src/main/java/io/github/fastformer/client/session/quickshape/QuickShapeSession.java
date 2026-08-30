package io.github.fastformer.client.session.quickshape;

import io.github.fastformer.client.session.ClientSession;
import io.github.fastformer.client.session.ClientSessionSnapshot;
import io.github.fastformer.client.session.ClientSessionState;

/** Session for the normal block-in-hand quick-shape workflow. */
public final class QuickShapeSession implements ClientSession {
   @Override
   public ClientSessionState state() {
      return ClientSessionState.QUICK_SHAPE;
   }

   @Override
   public boolean matches(ClientSessionSnapshot snapshot) {
      return snapshot.quickShapeActive()
         && !snapshot.specialShapeActive()
         && !snapshot.specialItemActive();
   }
}
