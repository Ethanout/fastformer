package io.github.fastformer.client.session.specialshape;

import io.github.fastformer.client.session.ClientSession;
import io.github.fastformer.client.session.ClientSessionSnapshot;
import io.github.fastformer.client.session.ClientSessionState;

/** Session for geometry and operation-based special shapes. */
public final class SpecialShapeSession implements ClientSession {
   @Override
   public ClientSessionState state() {
      return ClientSessionState.SPECIAL_SHAPE;
   }

   @Override
   public boolean matches(ClientSessionSnapshot snapshot) {
      return snapshot.specialShapeActive() && !snapshot.specialItemActive();
   }
}
