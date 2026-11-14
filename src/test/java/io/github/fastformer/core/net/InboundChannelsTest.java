package io.github.fastformer.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InboundChannelsTest {
   private final Object connection = new Object();

   @Test void acceptanceRunsAtDeliveryAndKeepsTheTypedPayload() {
      var channels = channels();
      var accepted = new AtomicBoolean();
      List<Integer> delivered = new ArrayList<>();
      channels.receive("value", Value.class).accept((payload, source) -> accepted.get())
         .handle(payload -> delivered.add(payload.value()));
      channels.freeze();
      channels.post(new Value(1), connection);
      assertEquals(1, channels.pendingCount());
      assertTrue(delivered.isEmpty());
      accepted.set(true);
      channels.drain();
      assertEquals(List.of(1), delivered);
   }

   @Test void oldConnectionIsRejectedAfterAnEnvironmentChange() {
      var channels = channels();
      var current = new AtomicReference<>(connection);
      List<Integer> delivered = new ArrayList<>();
      channels.receive("value", Value.class).accept((payload, source) -> source == current.get())
         .handle(payload -> delivered.add(payload.value()));
      channels.post(new Value(1), connection);
      Object replacement = new Object();
      current.set(replacement);
      channels.post(new Value(2), replacement);
      channels.drain();
      assertEquals(List.of(2), delivered);
   }

   @Test void invalidationDropsQueuedAndRemainingBatchEvents() {
      var channels = channels();
      List<Integer> delivered = new ArrayList<>();
      channels.receive("value", Value.class).accept((payload, source) -> true).handle(payload -> {
         delivered.add(payload.value());
         channels.invalidate();
         channels.post(new Value(3), connection);
      });
      channels.post(new Value(0), connection);
      channels.invalidate();
      assertEquals(0, channels.pendingCount());
      channels.post(new Value(1), connection);
      channels.post(new Value(2), connection);
      channels.drain();
      assertEquals(List.of(1), delivered);
      assertEquals(1, channels.pendingCount());
   }

   @Test void eventsPostedByHandlersWaitUntilTheNextDrain() {
      var channels = channels();
      List<Integer> delivered = new ArrayList<>();
      channels.receive("value", Value.class).accept((payload, source) -> true).handle(payload -> {
         delivered.add(payload.value());
         if (payload.value() == 1) channels.post(new Value(2), connection);
      });
      channels.post(new Value(1), connection);
      channels.drain();
      assertEquals(List.of(1), delivered);
      channels.drain();
      assertEquals(List.of(1, 2), delivered);
   }

   @Test void handlerFailureDropsOnlyTheCurrentBatch() {
      var channels = channels();
      List<Integer> delivered = new ArrayList<>();
      channels.receive("value", Value.class).accept((payload, source) -> true).handle(payload -> {
         if (payload.value() == 1) throw new IllegalStateException("handler failed");
         delivered.add(payload.value());
      });
      channels.post(new Value(1), connection);
      channels.post(new Value(2), connection);
      assertThrows(IllegalStateException.class, channels::drain);
      assertEquals(0, channels.pendingCount());
      channels.post(new Value(3), connection);
      channels.drain();
      assertEquals(List.of(3), delivered);
   }

   @Test void duplicateAndLateDeclarationsCannotReplaceTheRoute() {
      var channels = channels();
      List<Integer> delivered = new ArrayList<>();
      var declaration = channels.receive("value", Value.class).accept((payload, source) -> true);
      declaration.handle(payload -> delivered.add(payload.value()));
      assertThrows(IllegalArgumentException.class, () -> declaration.handle(payload -> fail()));
      var late = channels.receive("later", Value.class).accept((payload, source) -> true);
      channels.freeze();
      assertThrows(IllegalStateException.class, () -> late.handle(payload -> fail()));
      channels.post(new Value(1), connection);
      channels.drain();
      assertEquals(List.of(1), delivered);
   }

   @Test void unknownKeysAndMismatchedPayloadTypesFailExplicitly() {
      var channels = channels();
      channels.post(new Value(1), connection);
      assertThrows(IllegalArgumentException.class, channels::drain);
      channels.receive("value", Value.class).accept((payload, source) -> true).handle(payload -> fail());
      channels.post(new Other(), connection);
      assertThrows(ClassCastException.class, channels::drain);
      assertEquals(0, channels.pendingCount());
      assertThrows(NullPointerException.class, () -> channels.post(null, connection));
      assertThrows(NullPointerException.class, () -> channels.post(new Value(1), null));
   }

   @Test void isolatedChannelSetsNeverShareHandlersOrPendingEvents() {
      var first = channels();
      var second = channels();
      List<Integer> delivered = new ArrayList<>();
      first.receive("value", Value.class).accept((payload, source) -> true)
         .handle(payload -> delivered.add(payload.value()));
      second.receive("value", Value.class).accept((payload, source) -> true)
         .handle(payload -> delivered.add(-payload.value()));
      first.post(new Value(1), connection);
      second.post(new Value(2), connection);
      first.invalidate();
      first.drain();
      second.drain();
      assertEquals(List.of(-2), delivered);
   }

   private InboundChannels<String, Event, Object> channels() {
      return new InboundChannels<>("test inbound", Event::key);
   }
   private interface Event { String key(); }
   private record Value(int value) implements Event { public String key() { return "value"; } }
   private record Other() implements Event { public String key() { return "value"; } }
}
