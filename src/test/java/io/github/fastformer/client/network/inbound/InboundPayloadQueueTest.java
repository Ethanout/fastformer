package io.github.fastformer.client.network.inbound;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.network.transport.ClientboundTransport.Delivery;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class InboundPayloadQueueTest {
   private final Connection connection = new Connection(PacketFlow.CLIENTBOUND);

   @Test
   void guardRunsAtTickDeliveryRatherThanArrival() {
      var queue = new InboundPayloadQueue();
      var accepted = new AtomicBoolean(false);
      List<Integer> delivered = new ArrayList<>();
      queue.register(TestPayload.class, TestPayload.TYPE, (payload, source) -> accepted.get(),
         payload -> delivered.add(payload.value()));
      queue.freeze();
      queue.post(event(1));
      assertEquals(1, queue.pendingCount());
      assertTrue(delivered.isEmpty());
      accepted.set(true);
      queue.drain();
      assertEquals(List.of(1), delivered);
   }

   @Test
   void connectionChangedAfterArrivalRejectsTheOldEvent() {
      var queue = new InboundPayloadQueue();
      var current = new AtomicReference<>(connection);
      List<Integer> delivered = new ArrayList<>();
      queue.register(TestPayload.class, TestPayload.TYPE, (payload, source) -> source == current.get(),
         payload -> delivered.add(payload.value()));
      queue.post(event(1));
      Connection replacement = new Connection(PacketFlow.CLIENTBOUND);
      current.set(replacement);
      queue.post(new Delivery(new TestPayload(2), replacement));
      queue.drain();
      assertEquals(List.of(2), delivered);
   }

   @Test
   void invalidationDropsQueuedAndRemainingBatchEvents() {
      var queue = new InboundPayloadQueue();
      List<Integer> delivered = new ArrayList<>();
      queue.register(TestPayload.class, TestPayload.TYPE, (payload, source) -> true, payload -> {
         delivered.add(payload.value());
         queue.invalidate();
         queue.post(event(3));
      });
      queue.post(event(0));
      queue.invalidate();
      assertEquals(0, queue.pendingCount());
      queue.post(event(1));
      queue.post(event(2));
      queue.drain();
      assertEquals(List.of(1), delivered);
      assertEquals(1, queue.pendingCount());
   }

   @Test
   void eventsPostedDuringDeliveryWaitForTheNextTick() {
      var queue = new InboundPayloadQueue();
      List<Integer> delivered = new ArrayList<>();
      queue.register(TestPayload.class, TestPayload.TYPE, (payload, source) -> true, payload -> {
         delivered.add(payload.value());
         if (payload.value() == 1) queue.post(event(2));
      });
      queue.post(event(1));
      queue.drain();
      assertEquals(List.of(1), delivered);
      queue.drain();
      assertEquals(List.of(1, 2), delivered);
   }

   @Test
   void handlerFailureDropsTheRemainingBatchButAllowsLaterTicks() {
      var queue = new InboundPayloadQueue();
      List<Integer> delivered = new ArrayList<>();
      queue.register(TestPayload.class, TestPayload.TYPE, (payload, source) -> true, payload -> {
         if (payload.value() == 1) throw new IllegalStateException("handler failed");
         delivered.add(payload.value());
      });
      queue.post(event(1));
      queue.post(event(2));
      assertThrows(IllegalStateException.class, queue::drain);
      assertEquals(0, queue.pendingCount());
      queue.post(event(3));
      queue.drain();
      assertEquals(List.of(3), delivered);
   }

   @Test
   void duplicateOrLateRegistrationCannotReplaceAHandler() {
      var queue = new InboundPayloadQueue();
      List<Integer> delivered = new ArrayList<>();
      queue.register(TestPayload.class, TestPayload.TYPE, (payload, source) -> true,
         payload -> delivered.add(payload.value()));
      assertThrows(IllegalArgumentException.class, () -> queue.register(TestPayload.class, TestPayload.TYPE,
         (payload, source) -> true, payload -> fail("overwritten")));
      queue.freeze();
      assertThrows(IllegalStateException.class, () -> queue.register(TestPayload.class, TestPayload.TYPE,
         (payload, source) -> true, payload -> fail("registered after freeze")));
      queue.post(event(1));
      queue.drain();
      assertEquals(List.of(1), delivered);
   }

   @Test
   void unknownPayloadAndMissingConnectionFailExplicitly() {
      var queue = new InboundPayloadQueue();
      queue.post(event(1));
      assertThrows(IllegalArgumentException.class, queue::drain);
      assertThrows(NullPointerException.class, () -> new Delivery(new TestPayload(1), null));
      assertThrows(NullPointerException.class, () -> new Delivery(null, connection));
   }

   private Delivery event(int value) { return new Delivery(new TestPayload(value), connection); }

   private record TestPayload(int value) implements CustomPacketPayload {
      static final Type<TestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "inbound_test"));
      @Override public Type<TestPayload> type() { return TYPE; }
   }
}
