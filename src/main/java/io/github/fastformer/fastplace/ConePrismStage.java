package io.github.fastformer.fastplace;

public enum ConePrismStage implements TranslatableText {
   FACE("fastformer.geometry.cone_stage.face"),
   BODY("fastformer.geometry.cone_stage.body"),
   ADJUST("fastformer.geometry.cone_stage.adjust");

   private final String translationKey;

   ConePrismStage(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
