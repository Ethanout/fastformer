package io.github.fastformer.server.network.upload;

import com.mojang.logging.LogUtils;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceReceiptQueryPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.submission.WorkspaceSubmissionLedger;
import io.github.fastformer.server.submission.WorkspaceResultSink;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.WorkspaceAdmission;
import io.github.fastformer.workspace.submission.WorkspaceFailure;
import io.github.fastformer.workspace.submission.WorkspaceCompletion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Remembers request scopes and records outcomes before delivery, including offline completions. */
public final class WorkspaceReceipts implements WorkspaceResultSink {
   private record RequestKey(UUID owner, UUID transfer) {}
   private final MinecraftServer server;
   private final Map<RequestKey, OperationCallbackScope> scopes = new HashMap<>();
   private boolean closed;

   WorkspaceReceipts(MinecraftServer server) { this.server = server; }

   void remember(ServerPlayer player, UUID transfer, int chunkIndex) {
      requirePlayer(player);
      if (closed || chunkIndex != 0 || OperationSystem.of(player).recordedOutcome(player, transfer).open()) return;
      scopes.putIfAbsent(new RequestKey(player.getUUID(), transfer), PreviewSync.callbackScope(player));
   }

   OperationCallbackScope requestScope(UUID owner, UUID transfer) {
      UploadSystem.requireThread(server);
      return scopes.get(new RequestKey(owner, transfer));
   }

   void forget(UUID owner) {
      UploadSystem.requireThread(server);
      scopes.keySet().removeIf(key -> key.owner().equals(owner));
   }

   void forget(UUID owner, UUID transfer) {
      UploadSystem.requireThread(server);
      scopes.remove(new RequestKey(owner, transfer));
   }

   void forgetUnowned(java.util.function.BiPredicate<UUID, UUID> retained) {
      UploadSystem.requireThread(server);
      scopes.keySet().removeIf(key -> !retained.test(key.owner(), key.transfer()));
   }

   void close() {
      UploadSystem.requireThread(server);
      closed = true;
      scopes.clear();
   }

   /** The ledger decides the delivered outcome; transport failure must not become admission failure. */
   @Override public void finish(WorkspaceCompletion result, ServerPlayer player) {
      UploadSystem.requireThread(server);
      if (closed) return;
      if (player != null) {
         requirePlayer(player);
         if (!player.getUUID().equals(result.owner())) throw new IllegalArgumentException("Workspace result belongs to another player");
      }
      var recorded = WorkspaceSubmissionLedger.finish(server, result.owner(), result.dimension(), result.transfer(),
         result.applied(), result.retryable(), result.recoveryCreated());
      var scope = scopes.remove(new RequestKey(result.owner(), result.transfer()));
      if (player != null) deliver(player, result.transfer(), recorded, scope, result.failedIds(), result.failedTargets(), result.failure());
   }

   public void admission(ServerPlayer player, UUID transfer, WorkspaceAdmission admission) {
      requirePlayer(player);
      if (closed || transfer == null || admission == null || admission.keepsRequestScope()) return;
      var scope = scopes.remove(new RequestKey(player.getUUID(), transfer));
      if (!admission.sendsResult()) return;
      var outcome = admission.deliveredOutcome();
      if (admission.kind() == WorkspaceAdmission.Kind.REJECTED) {
         outcome = WorkspaceSubmissionLedger.finish(server, player.getUUID(), player.level().dimension().location(),
            transfer, false, true, false);
      }
      deliver(player, transfer, outcome, scope, List.of(), List.of(), WorkspaceFailure.UNKNOWN);
   }

   void failAdmission(ServerPlayer player, UUID transfer) {
      requirePlayer(player);
      if (closed || transfer == null
         || OperationSystem.of(player).recordedOutcome(player, transfer) != OperationSubmissionOutcome.UNKNOWN
         || OperationSystem.of(player).transferActive(player.getUUID(), transfer)) return;
      finish(new WorkspaceCompletion(player.getUUID(), player.level().dimension().location(), transfer,
         false, true, false, List.of(), List.of(), WorkspaceFailure.UNKNOWN), player);
   }

   public void query(ServerPlayer player, OperationWorkspaceReceiptQueryPayload payload) {
      requirePlayer(player);
      if (closed) return;
      var entries = new ArrayList<OperationWorkspaceReceiptPayload.Entry>(payload.transferIds().size());
      for (UUID transfer : payload.transferIds()) {
         entries.add(new OperationWorkspaceReceiptPayload.Entry(transfer, answer(player.getUUID(), payload.dimension(), transfer)));
      }
      PacketDistributor.sendToPlayer(player, new OperationWorkspaceReceiptPayload(payload.dimension(), entries));
   }

   private OperationSubmissionOutcome answer(UUID owner, ResourceLocation dimension, UUID transfer) {
      var recorded = WorkspaceSubmissionLedger.outcomeFor(server, owner, dimension, transfer);
      if (recorded == OperationSubmissionOutcome.IN_PROGRESS
         && !OperationSystem.find(server).map(system -> system.transferActive(owner, transfer)).orElse(false)) {
         return OperationSubmissionOutcome.UNKNOWN;
      }
      return recorded;
   }

   private void deliver(ServerPlayer player, UUID transfer, OperationSubmissionOutcome outcome,
      OperationCallbackScope remembered, List<Integer> failedIds, List<BlockPos> failedTargets, WorkspaceFailure failure) {
      if (outcome == null || !outcome.deliverable()) return;
      var scope = remembered == null ? PreviewSync.callbackScope(player) : remembered;
      try {
         if (player.connection != null && player.connection.hasChannel(OperationWorkspaceResultPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, new OperationWorkspaceResultPayload(
               transfer, outcome.applied(), outcome.retryable(), failedIds, failedTargets
            ).withCallbackScope(scope).withFailure(failure));
         }
      } catch (RuntimeException exception) {
         LogUtils.getLogger().warn("Unable to deliver a FastFormer workspace result", exception);
      }
   }

   private void requirePlayer(ServerPlayer player) {
      UploadSystem.requireThread(server);
      if (player.getServer() != server) throw new IllegalArgumentException("Upload player belongs to another server");
   }
}
