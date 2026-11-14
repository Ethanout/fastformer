package io.github.fastformer.client.operation.submission;

/** The action's outcome travels with the call; later feedback cannot consume another action's failure. */
public enum SubmissionActionResult {
   ACCEPTED(null),
   PENDING("fastformer.message.operation_submit_pending"),
   INVALID("fastformer.message.operation_submit_invalid"),
   NO_CONTENT("fastformer.message.operation_submit_no_content"),
   UNAVAILABLE("fastformer.message.operation_submit_unavailable"),
   STATE_CHANGED("fastformer.message.operation_submit_state_changed"),
   STORAGE_FAILED("fastformer.message.operation_submit_storage_failed");

   private final String failureKey;

   SubmissionActionResult(String failureKey) { this.failureKey = failureKey; }
   public boolean accepted() { return this == ACCEPTED; }
   public String failureKey() { return failureKey; }
}
