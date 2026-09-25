package io.github.fastformer.fastplace.geometry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Freezes coordinate maps without the clustered probing of JDK immutable maps. */
public final class BlockPositionMaps {
   private BlockPositionMaps() {
   }

   public static <T> Map<BlockPos, T> copyOf(Map<BlockPos, T> source) {
      Objects.requireNonNull(source, "source");
      if (source instanceof FrozenPositionMap<?>) return source;
      if (source.isEmpty()) {
         return Map.of();
      }
      Map<BlockPos, T> copy = new LinkedHashMap<>();
      source.forEach((position, value) -> copy.put(position.immutable(), Objects.requireNonNull(value, "value")));
      return new FrozenPositionMap<>(copy);
   }

   /** Builds on the owning thread, then transfers an immutable map without a full copy. */
   public static final class Builder<T> {
      private Map<BlockPos, T> values = new LinkedHashMap<>();
      public void put(BlockPos position, T value) {
         if (values == null) throw new IllegalStateException("Coordinate map is already frozen");
         values.put(position.immutable(), Objects.requireNonNull(value));
      }
      public int size() { return values == null ? 0 : values.size(); }
      public Map<BlockPos, T> build() {
         if (values == null) throw new IllegalStateException("Coordinate map is already frozen");
         Map<BlockPos, T> result = new FrozenPositionMap<>(values);
         values = null;
         return result;
      }
   }

   private static final class FrozenPositionMap<T> extends java.util.AbstractMap<BlockPos, T> {
      private final Map<BlockPos, T> values;
      FrozenPositionMap(Map<BlockPos, T> values) { this.values = Collections.unmodifiableMap(values); }
      @Override public java.util.Set<Entry<BlockPos, T>> entrySet() { return values.entrySet(); }
      @Override public T get(Object key) { return values.get(key); }
      @Override public boolean containsKey(Object key) { return values.containsKey(key); }
      @Override public int size() { return values.size(); }
   }
}
