package io.github.fastformer.client.operation.preview;

import java.util.Map;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
import net.minecraft.core.BlockPos;

/**
 * Result of a bounded composition. An empty {@link Composed} map is a legal downscale.
 * {@link OverBudget} is never an empty map.
 */
public sealed interface Composition<T> {
   enum Limit {
      OUTPUT,
      WORK
   }

   record Composed<T>(
      Map<BlockPos, T> values,
      WorkspacePreviewComposer.GeometryFrame frame
   ) implements Composition<T> {
      public Composed {
         values = values == null ? Map.of() : BlockPositionMaps.copyOf(values);
      }
   }

   record OverBudget<T>(Limit limit, long cap, long reached) implements Composition<T> {
      public OverBudget {
         if (limit == null) {
            throw new IllegalArgumentException("limit kind is required");
         }
      }
   }
}
