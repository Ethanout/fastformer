package io.github.fastformer.client.operation.submission;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Connection and input-request boundary; editor code does not query the input coordinator. */
public interface SubmissionEnvironment {
   boolean available();
   boolean supported();
   BooleanSupplier freezeOwnership();
   boolean begin(UUID transferId);
   void abort(UUID transferId);
   void acknowledge(UUID transferId);
   void expire(UUID transferId);
}
