package io.github.fastformer.client.operation.clipboard;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationClipboardActionsTest {
   @Test
   void emptyCopyAndDuplicateReturnTheirOwnImmutableOutcomeWithoutOpeningStorage() {
      var workspace = new ClientOperationWorkspace();
      var changed = new AtomicInteger();
      var repository = new OperationClipboardRepository(() -> { throw new AssertionError("empty copy accessed storage"); });
      var actions = new OperationClipboardActions(workspace, repository, () -> false, () -> false, changed::incrementAndGet);

      var copy = actions.copy();
      var duplicate = actions.duplicate();
      assertEquals(ClipboardActionResult.EMPTY, copy);
      assertEquals(ClipboardActionResult.EMPTY, duplicate);
      assertFalse(copy.succeeded());
      assertEquals("fastformer.message.operation_copy_empty", copy.failureKey());
      assertEquals(0, changed.get());
      assertTrue(workspace.isEmpty());
   }

   @Test
   void submissionBlocksPasteAndDuplicateBeforeAnyWorldOrStorageAccess() {
      var actions = new OperationClipboardActions(new ClientOperationWorkspace(),
         new OperationClipboardRepository(() -> { throw new AssertionError("pending action accessed storage"); }),
         () -> true, () -> false, () -> fail("pending action changed workspace"));
      assertEquals(ClipboardActionResult.PENDING, actions.duplicate());
      assertEquals(ClipboardActionResult.PENDING, actions.paste(null));
   }

   @Test
   void activeEditBlocksDuplicateAndMissingWorldRejectsPasteWithoutMutation() {
      var actions = new OperationClipboardActions(new ClientOperationWorkspace(),
         new OperationClipboardRepository(() -> Path.of("unused")), () -> false, () -> true,
         () -> fail("rejected action changed workspace"));
      assertEquals(ClipboardActionResult.PENDING, actions.duplicate());
      assertEquals(ClipboardActionResult.INVALID_CLIPBOARD, actions.paste(null));
   }
}
