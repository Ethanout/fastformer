package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.session.empty.EmptySession;
import io.github.fastformer.client.session.quickshape.QuickShapeSession;
import io.github.fastformer.client.session.specialitem.SpecialItemSession;
import io.github.fastformer.client.session.specialshape.SpecialShapeSession;
import org.junit.jupiter.api.Test;

class ClientSessionTest {
   @Test
   void sessionPredicatesAreMutuallyExclusiveForNormalSnapshots() {
      ClientSessionSnapshot empty = new ClientSessionSnapshot(false, false, false);
      ClientSessionSnapshot quickShape = new ClientSessionSnapshot(true, false, false);
      ClientSessionSnapshot specialShape = new ClientSessionSnapshot(false, true, false);
      ClientSessionSnapshot specialItem = new ClientSessionSnapshot(false, false, true);

      assertTrue(new EmptySession().matches(empty));
      assertTrue(new QuickShapeSession().matches(quickShape));
      assertTrue(new SpecialShapeSession().matches(specialShape));
      assertTrue(new SpecialItemSession().matches(specialItem));
   }

   @Test
   void specialItemTakesPrecedenceWhenFeaturesOverlap() {
      ClientSessionSnapshot overlapping = new ClientSessionSnapshot(true, true, true);

      assertTrue(new SpecialItemSession().matches(overlapping));
      assertTrue(!new QuickShapeSession().matches(overlapping));
      assertTrue(!new SpecialShapeSession().matches(overlapping));
      assertTrue(!new EmptySession().matches(overlapping));
   }
}
