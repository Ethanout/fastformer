package io.github.fastformer.client.operation.preview;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Lifecycle-safe set of world source positions whose normal presentation is replaced by a ghost. */
public final class SourceBlockRenderMask {
   private final LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();

   public void replace(Collection<BlockPos> values) {
      this.positions.clear();
      if (values != null) {
         values.forEach(pos -> this.positions.add(pos.immutable()));
      }
   }

   public boolean contains(BlockPos pos) {
      return this.positions.contains(pos);
   }

   public Set<BlockPos> positions() {
      return Set.copyOf(this.positions);
   }

   public void clear() {
      this.positions.clear();
   }
}
