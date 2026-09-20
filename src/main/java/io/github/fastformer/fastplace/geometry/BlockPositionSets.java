package io.github.fastformer.fastplace.geometry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Copies immutable coordinates without clustered immutable-set probing. */
public final class BlockPositionSets {
   private BlockPositionSets() {
   }

   public static Set<BlockPos> copyOf(Collection<BlockPos> source) {
      Objects.requireNonNull(source, "source");
      if (source.isEmpty()) {
         return Set.of();
      }
      Set<BlockPos> copy = new LinkedHashSet<>();
      for (BlockPos position : source) {
         copy.add(position.immutable());
      }
      return Collections.unmodifiableSet(copy);
   }
}
