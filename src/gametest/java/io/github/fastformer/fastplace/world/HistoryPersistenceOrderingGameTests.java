package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HistoryPersistenceOrderingGameTests {
   private HistoryPersistenceOrderingGameTests() { }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void oldSnapshotCannotClearNewerFailure(GameTestHelper helper) {
      var server = helper.getLevel().getServer();
      UUID owner = UUID.randomUUID();
      var oldSnapshot = new CompletableFuture<Void>();
      var newerWrite = new CompletableFuture<Void>();
      WorldHistoryManager.trackPersistenceForTest(server, owner, true, oldSnapshot);
      WorldHistoryManager.trackPersistenceForTest(server, owner, false, newerWrite);
      newerWrite.completeExceptionally(new IOException("injected persistence failure"));
      oldSnapshot.complete(null);
      helper.runAfterDelay(1, () -> {
         helper.assertTrue(WorldHistoryManager.persistenceFailedForTest(owner),
            "old snapshot cleared a newer failure");
         WorldHistoryManager.trackPersistenceForTest(server, owner, false, CompletableFuture.completedFuture(null));
         helper.runAfterDelay(1, () -> {
            helper.assertTrue(WorldHistoryManager.persistenceFailedForTest(owner),
               "ordinary write cleared the failed snapshot state");
            WorldHistoryManager.trackPersistenceForTest(server, owner, true, CompletableFuture.completedFuture(null));
            helper.runAfterDelay(1, () -> {
               helper.assertTrue(!WorldHistoryManager.persistenceFailedForTest(owner),
                  "latest full snapshot did not clear the failure");
               helper.succeed();
            });
         });
      });
   }
}
