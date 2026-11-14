package io.github.fastformer.client.interaction.selectiongesture;

/** Readiness or result of an adjustment using the selection's captured source. */
public enum AabbAdjustDecision {
   CAPTURE_PENDING, READY, DRAG_STARTED, ADJUSTED, UNCHANGED, SUBMISSION_PENDING,
   SOURCE_CHANGED, ENVIRONMENT_UNAVAILABLE, NO_TARGET, EDIT_UNAVAILABLE;

   public boolean ready() { return this == READY; }
   public boolean adjusted() { return this == ADJUSTED; }
}
