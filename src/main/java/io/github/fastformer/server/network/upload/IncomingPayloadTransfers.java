package io.github.fastformer.server.network.upload;

import io.github.fastformer.network.codec.OperationWorkspacePlanCodec;
import io.github.fastformer.protocol.upload.UploadChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Incomplete chunks owned and accessed by one upload system on its server thread. */
final class IncomingPayloadTransfers {
   private static final long TRANSFER_TIMEOUT_NANOS = 30_000_000_000L;
   private static final int MAX_TRANSFERS = 128;
   private final Map<UploadKind, Map<UUID, ChunkedPayloadTransfer>> lanes = new EnumMap<>(UploadKind.class);
   private final Consumer<ExpiredTransfer> onEvicted;
   private final long globalByteLimit;
   private final long ownerByteLimit;

   IncomingPayloadTransfers() { this(ignored -> {}); }

   IncomingPayloadTransfers(Consumer<ExpiredTransfer> onEvicted) {
      this(onEvicted, 128L * 1024 * 1024, OperationWorkspacePlanCodec.MAX_COMPRESSED_BYTES);
   }

   IncomingPayloadTransfers(Consumer<ExpiredTransfer> onEvicted, long globalByteLimit, long ownerByteLimit) {
      if (globalByteLimit <= 0 || ownerByteLimit <= 0 || ownerByteLimit > globalByteLimit) {
         throw new IllegalArgumentException("Invalid upload memory budget");
      }
      this.onEvicted = Objects.requireNonNull(onEvicted);
      this.globalByteLimit = globalByteLimit;
      this.ownerByteLimit = ownerByteLimit;
      for (UploadKind kind : UploadKind.values()) lanes.put(kind, new HashMap<>());
   }

   byte[] accept(UploadKind kind, UUID owner, UploadChunk chunk) throws IOException {
      Map<UUID, ChunkedPayloadTransfer> transfers = lanes.get(kind);
      ChunkedPayloadTransfer transfer = currentTransfer(kind, owner);
      if (transfer == null || !transfer.transferId().equals(chunk.transferId())) {
         if (chunk.chunkIndex() != 0) throw new IOException(kind + " transfer must start with chunk zero");
         evict(kind, owner);
         if (lanes.values().stream().mapToInt(Map::size).sum() >= MAX_TRANSFERS) {
            throw new IOException("Too many uploads");
         }
         transfer = new ChunkedPayloadTransfer(chunk.transferId(), chunk.chunkCount());
         transfers.put(owner, transfer);
      }
      if (transfer.chunkCount() != chunk.chunkCount()) throw new IOException(kind + " transfer metadata changed");
      byte[] data = chunk.data();
      int additionalBytes = data == null || transfer.hasChunk(chunk.chunkIndex()) ? 0 : data.length;
      if (data == null || retainedBytes(null) + additionalBytes > globalByteLimit
         || retainedBytes(owner) + additionalBytes > ownerByteLimit) {
         throw new IOException("Upload memory budget exceeded");
      }
      byte[] completed = transfer.accept(chunk.chunkIndex(), data);
      if (completed != null) transfers.remove(owner, transfer);
      return completed;
   }

   void forget(UUID owner) {
      if (owner != null) for (UploadKind kind : UploadKind.values()) forget(kind, owner);
   }

   void forget(UploadKind kind, UUID owner) { if (owner != null) evict(kind, owner); }

   void forget(UploadKind kind, UUID owner, UUID transferId) {
      ChunkedPayloadTransfer transfer = lanes.get(kind).get(owner);
      if (transfer != null && transfer.transferId().equals(transferId)) evict(kind, owner);
   }

   void clear() { lanes.values().forEach(Map::clear); }

   boolean contains(UUID owner, UUID transferId) {
      return lanes.values().stream().map(lane -> lane.get(owner))
         .anyMatch(transfer -> transfer != null && transfer.transferId().equals(transferId));
   }

   List<ExpiredTransfer> purgeExpired() { return purgeExpired(System.nanoTime()); }

   List<ExpiredTransfer> purgeExpired(long now) {
      var expired = new ArrayList<ExpiredTransfer>();
      lanes.forEach((kind, transfers) -> {
         var iterator = transfers.entrySet().iterator();
         while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!entry.getValue().expired(now, TRANSFER_TIMEOUT_NANOS)) continue;
            var removed = new ExpiredTransfer(entry.getKey(), entry.getValue().transferId(), kind);
            iterator.remove();
            onEvicted.accept(removed);
            expired.add(removed);
         }
      });
      return List.copyOf(expired);
   }

   record ExpiredTransfer(UUID owner, UUID transferId, UploadKind kind) {}

   private long retainedBytes(UUID owner) {
      return lanes.values().stream().flatMap(lane -> lane.entrySet().stream())
         .filter(entry -> owner == null || entry.getKey().equals(owner))
         .mapToLong(entry -> entry.getValue().bytes()).sum();
   }

   private void evict(UploadKind kind, UUID owner) {
      ChunkedPayloadTransfer removed = lanes.get(kind).remove(owner);
      if (removed != null) onEvicted.accept(new ExpiredTransfer(owner, removed.transferId(), kind));
   }

   private ChunkedPayloadTransfer currentTransfer(UploadKind kind, UUID owner) {
      ChunkedPayloadTransfer transfer = lanes.get(kind).get(owner);
      if (transfer != null && transfer.expired(System.nanoTime(), TRANSFER_TIMEOUT_NANOS)) {
         evict(kind, owner);
         return null;
      }
      return transfer;
   }
}
