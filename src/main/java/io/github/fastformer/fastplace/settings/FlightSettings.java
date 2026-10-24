package io.github.fastformer.fastplace.settings;

/** Local flight preferences; dash requests are validated again by the server. */
public record FlightSettings(int dashDistance, boolean accelerationEnabled) {
   public static final int MAX_DASH_DISTANCE = 200;
   public static final FlightSettings DEFAULTS = new FlightSettings(15, true);

   public FlightSettings {
      if (dashDistance < 0 || dashDistance > MAX_DASH_DISTANCE) {
         throw new IllegalArgumentException("Dash distance must be between 0 and " + MAX_DASH_DISTANCE);
      }
   }
}
