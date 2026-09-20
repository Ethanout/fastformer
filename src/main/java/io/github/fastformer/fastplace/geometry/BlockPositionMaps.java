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
      if (source.isEmpty()) {
         return Map.of();
      }
      Map<BlockPos, T> copy = new LinkedHashMap<>();
      source.forEach((position, value) -> copy.put(position.immutable(), Objects.requireNonNull(value, "value")));
      return Collections.unmodifiableMap(copy);
   }
}
