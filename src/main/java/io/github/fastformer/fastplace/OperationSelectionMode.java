package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

public enum OperationSelectionMode implements TranslatableText {
   CUBOID("fastformer.operation.selection.cuboid"),
   PRISM("fastformer.operation.selection.prism"),
   CONVEX_HULL("fastformer.operation.selection.convex_hull");

   private final String translationKey;

   OperationSelectionMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public int requiredPoints() {
      return this == CUBOID ? 2 : 4;
   }

   public OperationSelectionMode next() {
      return this == CUBOID ? PRISM : CUBOID;
   }

   public OperationSelectionStage stage(int pointCount) {
      if (pointCount >= this.requiredPoints()) {
         return OperationSelectionStage.READY;
      }
      return switch (this) {
         case CUBOID -> OperationSelectionStage.EXTENT;
         case CONVEX_HULL -> OperationSelectionStage.HULL;
         case PRISM -> switch (pointCount) {
            case 0, 1 -> OperationSelectionStage.FIRST_EDGE;
            case 2 -> OperationSelectionStage.SECOND_EDGE;
            default -> OperationSelectionStage.HEIGHT;
         };
      };
   }
}
