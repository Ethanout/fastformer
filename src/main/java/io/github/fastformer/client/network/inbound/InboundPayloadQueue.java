package io.github.fastformer.client.network.inbound;

import io.github.fastformer.core.async.TickMailbox;
import io.github.fastformer.core.registry.Registry;
import io.github.fastformer.network.transport.ClientboundTransport.Delivery;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Owns typed handlers and tick delivery; guards run at delivery time against the current environment. */
final class InboundPayloadQueue {
   private final Registry<ResourceLocation, Route<?>> routes = Registry.create("client inbound");
   private final TickMailbox<Delivery> events = new TickMailbox<>(event -> { });

   <P extends CustomPacketPayload> void register(Class<P> payloadClass, CustomPacketPayload.Type<P> type,
      BiPredicate<P, Connection> accept, Consumer<P> handler) {
      routes.register(type.id(), new Route<>(Objects.requireNonNull(payloadClass, "payloadClass"),
         Objects.requireNonNull(accept, "accept"), Objects.requireNonNull(handler, "handler")));
   }

   void freeze() { routes.freeze(); }
   void post(Delivery delivery) { events.post(delivery); }
   int pendingCount() { return events.pendingCount(); }
   void invalidate() { events.invalidate(); }

   void drain() {
      events.drain(delivery -> routes.get(delivery.payload().type().id())
         .orElseThrow(() -> new IllegalArgumentException("Unsupported client event: " + delivery.payload().type().id()))
         .deliver(delivery));
   }

   private record Route<P extends CustomPacketPayload>(Class<P> payloadClass, BiPredicate<P, Connection> accept, Consumer<P> handler) {
      void deliver(Delivery delivery) {
         P payload = payloadClass.cast(delivery.payload());
         if (accept.test(payload, delivery.connection())) handler.accept(payload);
      }
   }
}
