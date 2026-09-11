package io.github.fastformer.fastplace.history;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world server settings for completed operation history. */
public final class HistoryStorageConfig {
   public static final ModConfigSpec SPEC;
   private static final ModConfigSpec.IntValue OWNER_DISK_MIB;
   private static final ModConfigSpec.IntValue TOTAL_DISK_MIB;

   static {
      ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
      builder.push("history");
      OWNER_DISK_MIB = builder.comment("Disk limit per player, including batch files and the index, in MiB. Restart the server after changes.")
         .worldRestart().defineInRange("playerDiskMiB", 1024, 1, 1048576);
      TOTAL_DISK_MIB = builder.comment("Disk limit for all player histories in this world, in MiB. Restart the server after changes.")
         .worldRestart().defineInRange("totalDiskMiB", 8192, 1, 1048576);
      builder.pop();
      SPEC = builder.build();
   }

   private HistoryStorageConfig() {
   }

   public static long ownerDiskBytes() {
      return OWNER_DISK_MIB.get().longValue() * 1024L * 1024L;
   }

   public static long totalDiskBytes() {
      return TOTAL_DISK_MIB.get().longValue() * 1024L * 1024L;
   }
}
