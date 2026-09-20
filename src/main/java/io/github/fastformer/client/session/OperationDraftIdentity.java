package io.github.fastformer.client.session;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Stable server-owned selection identity used to validate a reconnect draft. */
public record OperationDraftIdentity(
   OperationSelectionMode mode,
   List<BlockPos> points,
   int prismBasePointCount,
   BlockPos minOffset,
   BlockPos maxOffset,
   double hullInflation,
   BlockPos selectionMin,
   BlockPos selectionMax
) {
   public OperationDraftIdentity {
      if (mode == null) {
         throw new IllegalArgumentException("A draft identity requires a selection mode");
      }
      points = points == null ? List.of() : points.stream()
         .filter(java.util.Objects::nonNull)
         .map(BlockPos::immutable)
         .toList();
      minOffset = immutable(minOffset);
      maxOffset = immutable(maxOffset);
      selectionMin = immutable(selectionMin);
      selectionMax = immutable(selectionMax);
   }

   public static OperationDraftIdentity from(OperationPreviewPayload payload) {
      if (payload == null || !payload.active()) {
         return null;
      }
      return new OperationDraftIdentity(
         payload.operationSelectionMode(),
         payload.points(),
         payload.operationPrismBasePointCount(),
         payload.operationMinOffset(),
         payload.operationMaxOffset(),
         payload.operationHullInflation(),
         payload.selectionMin(),
         payload.selectionMax()
      );
   }

   private static BlockPos immutable(BlockPos value) {
      return value == null ? null : value.immutable();
   }
}
