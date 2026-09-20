package io.github.fastformer.fastplace.selection;

import io.github.fastformer.fastplace.TranslatableText;

public enum OperationStageMode implements TranslatableText {
   TRANSFORM("fastformer.operation.stage.transform", true),
   SWEEP("fastformer.operation.stage.sweep", false),
   LOFT("fastformer.operation.stage.loft", false);

   private final String translationKey;
   private final boolean executable;

   OperationStageMode(String translationKey, boolean executable) {
      this.translationKey = translationKey;
      this.executable = executable;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public boolean executable() {
      return this.executable;
   }

   public OperationStageMode next() {
      OperationStageMode[] values = values();
      return values[(this.ordinal() + 1) % values.length];
   }
}
