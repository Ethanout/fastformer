package io.github.fastformer.network.transport;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Common-side bridge installed by client setup; it never loads physical-client classes. */
public final class ClientboundTransport {
   private static volatile Consumer<Delivery> receiver;

   private ClientboundTransport() { }

   public static synchronized void install(Consumer<Delivery> target) {
      Objects.requireNonNull(target, "target");
      if (receiver != null) throw new IllegalStateException("Clientbound transport already installed");
      receiver = target;
   }

   public static void receive(CustomPacketPayload payload, Connection connection) {
      Delivery delivery = new Delivery(payload, connection);
      Consumer<Delivery> current = receiver;
      if (current == null) throw new IllegalStateException("Clientbound transport has not been installed");
      current.accept(delivery);
   }

   public record Delivery(CustomPacketPayload payload, Connection connection) {
      public Delivery {
         Objects.requireNonNull(payload, "payload");
         Objects.requireNonNull(connection, "connection");
      }
   }
}
