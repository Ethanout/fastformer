package io.github.fastformer.core.net;

import io.github.fastformer.core.async.TickMailbox;
import io.github.fastformer.core.registry.Registry;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns typed routes and owner-thread delivery; acceptance is evaluated when a queued event is drained. */
public final class InboundChannels<K, P, C> {
   private final Registry<K, Route<P, C>> routes;
   private final TickMailbox<Delivery<P, C>> events = new TickMailbox<>(event -> { });
   private final Function<? super P, ? extends K> key;

   public InboundChannels(String name, Function<? super P, ? extends K> key) {
      routes = Registry.create(name);
      this.key = Objects.requireNonNull(key, "key");
   }

   public <T extends P> Channel<T> receive(K id, Class<T> payloadClass) {
      return new Channel<>(Objects.requireNonNull(id, "id"),
         Objects.requireNonNull(payloadClass, "payloadClass"));
   }

   public void freeze() { routes.freeze(); }
   public int pendingCount() { return events.pendingCount(); }
   public void invalidate() { events.invalidate(); }

   /** Arrival only queues immutable payload/connection references; it never executes a handler. */
   public void post(P payload, C connection) {
      events.post(new Delivery<>(Objects.requireNonNull(payload, "payload"),
         Objects.requireNonNull(connection, "connection")));
   }

   public void drain() {
      events.drain(delivery -> {
         K id = Objects.requireNonNull(key.apply(delivery.payload()), "payload key");
         routes.get(id).orElseThrow(() -> new IllegalArgumentException("Unsupported inbound event: " + id))
            .deliver(delivery);
      });
   }

   /** A declaration is registered once it has both an acceptance policy and a typed handler. */
   public final class Channel<T extends P> {
      private final K id;
      private final Class<T> payloadClass;

      private Channel(K id, Class<T> payloadClass) {
         this.id = id;
         this.payloadClass = payloadClass;
      }

      public Accepted<T> accept(BiPredicate<? super T, ? super C> policy) {
         return new Accepted<>(this, Objects.requireNonNull(policy, "policy"));
      }
   }

   public final class Accepted<T extends P> {
      private final Channel<T> channel;
      private final BiPredicate<? super T, ? super C> policy;

      private Accepted(Channel<T> channel, BiPredicate<? super T, ? super C> policy) {
         this.channel = channel;
         this.policy = policy;
      }

      public void handle(Consumer<? super T> handler) {
         Objects.requireNonNull(handler, "handler");
         routes.register(channel.id, delivery -> {
            T payload = channel.payloadClass.cast(delivery.payload());
            if (policy.test(payload, delivery.connection())) handler.accept(payload);
         });
      }
   }

   @FunctionalInterface
   private interface Route<P, C> { void deliver(Delivery<P, C> delivery); }
   private record Delivery<P, C>(P payload, C connection) { }
}
