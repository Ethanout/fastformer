package io.github.fastformer.client.interaction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** An immutable component snapshot. Components contain data, not business callbacks. */
public record InteractionObject(Id id, Map<ComponentType<?>, Object> components) {
   public InteractionObject {
      Objects.requireNonNull(id, "id");
      components = Map.copyOf(components);
      components.forEach((type, value) -> type.cast(value));
   }

   public <T> Optional<T> component(ComponentType<T> type) {
      Objects.requireNonNull(type, "type");
      Object value = this.components.get(type);
      return value == null ? Optional.empty() : Optional.of(type.cast(value));
   }

   public <T> T require(ComponentType<T> type) {
      return component(type).orElseThrow(() -> new IllegalStateException("Missing interaction component: " + type));
   }

   public static Builder builder(Id id) {
      return new Builder(id);
   }

   public record Id(UUID session, String kind, long localId) {
      public Id {
         Objects.requireNonNull(session, "session");
         Objects.requireNonNull(kind, "kind");
         if (kind.isBlank() || localId <= 0) {
            throw new IllegalArgumentException("Interaction identity requires a kind and a positive local ID");
         }
      }
   }

   public static final class Builder {
      private final Id id;
      private final Map<ComponentType<?>, Object> components = new LinkedHashMap<>();

      private Builder(Id id) {
         this.id = Objects.requireNonNull(id, "id");
      }

      public <T> Builder with(ComponentType<T> type, T value) {
         this.components.put(type, type.cast(value));
         return this;
      }

      public InteractionObject build() {
         return new InteractionObject(this.id, this.components);
      }
   }
}
