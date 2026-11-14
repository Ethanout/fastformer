package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.ClientOperationDraftCodec;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.testkit.ClientScopes;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartialDeletionPersistenceTest {
   @TempDir Path directory;

   @org.junit.jupiter.api.AfterEach void clearLiveFixture() {
      var session = ClientSessionSystem.instance().currentScope();
      if (session != null) { session.detachEnvironment(); session.discardSuspendedOperationDraft(); }
   }

   @Test void settlementPromotesOnlyTheRemainderAndCanRepeat() throws Exception {
      var manager = ClientSessionSystem.instance();
      UUID player = UUID.randomUUID(), transfer = UUID.randomUUID();
      String connection = "partial-delete-" + player;
      manager.activateScope(player, connection, "minecraft:overworld");
      var key = new ClientScopeId(connection, "minecraft:overworld", player);
      Path file = directory.resolve("draft.nbt.gz");
      manager.forScope(key).drafts().useFile(file);
      var original = draft(transfer);
      OperationClipboardStore.save(file, ClientOperationDraftCodec.encode(original));
      manager.currentScope().stageSuspendedOperationDraft(original);

      assertTrue(manager.submissions().clearDraftCopies(UUID.randomUUID()).confirmed());
      assertEquals(original, ClientOperationDraftCodec.decode(OperationClipboardStore.loadStrict(file).orElseThrow(), null));
      assertTrue(manager.submissions().clearDraftCopies(transfer).confirmed());
      var expected = original.remainder().asDraft();
      assertEquals(expected, manager.currentScope().suspendedOperationDraftData());
      assertEquals(expected, ClientOperationDraftCodec.decode(OperationClipboardStore.loadStrict(file).orElseThrow(), null));
      assertTrue(manager.submissions().clearDraftCopies(transfer).confirmed());
      assertTrue(manager.currentScope().restoreSuspendedOperationDraft(null));
      assertEquals(expected.selection(), manager.currentScope().selectionSession().draftState());
   }

   @Test void disconnectRetainsSubmissionRemainder() {
      var session = ClientScopes.create(UUID.randomUUID());
      var draft = draft(UUID.randomUUID());
      session.selectionSession().restoreDraftState(draft.selection());
      session.setSubmissionRemainder(draft.remainder());
      assertEquals(draft, session.buildSubmittedDraft(null, OperationSubmissionOrigin.CLIENT_SELECTION, draft.submissionId()));
      session.suspendOperationDraft(null, OperationSubmissionOrigin.CLIENT_SELECTION, draft.submissionId());
      assertEquals(draft, session.suspendedOperationDraftData());
   }

   private static ClientOperationDraft draft(UUID transfer) {
      var empty = new ClientOperationWorkspace.DraftState(List.of(), Set.of(), 0);
      var first = new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID,
         List.of(BlockPos.ZERO), 0, BlockPos.ZERO, BlockPos.ZERO);
      var remaining = new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID,
         List.of(new BlockPos(8, 9, 10)), 0, new BlockPos(8, 9, 10), new BlockPos(8, 9, 10));
      return new ClientOperationDraft(ClientOperationDraft.CURRENT_VERSION, null, empty, first,
         OperationSubmissionOrigin.CLIENT_SELECTION, transfer, new ClientOperationDraft.Remainder(empty, remaining));
   }
}
