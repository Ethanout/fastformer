package io.github.fastformer.common.history;

/** Three chronological tiers. Each tier consumes whole records made by earlier tiers. */
public record HistoryCompressionLimits(int recentAfter, int recentGroup, int olderAfter, int olderGroup,
   int oldestAfter, int oldestGroup) {
   public static final int MAX_AFTER = 65536;
   public static final int MAX_GROUP = 256;
   public static final HistoryCompressionLimits DEFAULTS = new HistoryCompressionLimits(5, 8, 32, 4, 128, 4);

   public HistoryCompressionLimits {
      recentAfter = Math.clamp(recentAfter, 0, MAX_AFTER - 2);
      olderAfter = Math.clamp(olderAfter, recentAfter + 1, MAX_AFTER - 1);
      oldestAfter = Math.clamp(oldestAfter, olderAfter + 1, MAX_AFTER);
      recentGroup = Math.clamp(recentGroup, 1, MAX_GROUP);
      olderGroup = Math.clamp(olderGroup, 1, MAX_GROUP);
      oldestGroup = Math.clamp(oldestGroup, 1, MAX_GROUP);
   }

   public int after(CompressionTier tier) {
      return switch (tier) { case RECENT -> recentAfter; case OLDER -> olderAfter; case OLDEST -> oldestAfter; };
   }

   public int groupSize(CompressionTier tier) {
      return switch (tier) { case RECENT -> recentGroup; case OLDER -> olderGroup; case OLDEST -> oldestGroup; };
   }

   public int minimumSourceStage(CompressionTier tier) {
      int stage = 0;
      for (var previous : CompressionTier.values()) {
         if (previous == tier) break;
         if (groupSize(previous) > 1) stage = previous.stage();
      }
      return stage;
   }

   public boolean enabled() { return recentGroup > 1 || olderGroup > 1 || oldestGroup > 1; }

   public int value(CompressionSetting setting) {
      return switch (setting) {
         case RECENT_AFTER -> recentAfter;
         case RECENT_GROUP -> recentGroup;
         case OLDER_AFTER -> olderAfter;
         case OLDER_GROUP -> olderGroup;
         case OLDEST_AFTER -> oldestAfter;
         case OLDEST_GROUP -> oldestGroup;
      };
   }

   public HistoryCompressionLimits with(CompressionSetting setting, int value) {
      return new HistoryCompressionLimits(
         setting == CompressionSetting.RECENT_AFTER ? Math.clamp(value, 0, olderAfter - 1) : recentAfter,
         setting == CompressionSetting.RECENT_GROUP ? value : recentGroup,
         setting == CompressionSetting.OLDER_AFTER ? Math.clamp(value, recentAfter + 1, oldestAfter - 1) : olderAfter,
         setting == CompressionSetting.OLDER_GROUP ? value : olderGroup,
         setting == CompressionSetting.OLDEST_AFTER ? value : oldestAfter,
         setting == CompressionSetting.OLDEST_GROUP ? value : oldestGroup);
   }

   public static HistoryCompressionLimits uniform(int groupSize) {
      return new HistoryCompressionLimits(0, groupSize, 32, 1, 128, 1);
   }
}
