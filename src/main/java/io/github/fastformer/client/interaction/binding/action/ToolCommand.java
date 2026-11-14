package io.github.fastformer.client.interaction.binding.action;

/** Atomic commands provided by the tool input adapter, without key or mode decisions. */
public enum ToolCommand {
   TOGGLE_REPLACEMENT, BEGIN_REPLACEMENT, APPLY_REPLACEMENT, END_REPLACEMENT,
   HISTORY_UNDO, HISTORY_REDO, START_FILL, CONFIRM_FILL, CAPTURE_SWAP_KEY
}
