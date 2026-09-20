package io.github.fastformer.client.interaction;

import java.util.Objects;

/** Identity-based keys prevent unrelated components with the same value class from colliding. */
public final class ComponentType<T> {
   private final String name;
   private final Class<T> valueType;

   public ComponentType(String name, Class<T> valueType) {
      this.name = Objects.requireNonNull(name, "name");
      this.valueType = Objects.requireNonNull(valueType, "valueType");
   }

   T cast(Object value) {
      return this.valueType.cast(Objects.requireNonNull(value, this.name));
   }

   @Override
   public String toString() {
      return this.name;
   }
}
