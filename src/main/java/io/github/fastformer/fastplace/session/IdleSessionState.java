package io.github.fastformer.fastplace.session;

/** Idle input ownership, independent of block tools and the distance hysteresis. */
public enum IdleSessionState {
   READY,
   MANUAL_VANILLA,
   MANUAL_FF,
   WAITING_FOR_FAR_ENTRY,
   WAITING_FOR_FAR_EXIT;

   public static IdleSessionState suspend(boolean withinFar) {
      return withinFar ? WAITING_FOR_FAR_EXIT : WAITING_FOR_FAR_ENTRY;
   }

   public IdleSessionState observeDistance(boolean withinFar) {
      return switch (this) {
         case READY -> READY;
         case MANUAL_VANILLA, MANUAL_FF -> this;
         case WAITING_FOR_FAR_ENTRY -> withinFar ? WAITING_FOR_FAR_EXIT : this;
         case WAITING_FOR_FAR_EXIT -> withinFar ? this : READY;
      };
   }

   public boolean suspended() { return this != READY && this != MANUAL_FF; }

   public boolean vanillaOwnsInput(boolean distanceDecision) {
      return this == MANUAL_FF ? false : suspended() || distanceDecision;
   }
}
