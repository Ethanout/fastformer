package io.github.fastformer.fastplace;

public enum PlacementUpdateMode implements TranslatableText {
   NORMAL(3, "fastformer.placement.update.normal"),
   CLIENT_ONLY(2 | 16 | 32, "fastformer.placement.update.client_only");

   private final int flags;
   private final String translationKey;

   PlacementUpdateMode(int flags, String translationKey) {
      this.flags = flags;
      this.translationKey = translationKey;
   }

   public int flags() {
      return this.flags;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public static PlacementUpdateMode parse(String value) {
      return switch (value) {
         case "normal" -> NORMAL;
         case "client_only" -> CLIENT_ONLY;
         default -> null;
      };
   }
}
