package io.github.fastformer.common.history;

/** Chronological compression tiers, ordered from recent to oldest. */
public enum CompressionTier {
   RECENT(CompressionSetting.RECENT_AFTER, CompressionSetting.RECENT_GROUP),
   OLDER(CompressionSetting.OLDER_AFTER, CompressionSetting.OLDER_GROUP),
   OLDEST(CompressionSetting.OLDEST_AFTER, CompressionSetting.OLDEST_GROUP);

   private final CompressionSetting after;
   private final CompressionSetting group;

   CompressionTier(CompressionSetting after, CompressionSetting group) {
      this.after = after;
      this.group = group;
   }

   public int stage() { return ordinal() + 1; }
   public String translationKey() { return "fastformer.settings.compression." + name().toLowerCase(java.util.Locale.ROOT); }
   public CompressionSetting afterSetting() { return after; }
   public CompressionSetting groupSetting() { return group; }
}
