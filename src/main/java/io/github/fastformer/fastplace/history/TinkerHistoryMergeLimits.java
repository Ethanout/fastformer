package io.github.fastformer.fastplace.history;

/** Per-action distance bands keep historical groups stable when the player moves. */
public record TinkerHistoryMergeLimits(int near, int middle, int far, int nearDistance, int farDistance) {
   public static final TinkerHistoryMergeLimits DEFAULTS = new TinkerHistoryMergeLimits(8, 16, 32);

   public TinkerHistoryMergeLimits {
      near = Math.clamp(near, 1, TinkerHistoryMetadata.MAX_LIMIT);
      middle = Math.clamp(middle, 1, TinkerHistoryMetadata.MAX_LIMIT);
      far = Math.clamp(far, 1, TinkerHistoryMetadata.MAX_LIMIT);
      nearDistance = Math.clamp(nearDistance, 1, 255);
      farDistance = Math.clamp(farDistance, nearDistance + 1, 256);
   }

   public TinkerHistoryMergeLimits(int near, int middle, int far) { this(near, middle, far, 5, 20); }

   public enum DistanceBand {
      NEAR, MIDDLE, FAR;

      public static DistanceBand at(double distance) {
         return at(distance, DEFAULTS);
      }

      public static DistanceBand at(double distance, TinkerHistoryMergeLimits limits) {
         return distance <= limits.nearDistance ? NEAR : distance <= limits.farDistance ? MIDDLE : FAR;
      }

      public DistanceBand next() { return values()[(ordinal() + 1) % values().length]; }
   }

   public int limit(DistanceBand band) {
      return switch (band) { case NEAR -> near; case MIDDLE -> middle; case FAR -> far; };
   }

   public TinkerHistoryMergeLimits with(DistanceBand band, int value) {
      return new TinkerHistoryMergeLimits(band == DistanceBand.NEAR ? value : near,
         band == DistanceBand.MIDDLE ? value : middle, band == DistanceBand.FAR ? value : far, nearDistance, farDistance);
   }

   public enum Setting {
      NEAR_LIMIT, MIDDLE_LIMIT, FAR_LIMIT, NEAR_DISTANCE, FAR_DISTANCE;

      public Setting next() { return values()[(ordinal() + 1) % values().length]; }
      public String translationKey() { return "fastformer.settings.tinker_history." + name().toLowerCase(java.util.Locale.ROOT); }
   }

   public int value(Setting setting) {
      return switch (setting) {
         case NEAR_LIMIT -> near;
         case MIDDLE_LIMIT -> middle;
         case FAR_LIMIT -> far;
         case NEAR_DISTANCE -> nearDistance;
         case FAR_DISTANCE -> farDistance;
      };
   }

   public TinkerHistoryMergeLimits with(Setting setting, int value) {
      return switch (setting) {
         case NEAR_LIMIT -> with(DistanceBand.NEAR, value);
         case MIDDLE_LIMIT -> with(DistanceBand.MIDDLE, value);
         case FAR_LIMIT -> with(DistanceBand.FAR, value);
         case NEAR_DISTANCE -> new TinkerHistoryMergeLimits(near, middle, far, Math.clamp(value, 1, farDistance - 1), farDistance);
         case FAR_DISTANCE -> new TinkerHistoryMergeLimits(near, middle, far, nearDistance, value);
      };
   }

   static TinkerHistoryMergeLimits uniform(int limit) { return new TinkerHistoryMergeLimits(limit, limit, limit); }
}
