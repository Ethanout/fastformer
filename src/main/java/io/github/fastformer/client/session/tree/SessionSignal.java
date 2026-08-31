package io.github.fastformer.client.session.tree;

import java.util.Objects;

/** A semantic event delivered to the active session node. */
public record SessionSignal(String name, Object payload) {
   public SessionSignal {
      if (name == null || name.isBlank()) {
         throw new IllegalArgumentException("Signal name must not be blank");
      }
   }

   public static SessionSignal tick() {
      return new SessionSignal("tick", null);
   }

   public static SessionSignal named(String name) {
      return new SessionSignal(name, null);
   }

   public <T> T payload(Class<T> type) {
      Objects.requireNonNull(type, "type");
      return type.cast(payload);
   }
}
