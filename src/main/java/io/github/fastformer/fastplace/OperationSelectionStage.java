package io.github.fastformer.fastplace;

public enum OperationSelectionStage implements TranslatableText {
   EXTENT("fastformer.operation.selection_stage.extent"),
   FIRST_EDGE("fastformer.operation.selection_stage.first_edge"),
   SECOND_EDGE("fastformer.operation.selection_stage.second_edge"),
   FACE("fastformer.operation.selection_stage.face"),
   HEIGHT("fastformer.operation.selection_stage.height"),
   HULL("fastformer.operation.selection_stage.hull"),
   READY("fastformer.operation.selection_stage.ready");

   private final String translationKey;

   OperationSelectionStage(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
