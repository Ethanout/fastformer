package io.github.fastformer.client.quickshape;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.fastplace.quickshape.QuickShapeSubmissionPoints;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Complete server data and its identity captured before a submission leaves the input queue. */
public record QuickShapeSubmissionSnapshot(
   long revision, OperationCallbackScope scope, BuildingPreviewPayload data
) {
   public QuickShapeSubmissionSnapshot {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(data, "data");
      if (revision <= 0 || scope.equals(OperationCallbackScope.unscoped()) || !data.active()) {
         throw new IllegalArgumentException("Submission snapshot requires an active authoritative draft");
      }
   }

   public List<BlockPos> points() {
      return QuickShapeSubmissionPoints.capture(
         data.points(), data.lineMode(), data.freeScrollOffset()
      );
   }
}
