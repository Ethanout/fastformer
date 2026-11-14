package io.github.fastformer.client.mode.input;

/** Explicit context for mode-specific keys before common cancellation and shortcuts run. */
public record KeyView(boolean screenClosed, boolean playerPresent, boolean idleActionsAllowed,
   boolean fillKey, boolean fillReady, boolean canStartFill) {
   public boolean playerReady() { return screenClosed && playerPresent; }
}
