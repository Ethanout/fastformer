package io.github.fastformer.client.session.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionSignalRegistryTest {
   @Test
   void dispatchesOnlyHandlersRegisteredForTheSignal() {
      SessionSignalRegistry registry = new SessionSignalRegistry();
      AtomicInteger ticks = new AtomicInteger();
      registry.register("tick", ignored -> ticks.incrementAndGet());

      assertEquals(1, registry.emit(SessionSignal.tick()));
      assertEquals(1, ticks.get());
      assertEquals(0, registry.emit(SessionSignal.named("confirm")));
   }

   @Test
   void subscriptionCanBeRemovedWithoutChangingOtherHandlers() {
      SessionSignalRegistry registry = new SessionSignalRegistry();
      AtomicInteger calls = new AtomicInteger();
      SessionSignalRegistry.Subscription first = registry.register("tick", ignored -> calls.incrementAndGet());
      registry.register("tick", ignored -> calls.addAndGet(10));

      first.close();
      assertEquals(1, registry.emit(SessionSignal.tick()));
      assertEquals(10, calls.get());
   }
}
