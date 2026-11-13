package io.github.fastformer.core.lifecycle;

import java.util.Objects;
import java.util.function.Supplier;

/** A typed factory and identity; declaring a key does not allocate state or register global data. */
public final class ScopedKey<T extends Scoped> {
   private final String id;
   private final Supplier<T> factory;

   private ScopedKey(String id, Supplier<T> factory) {
      this.id = Objects.requireNonNull(id, "id");
      this.factory = Objects.requireNonNull(factory, "factory");
      if (id.isBlank()) throw new IllegalArgumentException("scope key must not be blank");
   }

   public static <T extends Scoped> ScopedKey<T> create(String id, Supplier<T> factory) { return new ScopedKey<>(id, factory); }
   public String id() { return id; }
   T create() { return Objects.requireNonNull(factory.get(), "state factory result"); }
}
