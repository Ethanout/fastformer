package io.github.fastformer.client.operation.clipboard;

/** Immutable outcome returned with the action that produced it. */
public enum ClipboardActionResult {
   SUCCESS(null),
   EMPTY("fastformer.message.operation_copy_empty"),
   WORK_LIMIT("fastformer.message.workspace_work_too_large"),
   RESULT_LIMIT("fastformer.message.workspace_result_too_large"),
   STORAGE_FAILED("fastformer.message.operation_copy_storage_failed"),
   PENDING("fastformer.message.operation_paste_pending"),
   PART_LIMIT("fastformer.message.operation_paste_limit"),
   INVALID_CLIPBOARD("fastformer.message.operation_paste_clipboard_invalid"),
   NO_ANCHOR("fastformer.message.operation_paste_no_anchor"),
   UNAVAILABLE("fastformer.message.operation_paste_unavailable");

   private final String failureKey;

   ClipboardActionResult(String failureKey) { this.failureKey = failureKey; }
   public boolean succeeded() { return this == SUCCESS; }
   public String failureKey() { return failureKey; }
}
