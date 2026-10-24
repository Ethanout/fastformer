package io.github.fastformer.fastplace.history;

/** Three chronological tiers. Each tier consumes whole records made by earlier tiers. */
public record TinkerHistoryMergeLimits(int recentAfter, int recentGroup, int olderAfter, int olderGroup,
   int oldestAfter, int oldestGroup) {
   public static final int MAX_AFTER = 65536;
   public static final TinkerHistoryMergeLimits DEFAULTS = new TinkerHistoryMergeLimits(5, 8, 32, 4, 128, 4);

   public TinkerHistoryMergeLimits {
      recentAfter = Math.clamp(recentAfter, 0, MAX_AFTER - 2);
      olderAfter = Math.clamp(olderAfter, recentAfter + 1, MAX_AFTER - 1);
      oldestAfter = Math.clamp(oldestAfter, olderAfter + 1, MAX_AFTER);
      recentGroup = Math.clamp(recentGroup, 1, BlockEditHistoryMetadata.MAX_LIMIT);
      olderGroup = Math.clamp(olderGroup, 1, BlockEditHistoryMetadata.MAX_LIMIT);
      oldestGroup = Math.clamp(oldestGroup, 1, BlockEditHistoryMetadata.MAX_LIMIT);
   }

   public enum Tier {
      RECENT, OLDER, OLDEST;
      public int stage() { return ordinal() + 1; }
      public String translationKey() { return "fastformer.settings.compression." + name().toLowerCase(java.util.Locale.ROOT); }
      public Setting afterSetting() { return Setting.values()[ordinal() * 2]; }
      public Setting groupSetting() { return Setting.values()[ordinal() * 2 + 1]; }
   }

   public int after(Tier tier) {
      return switch (tier) { case RECENT -> recentAfter; case OLDER -> olderAfter; case OLDEST -> oldestAfter; };
   }

   public int groupSize(Tier tier) {
      return switch (tier) { case RECENT -> recentGroup; case OLDER -> olderGroup; case OLDEST -> oldestGroup; };
   }

   public int minimumSourceStage(Tier tier) {
      int stage = 0;
      for (var previous : Tier.values()) {
         if (previous == tier) break;
         if (groupSize(previous) > 1) stage = previous.stage();
      }
      return stage;
   }

   public boolean enabled() { return recentGroup > 1 || olderGroup > 1 || oldestGroup > 1; }

   public enum Setting {
      RECENT_AFTER, RECENT_GROUP, OLDER_AFTER, OLDER_GROUP, OLDEST_AFTER, OLDEST_GROUP;
   }

   public int value(Setting setting) {
      return switch (setting) {
         case RECENT_AFTER -> recentAfter;
         case RECENT_GROUP -> recentGroup;
         case OLDER_AFTER -> olderAfter;
         case OLDER_GROUP -> olderGroup;
         case OLDEST_AFTER -> oldestAfter;
         case OLDEST_GROUP -> oldestGroup;
      };
   }

   public TinkerHistoryMergeLimits with(Setting setting, int value) {
      return new TinkerHistoryMergeLimits(
         setting == Setting.RECENT_AFTER ? Math.clamp(value, 0, olderAfter - 1) : recentAfter,
         setting == Setting.RECENT_GROUP ? value : recentGroup,
         setting == Setting.OLDER_AFTER ? Math.clamp(value, recentAfter + 1, oldestAfter - 1) : olderAfter,
         setting == Setting.OLDER_GROUP ? value : olderGroup,
         setting == Setting.OLDEST_AFTER ? value : oldestAfter,
         setting == Setting.OLDEST_GROUP ? value : oldestGroup);
   }

   static TinkerHistoryMergeLimits uniform(int groupSize) {
      return new TinkerHistoryMergeLimits(0, groupSize, 32, 1, 128, 1);
   }
}
