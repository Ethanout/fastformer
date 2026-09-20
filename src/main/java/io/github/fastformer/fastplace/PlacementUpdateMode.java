package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

public enum PlacementUpdateMode implements TranslatableText {
   NORMAL(UpdateFlag.NEIGHBORS | UpdateFlag.CLIENTS, "fastformer.placement.update.normal"),
   CLIENT_ONLY(UpdateFlag.CLIENTS | UpdateFlag.KNOWN_SHAPE | UpdateFlag.SUPPRESS_DROPS, "fastformer.placement.update.client_only");

   private static final class UpdateFlag {
      private static final int NEIGHBORS = 1;
      private static final int CLIENTS = 2;
      private static final int KNOWN_SHAPE = 16;
      private static final int SUPPRESS_DROPS = 32;

      private UpdateFlag() {
      }
   }

   private final int flags;
   private final String translationKey;

   PlacementUpdateMode(int flags, String translationKey) {
      this.flags = flags;
      this.translationKey = translationKey;
   }

   public int flags() {
      return this.flags;
   }

   /** True when this mode does not propagate writes to neighbouring blocks. */
   public boolean suppressesNeighborUpdates() {
      return (this.flags & UpdateFlag.NEIGHBORS) == 0 && (this.flags & UpdateFlag.KNOWN_SHAPE) != 0;
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
