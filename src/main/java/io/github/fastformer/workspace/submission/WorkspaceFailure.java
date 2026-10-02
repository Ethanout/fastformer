package io.github.fastformer.workspace.submission;

/** Stable failure categories sent with a workspace result. */
public enum WorkspaceFailure {
   UNKNOWN("operation_submit_rejected"),
   INVALID_PLAN("workspace_invalid_plan"),
   INVALID_BLOCK_ENTITY("workspace_invalid_block_entity"),
   SNAPSHOT_UNAVAILABLE("workspace_snapshot_unavailable"),
   WORLD_CHANGED("workspace_world_changed"),
   CALLBACK_CONFLICT("workspace_world_changed"),
   WRITE_DID_NOT_MATCH_TARGET("workspace_write_failed");

   private final String messageKey;
   WorkspaceFailure(String key) { messageKey = "fastformer.message." + key; }
   public String messageKey() { return messageKey; }
}
