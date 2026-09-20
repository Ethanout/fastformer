package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
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
   void failedReadKeepsTheFileAndBlocksAnotherAttempt() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "load-a");
      Path file = this.directory.resolve("transient.nbt.gz");
      Files.write(file, new byte[]{1, 2, 3, 4});

      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.attemptDraftLoad(key, session, null, file));
      assertEquals(4L, Files.size(file));
      assertFalse(session.suspendedOperationDraft());

      // A readable file appears. The recorded failure must still block the read, so no
      // repeated attempt and no repeated message can happen.
      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION));
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.attemptDraftLoad(key, session, null, file));
      assertFalse(session.suspendedOperationDraft());
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.draftLoadState(key));
   }

   @Test
   void versionMismatchHasItsOwnStateAndKeepsTheFile() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "load-b");
      Path file = this.directory.resolve("future.nbt.gz");
      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION + 1));
      long size = Files.size(file);

      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.attemptDraftLoad(key, session, null, file));
      assertTrue(Files.exists(file));
      assertEquals(size, Files.size(file));
      assertFalse(session.suspendedOperationDraft());
      assertTrue(manager.draftLoadState(key).mayRetry());
   }

   @Test
   void unreadableContentKeepsTheFileAndReportsCorruption() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "load-c");
      Path file = this.directory.resolve("broken.nbt.gz");
      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION));
      long size = Files.size(file);

      assertEquals(ClientDraftLoadState.CORRUPT, manager.attemptDraftLoad(key, session, null, file));
      assertTrue(Files.exists(file));
      assertEquals(size, Files.size(file));
      assertFalse(session.suspendedOperationDraft());
   }

   @Test
   void aMissingFileCompletesTheReadWithoutADraft() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "load-d");
      Path file = this.directory.resolve("absent.nbt.gz");

      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, file));
      assertFalse(session.suspendedOperationDraft());
      assertFalse(manager.draftLoadState(key).mayRetry());
      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, file));
   }

   @Test
   void anUnchangedScopeNeverRetriesAFailedRead() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID playerId = session.playerId();
      Path file = this.directory.resolve("repeated.nbt.gz");
      Files.write(file, new byte[]{7, 7});

      manager.activateScope(playerId, "load-e", "minecraft:overworld");
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(keyFor(session, "load-e"), session, null, file)
      );

      for (int tick = 0; tick < 40; tick++) {
         manager.activateScope(playerId, "load-e", "minecraft:overworld");
      }

      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.currentDraftLoadState());
      assertFalse(session.suspendedOperationDraft());
   }

   @Test
   void reEnteringAnEnvironmentRetriesOnlyATransientFailure() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID playerId = session.playerId();
      ClientSessionManager.SessionKey key = keyFor(session, "load-f");
      Path file = this.directory.resolve("boundary.nbt.gz");

      manager.activateScope(playerId, "load-f", "minecraft:overworld");
      Files.write(file, new byte[]{5, 5});
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.attemptDraftLoad(key, session, null, file));

      manager.activateScope(playerId, "load-f", "minecraft:the_nether");
      manager.activateScope(playerId, "load-f", "minecraft:overworld");

      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.draftLoadState(key));

      OperationClipboardStore.save(file, draftTag(ClientOperationDraft.CURRENT_VERSION + 1));
      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.attemptDraftLoad(key, session, null, file));

      manager.activateScope(playerId, "load-f", "minecraft:the_nether");
      manager.activateScope(playerId, "load-f", "minecraft:overworld");

      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.draftLoadState(key));
   }

   @Test
   void explicitRetryClearsOneFailedRead() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID playerId = session.playerId();
      Path file = this.directory.resolve("retry.nbt.gz");

      manager.activateScope(playerId, "load-g", "minecraft:overworld");
      Files.write(file, new byte[]{3, 3});
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(keyFor(session, "load-g"), session, null, file)
      );

      assertTrue(manager.retryCurrentDraftLoad());
      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.currentDraftLoadState());
      assertFalse(manager.retryCurrentDraftLoad());

      Files.delete(file);
      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(keyFor(session, "load-g"), session, null, file)
      );
   }

   @Test
   void explicitRetryReadsAValidDraftAndStagesIt() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID playerId = session.playerId();
      Path file = this.directory.resolve("retry-valid.nbt.gz");

      manager.activateScope(playerId, "load-h", "minecraft:overworld");
      Files.write(file, new byte[]{3, 3});
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(keyFor(session, "load-h"), session, null, file)
      );
      assertTrue(Files.exists(file));

      // A readable draft now replaces the broken file. The recorded failure still blocks the
      // read, so the failure path stays stable until an explicit boundary arrives.
      OperationClipboardStore.save(file, validDraftTag());
      assertEquals(
         ClientDraftLoadState.RETRYABLE_FAILURE,
         manager.attemptDraftLoad(keyFor(session, "load-h"), session, null, file)
      );
      assertFalse(session.suspendedOperationDraft());

      // The explicit boundary clears the failure, and the next read stages the draft.
      assertTrue(manager.retryCurrentDraftLoad());
      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(keyFor(session, "load-h"), session, null, file)
      );
      assertTrue(session.suspendedOperationDraft());
      assertTrue(session.restoreSuspendedOperationDraft(validDraftIdentity()));
      assertFalse(session.suspendedOperationDraft());
   }

   private static ClientSessionManager.SessionKey keyFor(ClientPlayerSession session, String connection) {
      return new ClientSessionManager.SessionKey(connection, "minecraft:overworld", session.playerId());
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
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "guard-a");
      Path file = this.directory.resolve("applied.nbt.gz");
      UUID transferId = UUID.randomUUID();
      OperationClipboardStore.save(file, draftTagWithSubmission(transferId));

      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(new OperationSubmissionReceipt(
         transferId, OperationSubmissionOrigin.LOCAL_ONLY, null,
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
         io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.APPLIED, 1L
      ));

      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(key, session, null, file, receipts)
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
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "guard-b");
      Path file = this.directory.resolve("pending.nbt.gz");
      UUID transferId = UUID.randomUUID();
      OperationClipboardStore.save(file, draftTagWithSubmission(transferId));

      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(new OperationSubmissionReceipt(
         transferId, OperationSubmissionOrigin.LOCAL_ONLY, null,
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
         io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.IN_PROGRESS, 1L
      ));

      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(key, session, null, file, receipts)
      );

      // The result is still open, so the draft must stay available to the player.
      assertTrue(session.suspendedOperationDraft());
   }

   /** A draft from an older format names no submission, so no receipt can forbid it. */
   @Test
   void aDraftThatNamesNoSubmissionIsStillStaged() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "guard-c");
      Path file = this.directory.resolve("unnamed.nbt.gz");
      OperationClipboardStore.save(file, validDraftTag());

      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(new OperationSubmissionReceipt(
         UUID.randomUUID(), OperationSubmissionOrigin.LOCAL_ONLY, null,
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
         io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.APPLIED, 1L
      ));

      assertEquals(
         ClientDraftLoadState.READY,
         manager.attemptDraftLoad(key, session, null, file, receipts)
      );

      assertTrue(session.suspendedOperationDraft());
   }

   /** A draft file that names a submission, for the load guard. */
   private static CompoundTag draftTagWithSubmission(UUID transferId) {
      CompoundTag tag = validDraftTag();
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
      io.github.fastformer.client.operation.controller.ClientOperationController.onDisconnected();
      ClientPlayerSession session = ClientSessionManager.instance().activateScope(
         UUID.randomUUID(), "reconnect-timeout", "minecraft:overworld"
      );
      session.stageSuspendedOperationDraft(ClientOperationDraftCodec.decode(validDraftTag(), null));
      try {
         for (int tick = 0; tick < 200; tick++) {
            io.github.fastformer.client.operation.controller.ClientOperationController.onClientTick();
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
