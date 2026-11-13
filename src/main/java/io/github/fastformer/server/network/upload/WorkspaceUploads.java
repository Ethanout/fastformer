package io.github.fastformer.server.network.upload;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.network.codec.OperationWorkspacePlanCodec;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.protocol.upload.UploadChunk;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.submission.WorkspaceSubmissionLedger;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.WorkspaceAdmission;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** One server's complete upload lifecycle. Only decoding leaves the owning server thread. */
public final class WorkspaceUploads implements AutoCloseable {
   private final MinecraftServer server;
   private final Executor decoder;
   private final IncomingPayloadTransfers incoming = new IncomingPayloadTransfers(this::evicted);
   private final WorkspaceDecodeQueue decodes = new WorkspaceDecodeQueue();
   private final WorkspaceReceipts receipts;
   private boolean closed;

   WorkspaceUploads(MinecraftServer server, Executor decoder, WorkspaceSubmissionLedger submissions) {
      this.server = server;
      this.decoder = java.util.Objects.requireNonNull(decoder);
      this.receipts = new WorkspaceReceipts(server, submissions);
   }

   public WorkspaceReceipts receipts() { return receipts; }

   public void receive(ServerPlayer player, UploadKind kind, UploadChunk chunk) {
      requirePlayer(player);
      if (closed || server.getPlayerList().getPlayer(player.getUUID()) != player) return;
      try {
         if (!admit(player, chunk.transferId())) return;
         // Expiring/replacing an old transfer must happen before remembering the new scope.
         byte[] completed = incoming.accept(kind, player.getUUID(), chunk);
         receipts.remember(player, chunk.transferId(), chunk.chunkIndex());
         if (completed != null) decode(player, kind, chunk.transferId(), completed);
      } catch (IOException | RuntimeException exception) {
         incoming.forget(kind, player.getUUID(), chunk.transferId());
         receipts.failAdmission(player, chunk.transferId());
      }
   }

   public void forget(ServerPlayer player) {
      requirePlayer(player);
      decodes.cancel(player.getUUID());
      incoming.forget(player.getUUID());
      receipts.forget(player.getUUID());
   }

   public void tick() {
      UploadSystem.requireThread(server);
      if (closed) return;
      for (var expired : incoming.purgeExpired()) {
         ServerPlayer online = server.getPlayerList().getPlayer(expired.owner());
         // Without an online player, an incomplete upload has no admitted dimension to record.
         if (online != null) receipts.failAdmission(online, expired.transferId());
      }
      // Fatal decode/dispatch failures may release capacity without reaching a server callback.
      receipts.forgetUnowned(this::owns);
   }

   @Override public void close() {
      UploadSystem.requireThread(server);
      if (closed) return;
      closed = true;
      decodes.clear();
      incoming.clear();
      receipts.close();
   }

   private boolean admit(ServerPlayer player, UUID transfer) {
      if (decodes.contains(player.getUUID(), transfer)) return false;
      var recorded = OperationSystem.of(player).recordedOutcome(player, transfer);
      if (recorded != OperationSubmissionOutcome.UNKNOWN) {
         receipts.admission(player, transfer, WorkspaceAdmission.replayed(recorded));
         return false;
      }
      if (!ServerInputDispatcher.canOperate(player) || ServerInputDispatcher.interactionBlocked(player)
         || decodes.busy(player.getUUID())) {
         incoming.forget(player.getUUID());
         receipts.failAdmission(player, transfer);
         return false;
      }
      return true;
   }

   private void decode(ServerPlayer player, UploadKind kind, UUID transfer, byte[] bytes) {
      var dimension = player.level().dimension();
      var scope = receipts.requestScope(player.getUUID(), transfer);
      var blocks = player.registryAccess().lookupOrThrow(Registries.BLOCK);
      boolean accepted = decodes.submit(player.getUUID(), transfer,
         () -> OperationWorkspacePlanCodec.decodeCompressed(bytes, blocks),
         decoder, server, (plan, error) -> {
            UploadSystem.requireThread(server);
            if (closed) return;
            if (server.getPlayerList().getPlayer(player.getUUID()) != player || player.level().dimension() != dimension
               || !java.util.Objects.equals(scope, PreviewSync.callbackScope(player))) {
               receipts.forget(player.getUUID(), transfer);
               return;
            }
            if (error != null) { receipts.failAdmission(player, transfer); return; }
            try {
               var admission = ServerInputDispatcher.applyWorkspace(player, transfer, plan);
               if (kind == UploadKind.SHAPE && admission.isQueued()) SessionSystem.of(player).cancelEditing(player);
               receipts.admission(player, transfer, admission);
            } catch (RuntimeException failure) {
               // A task may already own the transfer. The receipt guard must decide whether failure is still admissible.
               com.mojang.logging.LogUtils.getLogger().warn("Unable to admit decoded workspace {}", transfer, failure);
               receipts.failAdmission(player, transfer);
            }
         });
      if (!accepted) receipts.failAdmission(player, transfer);
   }

   private void evicted(IncomingPayloadTransfers.ExpiredTransfer transfer) {
      if (!owns(transfer.owner(), transfer.transferId())) {
         receipts.forget(transfer.owner(), transfer.transferId());
      }
   }

   private boolean owns(UUID owner, UUID transfer) {
      return incoming.contains(owner, transfer) || decodes.contains(owner, transfer)
         || SessionSystem.find(server)
            .map(system -> system.operationTasks().transferActive(owner, transfer)).orElse(false);
   }

   private void requirePlayer(ServerPlayer player) {
      UploadSystem.requireThread(server);
      if (player.getServer() != server) throw new IllegalArgumentException("Upload player belongs to another server");
   }
}
