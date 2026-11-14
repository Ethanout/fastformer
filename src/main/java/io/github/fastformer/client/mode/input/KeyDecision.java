package io.github.fastformer.client.mode.input;

/** Mode-specific actions; PASS continues to the shared shortcut/cancellation pipeline. */
public enum KeyDecision {
   PASS, CONSUME, TOGGLE_REPLACEMENT, REPLACEMENT_UNDO, REPLACEMENT_REDO, START_FILL, CONFIRM_FILL
}
