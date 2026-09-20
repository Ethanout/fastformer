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
      workspaceTransfers.remove(owner);
      shapeTransfers.remove(owner);
   }

   public void forgetWorkspace(UUID owner) {
      if (owner != null) {
         workspaceTransfers.remove(owner);
      }
   }

   public void forgetShape(UUID owner) {
      if (owner != null) {
         shapeTransfers.remove(owner);
      }
   }

   public void forgetWorkspace(UUID owner, UUID transferId) {
      forgetMatching(workspaceTransfers, owner, transferId);
   }

   public void forgetShape(UUID owner, UUID transferId) {
      forgetMatching(shapeTransfers, owner, transferId);
   }

   private static void forgetMatching(Map<UUID, ChunkedPayloadTransfer> transfers, UUID owner, UUID transferId) {
      if (owner != null && transferId != null) {
         transfers.computeIfPresent(owner, (key, transfer) ->
            transfer.transferId().equals(transferId) ? null : transfer);
      }
   }

   public void clear() {
      workspaceTransfers.clear();
      shapeTransfers.clear();
   }

   /** Removes stalled transfers even when the client sends no further chunks. */
   public java.util.List<ExpiredTransfer> purgeExpired() {
      return purgeExpired(System.nanoTime());
   }

   java.util.List<ExpiredTransfer> purgeExpired(long now) {
      var expired = new java.util.ArrayList<ExpiredTransfer>();
      workspaceTransfers.forEach((owner, transfer) -> {
         if (transfer.expired(now, TRANSFER_TIMEOUT_NANOS) && workspaceTransfers.remove(owner, transfer)) {
            expired.add(new ExpiredTransfer(owner, transfer.transferId(), false));
         }
      });
      shapeTransfers.forEach((owner, transfer) -> {
         if (transfer.expired(now, TRANSFER_TIMEOUT_NANOS) && shapeTransfers.remove(owner, transfer)) {
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

   private static byte[] accept(
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
         transfer = new ChunkedPayloadTransfer(transferId, chunkCount);
         transfers.put(owner, transfer);
      }
      if (transfer.chunkCount() != chunkCount) {
         throw new IOException(payloadName + " transfer metadata changed");
      }
      byte[] completed = transfer.accept(chunkIndex, data);
      if (completed != null) {
         transfers.remove(owner, transfer);
      }
      return completed;
   }

   private static ChunkedPayloadTransfer currentTransfer(
      Map<UUID, ChunkedPayloadTransfer> transfers,
      UUID owner
   ) {
      ChunkedPayloadTransfer transfer = transfers.get(owner);
      if (transfer != null && transfer.expired(System.nanoTime(), TRANSFER_TIMEOUT_NANOS)) {
         transfers.remove(owner, transfer);
         return null;
      }
      return transfer;
   }
}
