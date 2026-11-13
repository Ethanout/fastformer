package io.github.fastformer.core.command;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.core.gate.Gate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntentBusTest {
   private record Change(int amount) implements Intent {}
   private record Unknown() implements Intent {}

   @Test
   void dispatchPreservesActorSourceAndRequestIdentity() {
      var actor = new AtomicInteger();
      var bus = IntentBus.<AtomicInteger>create(registry -> registry.register(Change.class,
         Gate.require("positive", context -> context.requestId() > 0), (context, intent) -> {
            assertSame(actor, context.actor());
            assertEquals(IntentSource.COMMAND, context.source());
            assertEquals(19, context.requestId());
            context.actor().addAndGet(intent.amount());
            return IntentResult.success();
         }));
      assertTrue(bus.dispatch(new IntentContext<>(actor, IntentSource.COMMAND, 19), new Change(3)).handled());
      assertEquals(3, actor.get());
   }

   @Test
   void deniedActionDoesNotExecuteAndReportsTheFirstGateFailure() {
      var bus = IntentBus.<String>create(registry -> registry.register(Change.class,
         Gate.<IntentContext<String>>require("first", context -> false)
            .and(Gate.require("second", context -> { throw new AssertionError(); })),
         (context, intent) -> { throw new AssertionError(); }));
      var result = bus.dispatch(new IntentContext<>("actor", IntentSource.NETWORK, 0), new Change(1));
      assertEquals(IntentResult.Status.DENIED, result.status());
      assertEquals("first", result.reason());
      assertFalse(result.handled());
   }

   @Test
   void anUnregisteredIntentPassesWithoutCallingAnotherHandler() {
      var bus = IntentBus.<String>create(registry -> registry.register(Change.class,
         Gate.require("allowed", context -> true), (context, intent) -> { throw new AssertionError(); }));
      assertEquals(IntentResult.pass(), bus.dispatch(new IntentContext<>("actor", IntentSource.INTERNAL, 0), new Unknown()));
   }

   @Test
   void duplicateAndLateRegistrationsAreRejected() {
      Gate<IntentContext<String>> gate = Gate.require("allowed", context -> true);
      assertThrows(IllegalArgumentException.class, () -> IntentBus.<String>create(registry -> {
         registry.register(Change.class, gate, (context, intent) -> IntentResult.success());
         registry.register(Change.class, gate, (context, intent) -> IntentResult.pass());
      }));
      var bus = IntentBus.<String>create(registry -> {});
      assertThrows(IllegalStateException.class, () -> bus.register(Change.class, gate, (context, intent) -> IntentResult.success()));
   }

   @Test
   void handlerFailuresRemainVisibleToTheOwningRuntime() {
      var context = new IntentContext<>("actor", IntentSource.INTERNAL, 0);
      var bus = IntentBus.<String>create(registry -> registry.register(Change.class,
         Gate.require("allowed", ignored -> true), (ignored, intent) -> { throw new IllegalStateException("Write failed"); }));
      assertEquals("Write failed", assertThrows(IllegalStateException.class,
         () -> bus.dispatch(context, new Change(1))).getMessage());
   }
}
