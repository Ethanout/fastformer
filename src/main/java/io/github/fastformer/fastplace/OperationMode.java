package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

public enum OperationMode implements TranslatableText {
   MOVE("fastformer.operation.mode.move"),
   STACK("fastformer.operation.mode.stack");

   private final String translationKey;

   OperationMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public OperationMode next() {
      OperationMode[] values = values();
      return values[(this.ordinal() + 1) % values.length];
   }
}
