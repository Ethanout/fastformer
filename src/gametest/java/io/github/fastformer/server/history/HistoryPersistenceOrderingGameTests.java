package io.github.fastformer.server.history;

import io.github.fastformer.server.history.HistorySystem;

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
      HistoryTestAccess.trackPersistence(HistorySystem.of(helper.getLevel().getServer()), server, owner, true, oldSnapshot);
      HistoryTestAccess.trackPersistence(HistorySystem.of(helper.getLevel().getServer()), server, owner, false, newerWrite);
      newerWrite.completeExceptionally(new IOException("injected persistence failure"));
      oldSnapshot.complete(null);
      helper.runAfterDelay(1, () -> {
         helper.assertTrue(HistoryTestAccess.persistenceFailed(HistorySystem.of(helper.getLevel().getServer()), owner),
            "old snapshot cleared a newer failure");
         HistoryTestAccess.trackPersistence(HistorySystem.of(helper.getLevel().getServer()), server, owner, false, CompletableFuture.completedFuture(null));
         helper.runAfterDelay(1, () -> {
            helper.assertTrue(HistoryTestAccess.persistenceFailed(HistorySystem.of(helper.getLevel().getServer()), owner),
               "ordinary write cleared the failed snapshot state");
            HistoryTestAccess.trackPersistence(HistorySystem.of(helper.getLevel().getServer()), server, owner, true, CompletableFuture.completedFuture(null));
            helper.runAfterDelay(1, () -> {
               helper.assertTrue(!HistoryTestAccess.persistenceFailed(HistorySystem.of(helper.getLevel().getServer()), owner),
                  "latest full snapshot did not clear the failure");
               helper.succeed();
            });
         });
      });
   }
}
