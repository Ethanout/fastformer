package io.github.fastformer.core.registry;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Insertion-ordered registration, owned and frozen by the system that assembles it. */
public final class Registry<K, V> {
   private final String name;
   private final Map<K, V> entries = new LinkedHashMap<>();
   private boolean frozen;

   private Registry(String name) { this.name = Objects.requireNonNull(name, "name"); }

   public static <K, V> Registry<K, V> create(String name) { return new Registry<>(name); }

   /** Rejects duplicates without changing the existing registration. */
   public V register(K key, V value) {
      if (frozen) throw new IllegalStateException(name + " registry is frozen");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      if (entries.putIfAbsent(key, value) != null) throw new IllegalArgumentException(name + " duplicate key: " + key);
      return value;
   }

   public Optional<V> get(K key) { return Optional.ofNullable(entries.get(Objects.requireNonNull(key, "key"))); }

   /** Stable sorting retains registration order for equal priorities. */
   public Stream<V> ordered(Comparator<? super V> order) { return entries.values().stream().sorted(order); }

   public Registry<K, V> freeze() { frozen = true; return this; }
}
