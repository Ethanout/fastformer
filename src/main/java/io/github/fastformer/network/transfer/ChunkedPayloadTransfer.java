package io.github.fastformer.network.transfer;

import io.github.fastformer.fastplace.OperationWorkspacePlanCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Reassembles one bounded, ordered-independent network payload transfer. */
public final class ChunkedPayloadTransfer {
   public static final int MAX_CHUNKS = 4096;
   private final UUID transferId;
   private final int chunkCount;
   private final byte[][] chunks;
   private int received;
   private int bytes;
   private long updatedAt = System.nanoTime();

   public ChunkedPayloadTransfer(UUID transferId, int chunkCount) {
      if (transferId == null || chunkCount < 1 || chunkCount > MAX_CHUNKS) {
         throw new IllegalArgumentException("A chunked transfer requires an id and at least one chunk");
      }
      this.transferId = transferId;
      this.chunkCount = chunkCount;
      this.chunks = new byte[chunkCount][];
   }

   public UUID transferId() {
      return transferId;
   }

   public int chunkCount() {
      return chunkCount;
   }

   /** Adds a chunk and returns the complete payload once every chunk arrived. */
   public byte[] accept(int index, byte[] data) throws IOException {
      if (index < 0 || index >= chunkCount || data == null || data.length == 0) {
         throw new IOException("Invalid chunk in transfer " + transferId);
      }
      if (chunks[index] == null) {
         if ((long)bytes + data.length > OperationWorkspacePlanCodec.MAX_COMPRESSED_BYTES) {
            throw new IOException("Chunked transfer exceeds compressed limit");
         }
         chunks[index] = data.clone();
         bytes += data.length;
         received++;
         updatedAt = System.nanoTime();
      }
      if (received != chunkCount) {
         return null;
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream(bytes);
      for (byte[] chunk : chunks) {
         output.writeBytes(chunk);
      }
      return output.toByteArray();
   }

   public boolean expired(long now, long timeoutNanos) {
      if (timeoutNanos < 0L) {
         throw new IllegalArgumentException("Transfer timeout must not be negative");
      }
      return now - updatedAt >= timeoutNanos;
   }
}
