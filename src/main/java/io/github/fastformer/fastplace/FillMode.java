package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

public enum FillMode implements TranslatableText {
   OUTLINE("fastformer.fill.wireframe"),
   HOLLOW("fastformer.fill.hollow"),
   SOLID("fastformer.fill.solid");

   private final String translationKey;

   FillMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public static FillMode parse(String value) {
      String normalized = value.toLowerCase();
      return switch (normalized) {
         case "0", "outline" -> OUTLINE;
         case "1", "hollow" -> HOLLOW;
         case "2", "solid" -> SOLID;
         default -> null;
      };
   }
}
