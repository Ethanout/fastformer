package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.history.HistoryBatchStore;
import io.github.fastformer.fastplace.history.WorldHistoryBatchCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldHistoryPersistenceGameTests {
   private WorldHistoryPersistenceGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void newOperationRemovesRetiredRedoFileAfterIndexCommit(GameTestHelper helper) {
      UUID owner = UUID.randomUUID();
      UUID retired = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      var before = new ReversibleBlockSnapshot(pos, Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      var after = new ReversibleBlockSnapshot(pos, Blocks.GOLD_BLOCK.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      var batch = WorldChangeBatch.capturePairsByPos(helper.getLevel().dimension(), List.of(before),
         new HashMap<>(Map.of(pos, after))).orElseThrow();
      var server = helper.getLevel().getServer();
      var published = WorldHistoryPersistence.publishNewBatch(server, owner, batch.withOperationId(retired),
         List.of(retired), List.of())
         .thenCompose(ignored -> WorldHistoryPersistence.publishIndex(server, owner, List.of(), List.of(retired)))
         .thenCompose(ignored -> WorldHistoryPersistence.publishNewBatch(server, owner, batch.withOperationId(current),
            List.of(current), List.of()));
      var ownerRoot = server.getWorldPath(LevelResource.ROOT).resolve("fastformer-history").resolve(owner.toString());
      helper.succeedWhen(() -> {
         helper.assertTrue(published.isDone(), "history publication is still pending");
         published.join();
         helper.assertTrue(!java.nio.file.Files.exists(ownerRoot.resolve("batches").resolve(retired + ".dat")),
            "retired redo batch still occupies disk");
         helper.assertTrue(java.nio.file.Files.isRegularFile(ownerRoot.resolve("batches").resolve(current + ".dat")),
            "cleanup removed current undo batch");
      });
   }

   // The test asserts the real asynchronous publication barrier.  The
   // uncapped GameTest server can advance far faster than the disk worker.
   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void completedManagerBatchReachesDisk(GameTestHelper helper) {
      publishAndCheck(helper, false);
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void durableHistoryLoadsAsynchronouslyInIndexOrder(GameTestHelper helper) {
      UUID owner = UUID.randomUUID();
      UUID newest = UUID.randomUUID();
      UUID older = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(
         helper.getLevel().dimension(),
         List.of(new ReversibleBlockSnapshot(
            pos, Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null
         )),
         Map.of(pos, new ReversibleBlockSnapshot(
            pos, Blocks.GOLD_BLOCK.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null
         ))
      ).orElseThrow();
      var server = helper.getLevel().getServer();
      var publication = WorldHistoryPersistence.publishNewBatch(
         server, owner, batch.withOperationId(older), List.of(older), List.of()
      ).thenCompose(ignored -> WorldHistoryPersistence.publishNewBatch(
         server, owner, batch.withOperationId(newest), List.of(newest, older), List.of()
      ));
      var load = publication.thenCompose(ignored -> WorldHistoryPersistence.loadSnapshot(
         server, owner, 200, 256L * 1024L * 1024L
      ));
      var page = publication.thenCompose(ignored -> WorldHistoryPersistence.loadPage(
         server, owner, List.of(newest, older), 1, 256L * 1024L * 1024L
      ));

      helper.succeedWhen(() -> {
         helper.assertTrue(load.isDone(), "durable history load is still pending");
         helper.assertTrue(page.isDone(), "durable history page is still pending");
         var loaded = load.join();
         helper.assertTrue(
            loaded.undo().stream().map(WorldChangeBatch::operationId).toList().equals(List.of(newest, older)),
            "loaded undo order differs from the durable index"
         );
         helper.assertTrue(loaded.redo().isEmpty(), "loaded history gained a redo entry");
         helper.assertTrue(
            loaded.undoOrder().equals(List.of(newest, older)),
            "loaded history lost the durable undo order"
         );
         helper.assertTrue(loaded.redoOrder().isEmpty(), "loaded history gained a durable redo entry");
         helper.assertTrue(
            page.join().stream().map(WorldChangeBatch::operationId).toList().equals(List.of(newest)),
            "history page ignored its entry limit"
         );
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void activeUndoLoadsTheNextPageAfterConsumingItsCache(GameTestHelper helper) {
      activeTaskLoadsNextPage(helper, true);
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void activeRedoLoadsTheNextPageAfterConsumingItsCache(GameTestHelper helper) {
      activeTaskLoadsNextPage(helper, false);
   }

   private static void activeTaskLoadsNextPage(GameTestHelper helper, boolean undo) {
      var player = helper.makeMockServerPlayerInLevel();
      if (!FastPlaceSettings.load(player).enabled()) {
         FastPlaceSettings.load(player).toggleEnabled(player);
      }
      UUID owner = player.getUUID();
      UUID newest = UUID.randomUUID();
      UUID older = UUID.randomUUID();
      BlockPos pos = helper.absolutePos(BlockPos.ZERO);
      var stone = snapshot(pos, Blocks.STONE);
      var gold = snapshot(pos, Blocks.GOLD_BLOCK);
      var diamond = snapshot(pos, Blocks.DIAMOND_BLOCK);
      var olderBatch = batch(helper, older, stone, gold);
      var newestBatch = batch(helper, newest, gold, diamond);
      var server = helper.getLevel().getServer();
      var publication = WorldHistoryPersistence.publishNewBatch(
         server, owner, olderBatch, List.of(older), List.of()
      ).thenCompose(ignored -> WorldHistoryPersistence.publishNewBatch(
         server, owner, newestBatch, List.of(newest, older), List.of()
      )).thenCompose(ignored -> WorldHistoryPersistence.publishIndex(
         server, owner, undo ? List.of(newest, older) : List.of(), undo ? List.of() : List.of(older, newest)
      ));
      boolean[] started = {false};
      helper.succeedWhen(() -> {
         if (!started[0]) {
            helper.assertTrue(publication.isDone(), "history publication is pending");
            publication.join();
            helper.getLevel().setBlock(pos, (undo ? Blocks.DIAMOND_BLOCK : Blocks.STONE).defaultBlockState(), 3);
            WorldHistoryManager.installLoadedHistoryForTest(owner,
               new WorldHistoryPersistence.LoadedHistory(
                  undo ? List.of(newestBatch) : List.of(), undo ? List.of() : List.of(olderBatch),
                  undo ? List.of(newest, older) : List.of(), undo ? List.of() : List.of(older, newest)
               ), 200);
            helper.assertTrue(undo ? WorldHistoryManager.requestUndo(player, 2) : WorldHistoryManager.requestRedo(player, 2),
               "history request was refused");
            started[0] = true;
         }
         WorldHistoryManager.tickWorld(server);
         helper.assertTrue(!WorldHistoryManager.busy(owner), "multi-undo is pending");
         helper.assertTrue(helper.getLevel().getBlockState(pos).is(undo ? Blocks.STONE : Blocks.DIAMOND_BLOCK),
            "next page was not applied");
         helper.assertTrue(WorldHistoryManager.historyOrderForTest(owner, undo).isEmpty(), "source order is not empty");
         helper.assertTrue(WorldHistoryManager.historyOrderForTest(owner, !undo).equals(undo ? List.of(older, newest) : List.of(newest, older)),
            "target order differs from the applied sequence");
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void managerLoadsOlderHistoryPageBeforeMultiUndo(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      FastPlaceSettings initialSettings = FastPlaceSettings.load(player);
      if (!initialSettings.enabled()) {
         initialSettings.toggleEnabled(player);
      }
      helper.assertTrue(FastPlaceSettings.load(player).enabled(), "the test could not enable the operation permission");
      UUID owner = player.getUUID();
      UUID newest = UUID.randomUUID();
      UUID older = UUID.randomUUID();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
      ReversibleBlockSnapshot stone = snapshot(pos, Blocks.STONE);
      ReversibleBlockSnapshot gold = snapshot(pos, Blocks.GOLD_BLOCK);
      ReversibleBlockSnapshot diamond = snapshot(pos, Blocks.DIAMOND_BLOCK);
      WorldChangeBatch olderBatch = batch(helper, older, stone, gold);
      WorldChangeBatch newestBatch = batch(helper, newest, gold, diamond);
      var server = helper.getLevel().getServer();
      var publication = WorldHistoryPersistence.publishNewBatch(
         server, owner, olderBatch, List.of(older), List.of()
      ).thenCompose(ignored -> WorldHistoryPersistence.publishNewBatch(
         server, owner, newestBatch, List.of(newest, older), List.of()
      ));
      int[] phase = {0};
      boolean[] permissionRestored = {false};

      helper.succeedWhen(() -> {
         if (phase[0] == 0) {
            helper.assertTrue(publication.isDone(), "history publication is still pending");
            publication.join();
            helper.getLevel().setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            WorldHistoryManager.installLoadedHistoryForTest(
               player.getUUID(),
               new WorldHistoryPersistence.LoadedHistory(
                  List.of(), List.of(), List.of(newest, older), List.of()
               ),
               200
            );
            helper.assertTrue(
               WorldHistoryManager.requestUndo(player, 2),
               "manager did not start the older history page load"
            );
            FastPlaceSettings.load(player).toggleEnabled(player);
            helper.assertTrue(
               !FastPlaceSettings.load(player).enabled(),
               "the test could not disable the operation permission"
            );
            phase[0] = 1;
         }

         WorldHistoryManager.tickWorld(server);
         if (phase[0] == 1) {
            helper.assertTrue(!WorldHistoryManager.busy(owner), "older history page is still pending");
            helper.assertTrue(
               helper.getLevel().getBlockState(pos).is(Blocks.DIAMOND_BLOCK),
               "page completion bypassed the renewed operation permission check"
            );
            helper.assertTrue(
               WorldHistoryManager.historyOrderForTest(owner, true).equals(List.of(newest, older)),
               "denied page continuation changed the durable undo order"
            );
            // Restore the permission once. A retry must not toggle it again, or the
            // retry tests the permission check instead of the retried undo.
            if (!permissionRestored[0]) {
               FastPlaceSettings.load(player).toggleEnabled(player);
               helper.assertTrue(
                  FastPlaceSettings.load(player).enabled(),
                  "the test could not restore the operation permission"
               );
               permissionRestored[0] = true;
            }
            helper.assertTrue(
               WorldHistoryManager.requestUndo(player, 2),
               "manager did not accept the retried multi-undo"
            );
            phase[0] = 2;
            WorldHistoryManager.tickWorld(server);
         }
         helper.assertTrue(!WorldHistoryManager.busy(owner), "older history undo is still pending");
         helper.assertTrue(
            helper.getLevel().getBlockState(pos).is(Blocks.STONE),
            "multi-undo did not restore the oldest before-state"
         );
         helper.assertTrue(
            WorldHistoryManager.historyOrderForTest(owner, true).isEmpty(),
            "multi-undo retained a completed undo operation"
         );
         helper.assertTrue(
            WorldHistoryManager.historyOrderForTest(owner, false).equals(List.of(older, newest)),
            "multi-undo changed the durable redo order"
         );
      });
   }

   @GameTest(
      template = "fastformergametests.empty",
      timeoutTicks = 100000,
      batch = "history_commit_protocol"
   )
   public static void sealedJournalWaitsForFailedIndexAndRetries(GameTestHelper helper) {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      ReversibleBlockSnapshot before = snapshot(pos, Blocks.STONE);
      ReversibleBlockSnapshot after = snapshot(pos, Blocks.GOLD_BLOCK);
      WorldChangeBatch batch = batch(helper, operation, before, after);
      var server = helper.getLevel().getServer();
      PersistentRecoveryJournal journal = PersistentRecoveryJournal.begin(
         server, owner, helper.getLevel().dimension(), List.of(before), List.of(after), operation
      ).orElseThrow();
      helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
      java.nio.file.Path journalDirectory = journalDirectory(server, operation);
      java.nio.file.Path index = historyRoot(server).resolve(owner.toString()).resolve("index.dat");
      JournalPreparation first = WorldHistoryManager.pollPreparedOperation(
         new WorldTaskContext(server, owner), Optional.of(batch), journal
      );
      helper.assertTrue(first == JournalPreparation.PENDING, "batch publication did not enter pending state");
      int[] phase = {0};

      helper.succeedWhen(() -> {
         if (phase[0] == 0) {
            if (!java.nio.file.Files.exists(
               historyRoot(server).resolve(owner.toString()).resolve("batches").resolve(operation + ".dat")
            )) {
               helper.fail("waiting for history batch publication");
            }
            try {
               java.nio.file.Files.createDirectories(index);
               java.nio.file.Files.writeString(index.resolve("blocked"), "force index failure");
            } catch (java.io.IOException failure) {
               throw new AssertionError("cannot block history index", failure);
            }
            phase[0] = 1;
         }

         if (phase[0] == 1) {
            JournalPreparation state = WorldHistoryManager.pollPreparedOperation(
               new WorldTaskContext(server, owner), Optional.of(batch), journal
            );
            helper.assertTrue(
               state != JournalPreparation.FAILED,
               "index failure incorrectly rolled back committed world data"
            );
            helper.assertTrue(
               java.nio.file.Files.exists(journalDirectory),
               "sealed journal was removed before index recovery"
            );
            if (!WorldHistoryManager.durableIndexFailedForTest(owner)) {
               helper.fail("waiting for failed index publication");
            }
            try {
               java.nio.file.Files.delete(index.resolve("blocked"));
               java.nio.file.Files.delete(index);
            } catch (java.io.IOException failure) {
               throw new AssertionError("cannot unblock history index", failure);
            }
            phase[0] = 2;
         }

         if (phase[0] == 2) {
            JournalPreparation state = WorldHistoryManager.pollPreparedOperation(
               new WorldTaskContext(server, owner), Optional.of(batch), journal
            );
            helper.assertTrue(state != JournalPreparation.FAILED, "history retry failed");
            if (state != JournalPreparation.READY) {
               helper.fail("waiting for history index retry");
            }
            HistoryBatchStore reopened = historyStore(server);
            helper.assertTrue(
               reopened.loadIndex(owner).join().orElseThrow().undo().equals(List.of(operation)),
               "retried index omitted the operation"
            );
            PersistentRecoveryJournal.onLevelSaved(helper.getLevel());
            phase[0] = 3;
         }

         helper.assertTrue(
            !java.nio.file.Files.exists(journalDirectory),
            "published journal cleanup is pending"
         );
      });
   }

   @GameTest(
      template = "fastformergametests.empty",
      timeoutTicks = 100000,
      batch = "history_commit_protocol"
   )
   public static void sealedJournalRepairsMissingIndexBeforeCleanup(GameTestHelper helper) {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      ReversibleBlockSnapshot before = snapshot(pos, Blocks.STONE);
      ReversibleBlockSnapshot after = snapshot(pos, Blocks.GOLD_BLOCK);
      WorldChangeBatch batch = batch(helper, operation, before, after);
      var server = helper.getLevel().getServer();
      PersistentRecoveryJournal journal = PersistentRecoveryJournal.begin(
         server, owner, helper.getLevel().dimension(), List.of(before), List.of(after), operation
      ).orElseThrow();
      helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
      WorldHistoryPersistence.publishBatchOnly(server, owner, batch).join();
      helper.assertTrue(journal.sealFinalizedForHistory(), "journal did not seal");
      java.nio.file.Path journalDirectory = journalDirectory(server, operation);
      var repaired = WorldHistoryPersistence.reconcileCommittedJournal(server, journalDirectory);
      boolean[] cleanupStarted = {false};

      helper.succeedWhen(() -> {
         if (!cleanupStarted[0]) {
            helper.assertTrue(repaired.isDone(), "startup history reconciliation is pending");
            repaired.join();
            HistoryBatchStore reopened = historyStore(server);
            helper.assertTrue(
               reopened.loadIndex(owner).join().orElseThrow().undo().equals(List.of(operation)),
               "startup reconciliation did not publish the recovered operation"
            );
            journal.historyPublished();
            PersistentRecoveryJournal.onLevelSaved(helper.getLevel());
            cleanupStarted[0] = true;
         }
         helper.assertTrue(
            !java.nio.file.Files.exists(journalDirectory),
            "published journal cleanup is pending"
         );
      });
   }

   @GameTest(
      template = "fastformergametests.empty",
      timeoutTicks = 100000,
      batch = "history_shutdown_sealed"
   )
   public static void shutdownKeepsSealedJournalForStartupReconciliation(GameTestHelper helper) {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      ReversibleBlockSnapshot before = snapshot(pos, Blocks.STONE);
      ReversibleBlockSnapshot after = snapshot(pos, Blocks.GOLD_BLOCK);
      WorldChangeBatch batch = batch(helper, operation, before, after);
      var server = helper.getLevel().getServer();
      PersistentRecoveryJournal journal = PersistentRecoveryJournal.begin(
         server, owner, helper.getLevel().dimension(), List.of(before), List.of(after), operation
      ).orElseThrow();
      helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
      WorldHistoryPersistence.publishBatchOnly(server, owner, batch).join();
      helper.assertTrue(journal.sealFinalizedForHistory(), "journal did not seal");
      java.nio.file.Path journalDirectory = journalDirectory(server, operation);
      java.nio.file.Path batchFile = historyRoot(server).resolve(owner.toString())
         .resolve("batches").resolve(operation + ".dat");
      java.nio.file.Path index = historyRoot(server).resolve(owner.toString()).resolve("index.dat");
      try {
         java.nio.file.Files.createDirectories(index);
         java.nio.file.Files.writeString(index.resolve("blocked"), "force index failure");
      } catch (java.io.IOException failure) {
         throw new AssertionError("cannot block history index", failure);
      }

      boolean[] shutdownObserved = {false};
      boolean[] reconciled = {false};
      helper.succeedWhen(() -> {
         if (!shutdownObserved[0]) {
            WorldHistoryManager.awaitDiskWritesOnShutdown(server);
            WorldHistoryManager.clearServer();
            helper.assertTrue(java.nio.file.Files.exists(journalDirectory), "shutdown removed sealed journal");
            helper.assertTrue(java.nio.file.Files.exists(batchFile), "shutdown removed published batch");
            try {
               java.nio.file.Files.delete(index.resolve("blocked"));
               java.nio.file.Files.delete(index);
            } catch (java.io.IOException failure) {
               throw new AssertionError("cannot unblock history index", failure);
            }
            shutdownObserved[0] = true;
         }
         if (!reconciled[0]) {
            var repaired = WorldHistoryPersistence.reconcileCommittedJournal(server, journalDirectory);
            helper.assertTrue(repaired.join() == null, "startup reconciliation failed");
            HistoryBatchStore reopened = historyStore(server);
            helper.assertTrue(
               reopened.loadIndex(owner).join().orElseThrow().undo().equals(List.of(operation)),
               "startup reconciliation did not publish the recovered operation"
            );
            journal.historyPublished();
            PersistentRecoveryJournal.onLevelSaved(helper.getLevel());
            reconciled[0] = true;
         }
         helper.assertTrue(!java.nio.file.Files.exists(journalDirectory), "recovered journal cleanup is pending");
      });
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, net.minecraft.world.level.block.Block block) {
      return new ReversibleBlockSnapshot(pos, block.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
   }

   private static WorldChangeBatch batch(
      GameTestHelper helper,
      UUID operation,
      ReversibleBlockSnapshot before,
      ReversibleBlockSnapshot after
   ) {
      return WorldChangeBatch.capturePairsByPos(
         helper.getLevel().dimension(), List.of(before), Map.of(before.pos(), after)
      ).orElseThrow().withOperationId(operation);
   }

   private static java.nio.file.Path journalDirectory(net.minecraft.server.MinecraftServer server, UUID operation) {
      java.nio.file.Path root = server.getWorldPath(LevelResource.ROOT).resolve("fastformer-recovery");
      try (var entries = java.nio.file.Files.list(root)) {
         return entries.filter(java.nio.file.Files::isDirectory)
            .filter(path -> path.getFileName().toString().endsWith("-" + operation))
            .findFirst()
            .orElseThrow();
      } catch (java.io.IOException failure) {
         throw new AssertionError("cannot locate recovery journal", failure);
      }
   }

   private static java.nio.file.Path historyRoot(net.minecraft.server.MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).resolve("fastformer-history");
   }

   private static HistoryBatchStore historyStore(net.minecraft.server.MinecraftServer server) {
      return new HistoryBatchStore(historyRoot(server), Runnable::run, 1,
         256L * 1024 * 1024, 8L * 1024 * 1024 * 1024, 1024, 4, 1024);
   }

   // The headless server advances ticks much faster than the single I/O worker
   // can finish a failed publish and its retry.
   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void failedIndexPublicationRetriesFromMemory(GameTestHelper helper) {
      publishAndCheck(helper, true);
   }

   // The headless server runs uncapped ticks; leave time for real filesystem completion.
   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "history_shutdown")
   public static void shutdownResubmitsFailedHistoryBeforeClearingIt(GameTestHelper helper) {
      publishAndCheck(helper, true, true);
   }

   private static void publishAndCheck(GameTestHelper helper, boolean failFirstWrite) {
      publishAndCheck(helper, failFirstWrite, false);
   }

   private static void publishAndCheck(GameTestHelper helper, boolean failFirstWrite, boolean shutdownRetry) {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      ReversibleBlockSnapshot before = new ReversibleBlockSnapshot(pos,
         Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      ReversibleBlockSnapshot after = new ReversibleBlockSnapshot(pos,
         Blocks.GOLD_BLOCK.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(helper.getLevel().dimension(),
         List.of(before), new HashMap<>(Map.of(pos, after))).orElseThrow().withOperationId(operation);
      var root = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("fastformer-history");
      var indexPath = root.resolve(owner.toString()).resolve("index.dat");
      if (failFirstWrite) {
         try {
            java.nio.file.Files.createDirectories(indexPath);
            java.nio.file.Files.writeString(indexPath.resolve("blocked"), "force index publication to fail");
         } catch (java.io.IOException failure) {
            throw new AssertionError("cannot prepare history write failure", failure);
         }
      }
      var releaseIo = new java.util.concurrent.CompletableFuture<Void>();
      java.util.concurrent.CompletableFuture.runAsync(releaseIo::join, WorldHistoryPersistence.executor());
      java.util.concurrent.CompletableFuture<Void> shutdownBarrier;
      try {
         helper.assertTrue(WorldHistoryManager.commitPreparedOperation(
            new WorldTaskContext(helper.getLevel().getServer(), owner), Optional.of(batch), null),
            "completed history was not accepted");
         shutdownBarrier = WorldHistoryPersistence.pendingWrites(helper.getLevel().getServer());
         helper.assertTrue(!shutdownBarrier.isDone(), "shutdown barrier ignored queued history writes");
      } finally {
         releaseIo.complete(null);
      }
      boolean[] failureObserved = {false};
      var failedShutdownSave = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
      helper.succeedWhen(() -> {
         if (failFirstWrite && !failureObserved[0]) {
            helper.assertTrue(WorldHistoryManager.persistenceFailedForTest(owner), "waiting for index write failure");
            helper.assertTrue(WorldHistoryManager.undoSizeForTest(owner) == 1, "failed save lost in-memory history");
            if (shutdownRetry && failedShutdownSave.get() == null) {
               failedShutdownSave.set(WorldHistoryManager.saveDirtyHistoriesOnShutdown(helper.getLevel().getServer()));
            }
            if (shutdownRetry) {
               helper.assertTrue(failedShutdownSave.get().isDone(), "failed shutdown save is pending");
               helper.assertTrue(failedShutdownSave.get().isCompletedExceptionally(),
                  "failed shutdown save hid its filesystem error");
            }
            try {
               java.nio.file.Files.delete(indexPath.resolve("blocked"));
               java.nio.file.Files.delete(indexPath);
            } catch (java.io.IOException failure) {
               throw new AssertionError("cannot release history write failure", failure);
            }
            failureObserved[0] = true;
            if (shutdownRetry) {
               WorldHistoryManager.awaitDiskWritesOnShutdown(helper.getLevel().getServer());
               WorldHistoryManager.clearServer();
               helper.assertTrue(WorldHistoryManager.undoSizeForTest(owner) == 0,
                  "shutdown did not release in-memory history");
            }
         }
         HistoryBatchStore reopened = new HistoryBatchStore(root, Runnable::run, 1,
            256L * 1024 * 1024, 8L * 1024 * 1024 * 1024, 1024, 4, 1024);
         var order = reopened.loadIndex(owner).join();
         helper.assertTrue(order.isPresent(), "background history publication is still pending");
         helper.assertTrue(shutdownBarrier.isDone(), "shutdown barrier is still waiting for the publication chain");
         helper.assertTrue(order.orElseThrow().undo().equals(List.of(operation)), "saved undo order differs");
         helper.assertTrue(order.orElseThrow().redo().isEmpty(), "new operation retained redo history");
         try {
            var restored = WorldHistoryBatchCodec.decode(helper.getLevel().registryAccess(),
               reopened.loadBatch(owner, operation).join().orElseThrow(), 1024 * 1024);
            helper.assertTrue(restored.operationId().equals(operation), "saved operation ID differs");
            helper.assertTrue(restored.targetSnapshots(true).getFirst().sameContents(before), "saved before state differs");
            helper.assertTrue(restored.sourceSnapshots(true).getFirst().sameContents(after), "saved after state differs");
         } catch (java.io.IOException failure) {
            throw new AssertionError("saved history cannot be decoded", failure);
         }
      });
   }
}
