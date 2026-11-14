package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.session.draft.ClientDraftLoadState;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.ClientOperationDraftCodec;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.common.selection.OperationSelectionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientScopeReclamationTest {
   @TempDir Path directory;

   @Test
   void loadedDraftCanBeEvictedAndLoadedIntoANewScope() throws Exception {
      var manager = new ClientSessionSystem();
      UUID player = UUID.randomUUID();
      var key = new ClientScopeId(
         "reclaim-loaded", "minecraft:overworld", player);
      Path file = directory.resolve("loaded.nbt.gz");
      OperationClipboardStore.save(file, ClientOperationDraftCodec.encode(draft()));
      var original = manager.activateScope(player, key.connection(), key.dimension());
      original.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(original, null));

      manager.activateScope(player, key.connection(), "minecraft:the_nether");
      var replacement = manager.activateScope(player, key.connection(), key.dimension());

      assertNotSame(original, replacement);
      replacement.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(replacement, null));
      assertEquals(draft().selection(), replacement.suspendedOperationDraftData().selection());
   }

   @Test
   void saveCompletionReclaimsAnInactiveScopeWithoutLosingItsDraft() throws Exception {
      var manager = new ClientSessionSystem();
      UUID player = UUID.randomUUID();
      var key = new ClientScopeId(
         "reclaim-saved", "minecraft:overworld", player);
      Path file = directory.resolve("saved.nbt.gz");
      manager.forScope(key).drafts().useFile(file);
      var original = manager.activateScope(player, key.connection(), key.dimension());
      original.selectionSession().addDraftPoint(new BlockPos(1, 2, 3));
      manager.suspendCurrentDraft(null, OperationSubmissionOrigin.CLIENT_SELECTION);
      manager.activateScope(player, key.connection(), "minecraft:the_nether");

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      do {
         manager.drainStorageResults();
         Thread.sleep(5);
      } while (manager.forScope(key) == original
         && System.nanoTime() < deadline);

      var replacement = manager.activateScope(player, key.connection(), key.dimension());
      assertNotSame(original, replacement);
      assertTrue(OperationClipboardStore.loadStrict(file).isPresent());
      replacement.drafts().useFile(file);
      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(replacement, null));
      assertEquals(List.of(new BlockPos(1, 2, 3)),
         replacement.suspendedOperationDraftData().selection().points());
   }

   @Test
   void replacingALoadedDraftWithUnsavedWorkPreventsEviction() throws Exception {
      var manager = new ClientSessionSystem();
      UUID player = UUID.randomUUID();
      var key = new ClientScopeId(
         "reclaim-replaced", "minecraft:overworld", player);
      Path file = directory.resolve("replaced.nbt.gz");
      OperationClipboardStore.save(file, ClientOperationDraftCodec.encode(draft()));
      var original = manager.activateScope(player, key.connection(), key.dimension());
      original.drafts().useFile(file);
      manager.attemptDraftLoad(original, null);
      original.discardSuspendedOperationDraft();
      original.selectionSession().addDraftPoint(new BlockPos(4, 5, 6));
      original.suspendOperationDraft(null, OperationSubmissionOrigin.CLIENT_SELECTION);

      manager.activateScope(player, key.connection(), "minecraft:the_nether");
      manager.drainStorageResults();

      assertSame(original, manager.activateScope(player, key.connection(), key.dimension()));
      assertEquals(List.of(new BlockPos(4, 5, 6)),
         original.suspendedOperationDraftData().selection().points());
   }

   private static ClientOperationDraft draft() {
      return new ClientOperationDraft(null,
         new ClientOperationWorkspace.DraftState(List.of(), Set.of(), 0),
         new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID,
            List.of(new BlockPos(1, 2, 3)), 0, null, null),
         OperationSubmissionOrigin.CLIENT_SELECTION);
   }
}
