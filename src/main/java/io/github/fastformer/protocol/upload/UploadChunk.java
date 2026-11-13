package io.github.fastformer.protocol.upload;

import java.util.UUID;

/** Shared chunk fields; each payload remains responsible for validating and copying its bytes. */
public interface UploadChunk {
   UUID transferId();
   int chunkIndex();
   int chunkCount();
   byte[] data();
}
