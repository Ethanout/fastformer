package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ClientInputSessionQueueStateTest {
   @Test
   void queuedPhysicalEventsStayVisibleUntilTickDrain() {
      var session = new ClientInputSession();
      session.postScroll(new ScrollInputSnapshot(1));
      assertTrue(session.hasQueuedPhysicalEvents());
      session.drainPhysicalEvents(() -> true, event -> fail(), event -> { },
         event -> fail(), event -> fail(), event -> fail());
      assertFalse(session.hasQueuedPhysicalEvents());
   }

   @Test
   void resetDropsQueuedPhysicalEvents() {
      var session = new ClientInputSession();
      session.postScroll(new ScrollInputSnapshot(1));
      session.reset();
      assertFalse(session.hasQueuedPhysicalEvents());
   }
}
