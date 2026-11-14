package io.github.fastformer.client.session.receipt;

/** Recorded result of reading a scope's receipt file. */
public enum ReceiptReadState {
   NOT_LOADED,
   READY,
   RETRYABLE_FAILURE,
   VERSION_INCOMPATIBLE;

   public boolean mayRetry() {
      return this == RETRYABLE_FAILURE || this == VERSION_INCOMPATIBLE;
   }
}
