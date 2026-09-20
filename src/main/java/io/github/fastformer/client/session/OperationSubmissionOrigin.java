package io.github.fastformer.client.session;

/** Whether a server selection identity can confirm a submission after a reconnect. */
public enum OperationSubmissionOrigin {
   /**
    * The workspace came from the live server selection. A reconnect must match the
    * server identity before the draft returns.
    */
   SERVER_SELECTION,
   /**
    * The workspace exists only on the client, for example a pasted clipboard. The
    * server owns no selection for it, so no identity can ever confirm it. The
    * receipt scope alone decides where the draft returns.
    */
   LOCAL_ONLY
}
