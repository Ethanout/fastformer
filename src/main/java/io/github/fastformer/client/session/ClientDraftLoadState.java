package io.github.fastformer.client.session;

/**
 * Mutually exclusive result of reading the durable operation draft of one client scope.
 *
 * <p>Only a completed read or an explicit absence ends the read. A failure keeps the file,
 * records the reason, and waits for an explicit retry boundary. No state deletes the file.
 */
public enum ClientDraftLoadState {
   /** No read was attempted for this scope in this client process. */
   NOT_LOADED,
   /** The read completed. The scope holds a staged draft, or the scope has no draft file. */
   READY,
   /**
    * The draft file was read, but the receipt file could not say whether its submission
    * already applied. The read is not complete: the draft stays out of the editor, and a
    * readable receipt later re-opens the read so the same file can be judged again.
    */
   AWAITING_RECEIPT,
   /** The durable read failed. The file stays, and one later boundary may read it again. */
   RETRYABLE_FAILURE,
   /** The file format version is not readable by this client. The file stays untouched. */
   VERSION_INCOMPATIBLE,
   /** The file content did not decode. The file stays untouched. */
   CORRUPT;

   /** True while a read attempt is not allowed again without an explicit retry boundary. */
   public boolean blocksRead() {
      return this != NOT_LOADED;
   }

   /** True when an explicit retry boundary may clear this state and read once more. */
   public boolean mayRetry() {
      return this != NOT_LOADED && this != READY;
   }
}
