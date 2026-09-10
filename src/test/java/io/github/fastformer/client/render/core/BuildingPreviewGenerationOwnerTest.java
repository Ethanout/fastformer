package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.render.model.BuildingBlockResult;
import io.github.fastformer.client.render.model.BuildingPreviewKey;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BuildingPreviewGenerationOwnerTest {
   @Test
   void replacingTheKeyCancelsThePreviousGeneration() {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      CompletableFuture<BuildingBlockResult> first = new CompletableFuture<>();
      CompletableFuture<BuildingBlockResult> second = new CompletableFuture<>();

      owner.replace(key(1L), first);
      owner.replace(key(2L), second);

      assertTrue(first.isCancelled());
      assertFalse(second.isCancelled());
   }

   @Test
   void completedResultMustBelongToTheKeyThatStartedItsFuture() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      BuildingBlockResult stale = new BuildingBlockResult(key(1L), Set.of(BlockPos.ZERO));

      owner.replace(key(2L), CompletableFuture.completedFuture(stale));

      assertTrue(owner.takeCompleted().isEmpty());
      assertFalse(owner.completed());
   }

   @Test
   void changingKeyAfterOldCompletionKeepsTheNewFutureAuthoritative() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      CompletableFuture<BuildingBlockResult> old = CompletableFuture.completedFuture(
         new BuildingBlockResult(key(1L), Set.of(BlockPos.ZERO))
      );
      CompletableFuture<BuildingBlockResult> current = new CompletableFuture<>();

      owner.replace(key(1L), old);
      assertTrue(owner.completed());
      owner.replace(key(2L), current);

      assertFalse(owner.completed());
      current.complete(new BuildingBlockResult(key(2L), Set.of(new BlockPos(2, 0, 0))));
      assertEquals(key(2L), owner.takeCompleted().orElseThrow().key());
   }

   @Test
   void currentCompletedResultIsTakenOnce() throws Exception {
      BuildingPreviewGenerationOwner owner = new BuildingPreviewGenerationOwner();
      BuildingBlockResult current = new BuildingBlockResult(key(3L), Set.of(new BlockPos(1, 2, 3)));
      owner.replace(key(3L), CompletableFuture.completedFuture(current));

      assertTrue(owner.completed());
      assertEquals(current, owner.takeCompleted().orElseThrow());
      assertFalse(owner.completed());
      assertTrue(owner.takeCompleted().isEmpty());
   }

   private static BuildingPreviewKey key(long version) {
      return new BuildingPreviewKey(version, List.of(BlockPos.ZERO), false, false);
   }
}
