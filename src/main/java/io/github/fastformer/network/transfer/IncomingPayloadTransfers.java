package io.github.fastformer.network.transfer;

import io.github.fastformer.network.payload.operation.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.payload.placement.ShapePlacementPayload;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns incomplete client-to-server payload transfers for each player identity. */
public final class IncomingPayloadTransfers {
   private static final long TRANSFER_TIMEOUT_NANOS = 30_000_000_000L;

   private final Map<UUID, ChunkedPayloadTransfer> workspaceTransfers = new ConcurrentHashMap<>();
   private final Map<UUID, ChunkedPayloadTransfer> shapeTransfers = new ConcurrentHashMap<>();
   private final java.util.function.Consumer<ExpiredTransfer> onEvicted;
   private final long globalByteLimit;
   private final long ownerByteLimit;

   public IncomingPayloadTransfers() { this(ignored -> { }); }
   public IncomingPayloadTransfers(java.util.function.Consumer<ExpiredTransfer> onEvicted) {
      this(onEvicted, 128L * 1024 * 1024, io.github.fastformer.network.codec.OperationWorkspacePlanCodec.MAX_COMPRESSED_BYTES);
   }
   IncomingPayloadTransfers(java.util.function.Consumer<ExpiredTransfer> onEvicted, long globalByteLimit, long ownerByteLimit) {
      if (globalByteLimit <= 0 || ownerByteLimit <= 0 || ownerByteLimit > globalByteLimit) throw new IllegalArgumentException("Invalid upload memory budget");
      this.onEvicted = java.util.Objects.requireNonNull(onEvicted);
      this.globalByteLimit = globalByteLimit;
      this.ownerByteLimit = ownerByteLimit;
   }

   private long retainedBytes(UUID owner) {
      return java.util.stream.Stream.of(workspaceTransfers, shapeTransfers)
         .flatMap(map -> map.entrySet().stream())
         .filter(entry -> owner == null || entry.getKey().equals(owner))
         .mapToLong(entry -> entry.getValue().bytes()).sum();
   }

   public byte[] acceptWorkspace(UUID owner, OperationWorkspaceApplyPayload payload) throws IOException {
      return accept(
         workspaceTransfers,
         owner,
         payload.transferId(),
         payload.chunkIndex(),
         payload.chunkCount(),
         payload.data(),
         "Workspace"
      );
   }

   public byte[] acceptShape(UUID owner, ShapePlacementPayload payload) throws IOException {
      return accept(
         shapeTransfers,
         owner,
         payload.transferId(),
         payload.chunkIndex(),
         payload.chunkCount(),
         payload.data(),
         "Shape"
      );
   }

   public void forget(UUID owner) {
      if (owner == null) {
         return;
      }
      forgetWorkspace(owner);
      forgetShape(owner);
   }

   public void forgetWorkspace(UUID owner) {
      if (owner != null) {
         evict(workspaceTransfers, owner);
      }
   }

   public void forgetShape(UUID owner) {
      if (owner != null) {
         evict(shapeTransfers, owner);
      }
   }

   public void forgetWorkspace(UUID owner, UUID transferId) {
      forgetMatching(workspaceTransfers, owner, transferId);
   }

   public void forgetShape(UUID owner, UUID transferId) {
      forgetMatching(shapeTransfers, owner, transferId);
   }

   private void evict(Map<UUID, ChunkedPayloadTransfer> transfers, UUID owner) {
      var removed = transfers.remove(owner);
      if (removed != null) onEvicted.accept(new ExpiredTransfer(owner, removed.transferId(), transfers == shapeTransfers));
   }

   private void forgetMatching(Map<UUID, ChunkedPayloadTransfer> transfers, UUID owner, UUID transferId) {
      if (owner != null && transferId != null) {
         var transfer = transfers.get(owner);
         if (transfer != null && transfer.transferId().equals(transferId)) evict(transfers, owner);
      }
   }

   public void clear() {
      workspaceTransfers.clear();
      shapeTransfers.clear();
   }

   public boolean contains(UUID owner, UUID transferId) {
      var workspace = workspaceTransfers.get(owner);
      var shape = shapeTransfers.get(owner);
      return workspace != null && workspace.transferId().equals(transferId) || shape != null && shape.transferId().equals(transferId);
   }

   /** Removes stalled transfers even when the client sends no further chunks. */
   public java.util.List<ExpiredTransfer> purgeExpired() {
      return purgeExpired(System.nanoTime());
   }

   java.util.List<ExpiredTransfer> purgeExpired(long now) {
      var expired = new java.util.ArrayList<ExpiredTransfer>();
      workspaceTransfers.forEach((owner, transfer) -> {
         if (transfer.expired(now, TRANSFER_TIMEOUT_NANOS) && workspaceTransfers.remove(owner, transfer)) {
            onEvicted.accept(new ExpiredTransfer(owner, transfer.transferId(), false));
            expired.add(new ExpiredTransfer(owner, transfer.transferId(), false));
         }
      });
      shapeTransfers.forEach((owner, transfer) -> {
         if (transfer.expired(now, TRANSFER_TIMEOUT_NANOS) && shapeTransfers.remove(owner, transfer)) {
            onEvicted.accept(new ExpiredTransfer(owner, transfer.transferId(), true));
            expired.add(new ExpiredTransfer(owner, transfer.transferId(), true));
         }
      });
      return java.util.List.copyOf(expired);
   }

   public record ExpiredTransfer(UUID owner, UUID transferId, boolean shape) {
      public ExpiredTransfer(UUID owner, UUID transferId) {
         this(owner, transferId, false);
      }
   }

   private byte[] accept(
      Map<UUID, ChunkedPayloadTransfer> transfers,
      UUID owner,
      UUID transferId,
      int chunkIndex,
      int chunkCount,
      byte[] data,
      String payloadName
   ) throws IOException {
      ChunkedPayloadTransfer transfer = currentTransfer(transfers, owner);
      if (transfer == null || !transfer.transferId().equals(transferId)) {
         if (chunkIndex != 0) {
            throw new IOException(payloadName + " transfer must start with chunk zero");
         }
         if (transfer != null) {
            transfers.remove(owner, transfer);
            onEvicted.accept(new ExpiredTransfer(owner, transfer.transferId(), transfers == shapeTransfers));
         }
         if (workspaceTransfers.size() + shapeTransfers.size() >= 128) throw new IOException("Too many uploads");
         transfer = new ChunkedPayloadTransfer(transferId, chunkCount);
         transfers.put(owner, transfer);
      }
      if (transfer.chunkCount() != chunkCount) {
         throw new IOException(payloadName + " transfer metadata changed");
      }
      int additionalBytes = data == null || transfer.hasChunk(chunkIndex) ? 0 : data.length;
      if (data == null || retainedBytes(null) + additionalBytes > globalByteLimit
         || retainedBytes(owner) + additionalBytes > ownerByteLimit) {
         throw new IOException("Upload memory budget exceeded");
      }
      byte[] completed = transfer.accept(chunkIndex, data);
      if (completed != null) {
         transfers.remove(owner, transfer);
      }
      return completed;
   }

   private ChunkedPayloadTransfer currentTransfer(
      Map<UUID, ChunkedPayloadTransfer> transfers,
      UUID owner
   ) {
      ChunkedPayloadTransfer transfer = transfers.get(owner);
      if (transfer != null && transfer.expired(System.nanoTime(), TRANSFER_TIMEOUT_NANOS)) {
         transfers.remove(owner, transfer);
         onEvicted.accept(new ExpiredTransfer(owner, transfer.transferId(), transfers == shapeTransfers));
         return null;
      }
      return transfer;
   }
}
