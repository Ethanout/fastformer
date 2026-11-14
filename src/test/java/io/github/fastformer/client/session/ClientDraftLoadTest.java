package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.session.draft.ClientDraftLoadState;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.ClientOperationDraftCodec;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceiptStore;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.common.selection.OperationSelectionMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the explicit read result of a durable scope draft and its deletion boundaries. */
class ClientDraftLoadTest {
   @TempDir
   Path directory;

   @Test
   void draftReadUsesItsOwnerWhileAnotherWorldIsActive() throws Exception {
      var manager = new ClientSessionSystem();
      UUID player = UUID.randomUUID();
      var owner = manager.forScope(new ClientScopeId("server-a", "minecraft:overworld", player));
      var active = manager.activateScope(player, "server-b", "minecraft:the_nether");
      Path file = directory.resolve("corrupt-current-version.nbt.gz");
      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION));

      owner.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.CORRUPT, manager.attemptDraftLoad(owner, null));
      assertEquals(ClientDraftLoadState.CORRUPT, manager.draftLoadState(owner.id()));
      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.currentDraftLoadState());
      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.draftLoadState(active.id()));
      assertFalse(active.suspendedOperationDraft());
      assertTrue(Files.exists(file));
   }

   @Test
   void failedReadKeepsTheFileAndBlocksAnotherAttempt() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-a", "minecraft:overworld", UUID.randomUUID()));
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("transient.nbt.gz");
      Files.write(file, new byte[]{1, 2, 3, 4});

      session.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.attemptDraftLoad(session, null));
      assertEquals(4L, Files.size(file));
      assertFalse(session.suspendedOperationDraft());

      // A readable file appears. The recorded failure must still block the read, so no
      // repeated attempt and no repeated message can happen.
      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION));
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.attemptDraftLoad(session, null));
      assertFalse(session.suspendedOperationDraft());
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.draftLoadState(key));
   }

   @Test
   void versionMismatchHasItsOwnStateAndKeepsTheFile() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-b", "minecraft:overworld", UUID.randomUUID()));
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("future.nbt.gz");
      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION + 1));
      long size = Files.size(file);

      session.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.attemptDraftLoad(session, null));
      assertTrue(Files.exists(file));
      assertEquals(size, Files.size(file));
      assertFalse(session.suspendedOperationDraft());
      assertTrue(manager.draftLoadState(key).mayRetry());
   }

   @Test
   void unreadableContentKeepsTheFileAndReportsCorruption() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-c", "minecraft:overworld", UUID.randomUUID()));
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("broken.nbt.gz");
      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION));
      long size = Files.size(file);

      session.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.CORRUPT, manager.attemptDraftLoad(session, null));
      assertTrue(Files.exists(file));
      assertEquals(size, Files.size(file));
      assertFalse(session.suspendedOperationDraft());
   }

   @Test
   void aMissingFileCompletesTheReadWithoutADraft() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-d", "minecraft:overworld", UUID.randomUUID()));
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("absent.nbt.gz");

      session.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(session, null));
      assertFalse(session.suspendedOperationDraft());
      assertFalse(manager.draftLoadState(key).mayRetry());
      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(session, null));
   }

   @Test
   void anUnchangedScopeNeverRetriesAFailedRead() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-e", "minecraft:overworld", UUID.randomUUID()));
      UUID playerId = session.id().playerId();
      Path file = this.directory.resolve("repeated.nbt.gz");
      Files.write(file, new byte[]{7, 7});

      manager.activateScope(playerId, "load-e", "minecraft:overworld");
      session.drafts().useFile(file);
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(session, null)
      );

      for (int tick = 0; tick < 40; tick++) {
         manager.activateScope(playerId, "load-e", "minecraft:overworld");
      }

      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.currentDraftLoadState());
      assertFalse(session.suspendedOperationDraft());
   }

   @Test
   void reEnteringAnEnvironmentRetriesOnlyATransientFailure() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-f", "minecraft:overworld", UUID.randomUUID()));
      UUID playerId = session.id().playerId();
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("boundary.nbt.gz");

      manager.activateScope(playerId, "load-f", "minecraft:overworld");
      Files.write(file, new byte[]{5, 5});
      session.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.attemptDraftLoad(session, null));

      manager.activateScope(playerId, "load-f", "minecraft:the_nether");
      manager.activateScope(playerId, "load-f", "minecraft:overworld");

      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.draftLoadState(key));

      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION + 1));
      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.attemptDraftLoad(session, null));

      manager.activateScope(playerId, "load-f", "minecraft:the_nether");
      manager.activateScope(playerId, "load-f", "minecraft:overworld");

      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.draftLoadState(key));
   }

   @Test
   void explicitRetryClearsOneFailedRead() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-g", "minecraft:overworld", UUID.randomUUID()));
      UUID playerId = session.id().playerId();
      Path file = this.directory.resolve("retry.nbt.gz");

      manager.activateScope(playerId, "load-g", "minecraft:overworld");
      Files.write(file, new byte[]{3, 3});
      session.drafts().useFile(file);
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(session, null)
      );

      assertTrue(manager.retryCurrentDraftLoad());
      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.currentDraftLoadState());
      assertFalse(manager.retryCurrentDraftLoad());

      Files.delete(file);
      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(session, null)
      );
   }

   @Test
   void explicitRetryReadsAValidDraftAndStagesIt() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("load-h", "minecraft:overworld", UUID.randomUUID()));
      UUID playerId = session.id().playerId();
      Path file = this.directory.resolve("retry-valid.nbt.gz");

      manager.activateScope(playerId, "load-h", "minecraft:overworld");
      Files.write(file, new byte[]{3, 3});
      session.drafts().useFile(file);
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(session, null)
      );
      assertTrue(Files.exists(file));

      // A readable draft now replaces the broken file. The recorded failure still blocks the
      // read, so the failure path stays stable until an explicit boundary arrives.
      OperationClipboardStore.save(file, validDraftTag());
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(session, null)
      );
      assertFalse(session.suspendedOperationDraft());

      // The explicit boundary clears the failure, and the next read stages the draft.
      assertTrue(manager.retryCurrentDraftLoad());
      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(session, null)
      );
      assertTrue(session.suspendedOperationDraft());
      assertTrue(session.restoreSuspendedOperationDraft(validDraftIdentity()));
      assertFalse(session.suspendedOperationDraft());
   }

   /**
    * A draft whose submission already applied never reaches the staged slot.
    *
    * <p>Clearing the copies is not enough by itself, because a delete can fail. This is the
    * boundary that keeps applied work out of the editor, where the player could send it a
    * second time and the server would write the same blocks again.</p>
    */
   @Test
   void anAppliedDraftNeverReachesTheStagedSlot() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("guard-a", "minecraft:overworld", UUID.randomUUID()));
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("applied.nbt.gz");
      UUID transferId = UUID.randomUUID();
      OperationClipboardStore.save(file, draftTagWithSubmission(transferId));

      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(new OperationSubmissionReceipt(
         transferId, OperationSubmissionOrigin.LOCAL_ONLY, null,
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
         io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome.APPLIED, 1L
      ));

      session.drafts().useFile(file);
      Path receiptFile = directory.resolve("receipts.nbt.gz");
      receipts.save(receiptFile);
      session.receipts().useFile(receiptFile);
      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(session, null)
      );

      // The draft did not enter the staged slot, so no restore prompt can offer it.
      assertFalse(session.suspendedOperationDraft());
      // The file stays, so the pending cleanup still has something to retry.
      assertTrue(Files.exists(file));
      assertEquals(1, receipts.needingCleanup().size());
   }

   /** A draft whose submission has no applied result is still staged. */
   @Test
   void aDraftWithoutAnAppliedResultIsStillStaged() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("guard-b", "minecraft:overworld", UUID.randomUUID()));
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("pending.nbt.gz");
      UUID transferId = UUID.randomUUID();
      OperationClipboardStore.save(file, draftTagWithSubmission(transferId));

      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(new OperationSubmissionReceipt(
         transferId, OperationSubmissionOrigin.LOCAL_ONLY, null,
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
         io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome.IN_PROGRESS, 1L
      ));

      session.drafts().useFile(file);
      Path receiptFile = directory.resolve("receipts.nbt.gz");
      receipts.save(receiptFile);
      session.receipts().useFile(receiptFile);
      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(session, null)
      );

      // The result is still open, so the draft must stay available to the player.
      assertTrue(session.suspendedOperationDraft());
   }

   /** An unsubmitted draft belongs to the player, so no receipt can forbid it. */
   @Test
   void aDraftThatNamesNoSubmissionIsStillStaged() throws Exception {
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope session = manager.forScope(new ClientScopeId("guard-c", "minecraft:overworld", UUID.randomUUID()));
      ClientScopeId key = session.id();
      Path file = this.directory.resolve("unnamed.nbt.gz");
      OperationClipboardStore.save(file, validDraftTag());

      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(new OperationSubmissionReceipt(
         UUID.randomUUID(), OperationSubmissionOrigin.LOCAL_ONLY, null,
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
         io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome.APPLIED, 1L
      ));

      session.drafts().useFile(file);
      Path receiptFile = directory.resolve("receipts.nbt.gz");
      receipts.save(receiptFile);
      session.receipts().useFile(receiptFile);
      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(session, null)
      );

      assertTrue(session.suspendedOperationDraft());
   }

   /** A draft file that names a submission, for the load guard. */
   private static CompoundTag draftTagWithSubmission(UUID transferId) {
      CompoundTag tag = validDraftTag();
      tag.putBoolean("Unsubmitted", false);
      tag.putString("Origin", OperationSubmissionOrigin.SERVER_SELECTION.name());
      tag.putUUID("SubmissionId", transferId);
      return tag;
   }

   private static CompoundTag draftTag(int version) {
      CompoundTag tag = new CompoundTag();
      tag.putInt("Version", version);
      return tag;
   }

   @Test
   void unansweredReconnectKeepsTheStagedDraft() throws Exception {
      ClientSessionSystem.instance().operations().onDisconnected();
      var manager = ClientSessionSystem.instance();
      UUID playerId = UUID.randomUUID();
      var key = new ClientScopeId("reconnect-timeout", "minecraft:overworld", playerId);
      manager.forScope(key).drafts().useFile(directory.resolve("reconnect-draft.nbt.gz"));
      manager.forScope(key).receipts().useFile(directory.resolve("reconnect-receipts.nbt.gz"));
      ClientScope session = manager.activateScope(
         playerId, key.connection(), key.dimension()
      );
      session.stageSuspendedOperationDraft(ClientOperationDraftCodec.decode(validDraftTag(), null));
      try {
         for (int tick = 0; tick < 200; tick++) {
            ClientSessionSystem.instance().operations().onClientTick();
         }
         assertTrue(session.suspendedOperationDraft());
         assertTrue(session.restoreSuspendedOperationDraft(validDraftIdentity()));
         assertTrue(session.selectionSession().hasDraft());
      } finally {
         session.discardSuspendedOperationDraft();
         session.detachEnvironment();
      }
   }

   /** A draft with no parts, so decoding it needs no block registry. */
   private static CompoundTag validDraftTag() {
      return ClientOperationDraftCodec.encode(new ClientOperationDraft(
         validDraftIdentity(),
         new ClientOperationWorkspace.DraftState(List.of(), java.util.Set.of(), 0),
         new ClientSelectionSession.DraftState(
            OperationSelectionMode.CUBOID, List.of(new BlockPos(1, 2, 3)), 0, null, null
         )
      ));
   }

   private static OperationDraftIdentity validDraftIdentity() {
      return new OperationDraftIdentity(
         OperationSelectionMode.CUBOID,
         List.of(BlockPos.ZERO, new BlockPos(1, 1, 1)),
         0,
         BlockPos.ZERO,
         BlockPos.ZERO,
         0.0,
         BlockPos.ZERO,
         new BlockPos(1, 1, 1)
      );
   }
}
