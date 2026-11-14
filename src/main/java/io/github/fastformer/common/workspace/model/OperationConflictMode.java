package io.github.fastformer.common.workspace.model;

import io.github.fastformer.common.text.TranslatableText;


public enum OperationConflictMode implements TranslatableText {
   REPLACE("fastformer.operation.conflict.replace"),
   KEEP_EXISTING("fastformer.operation.conflict.keep_existing");

   private final String translationKey;

   OperationConflictMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
