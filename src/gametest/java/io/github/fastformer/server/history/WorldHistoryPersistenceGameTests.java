package io.github.fastformer.server.history;

import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.common.persistence.MinecraftDataVersion;
import io.github.fastformer.core.persist.Envelope;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.HistoryEntryFormat;
import io.github.fastformer.server.history.entry.EntryCodecRegistry;

import io.github.fastformer.server.history.store.HistoryBatchStore;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayDeque;
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
   private static final HistoryEntryFormat<WorldChangeBatch> ENTRY_FORMAT =
      new HistoryEntryFormat<>(EntryCodecRegistry.standard(), WorldChangeBatch.class);
   private WorldHistoryPersistenceGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = "history_capture_failure")
   public static void failedRecordDoesNotClaimOrRetainTheTransaction(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
      level.setBlock(position, Blocks.STONE.defaultBlockState(), 2);
      var before = new ArrayDeque<>(List.of(ReversibleBlockSnapshot.capture(level, position).orElseThrow()));
      level.setBlock(position, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var lease = io.github.fastformer.server.recovery.RecoverySystem.of(level.getServer()).writeLeases().acquire(level.dimension(), player.getUUID());
      helper.assertTrue(lease != null, "test transaction could not acquire its lease");
      try {
         helper.assertTrue(!HistorySystem.of(player).record(player, level, before, Map.of()),
            "incomplete capture falsely reported a recorded history");
         helper.assertTrue(!HistorySystem.of(player).busy(player.getUUID()),
            "failed capture retained a memory-only pending record");
         helper.assertTrue(io.github.fastformer.server.recovery.RecoverySystem.of(level.getServer()).writeLeases().heldBy(level.dimension(), player.getUUID()),
            "failed capture released its caller's lease");
         helper.assertTrue(before.size() == 1 && level.getBlockState(position).is(Blocks.GOLD_BLOCK),
            "record failure changed the caller's snapshots or world");
         helper.assertTrue(before.getFirst().restore(level, 2), "caller could not restore the failed transaction");
         helper.assertTrue(level.getBlockState(position).is(Blocks.STONE), "caller restoration lost its before state");
         helper.succeed();
      } finally {
         lease.release();
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000)
   public static void newOperationRemovesRetiredRedoFileAfterIndexCommit(GameTestHelper helper) {
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
      UUID owner = UUID.randomUUID();
      UUID retired = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      var before = new ReversibleBlockSnapshot(pos, Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      var after = new ReversibleBlockSnapshot(pos, Blocks.GOLD_BLOCK.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      var batch = WorldChangeBatch.capturePairsByPos(helper.getLevel().dimension(), List.of(before),
         new HashMap<>(Map.of(pos, after))).orElseThrow();
      var server = helper.getLevel().getServer();
      var published = repository.publishNewBatch(owner, batch.withOperationId(retired),
         List.of(retired), List.of())
         .thenCompose(ignored -> repository.publishIndex(owner, List.of(), List.of(retired)))
         .thenCompose(ignored -> repository.publishNewBatch(owner, batch.withOperationId(current),
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
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
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
      var publication = repository.publishNewBatch(owner, batch.withOperationId(older), List.of(older), List.of()
      ).thenCompose(ignored -> repository.publishNewBatch(owner, batch.withOperationId(newest), List.of(newest, older), List.of()
      ));
      var load = publication.thenCompose(ignored -> repository.loadSnapshot(owner, 200, 256L * 1024L * 1024L
      ));
      var page = publication.thenCompose(ignored -> repository.loadPage(owner, List.of(newest, older), 1, 256L * 1024L * 1024L
      ));

      helper.succeedWhen(() -> {
         helper.assertTrue(load.isDone(), "durable history load is still pending");
         helper.assertTrue(page.isDone(), "durable history page is still pending");
         var loaded = load.join();
         helper.assertTrue(
            loaded.undo().stream().map(HistoryEntry::operationId).toList().equals(List.of(newest, older)),
            "loaded undo order differs from the durable index"
         );
         helper.assertTrue(loaded.redo().isEmpty(), "loaded history gained a redo entry");
         helper.assertTrue(
            loaded.undoOrder().equals(List.of(newest, older)),
            "loaded history lost the durable undo order"
         );
         helper.assertTrue(loaded.redoOrder().isEmpty(), "loaded history gained a durable redo entry");
         helper.assertTrue(
            page.join().stream().map(HistoryEntry::operationId).toList().equals(List.of(newest)),
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
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
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
      var publication = repository.publishNewBatch(owner, olderBatch, List.of(older), List.of()
      ).thenCompose(ignored -> repository.publishNewBatch(owner, newestBatch, List.of(newest, older), List.of()
      )).thenCompose(ignored -> repository.publishIndex(owner, undo ? List.of(newest, older) : List.of(), undo ? List.of() : List.of(older, newest)
      ));
      boolean[] started = {false};
      helper.succeedWhen(() -> {
         if (!started[0]) {
            helper.assertTrue(publication.isDone(), "history publication is pending");
            publication.join();
            helper.getLevel().setBlock(pos, (undo ? Blocks.DIAMOND_BLOCK : Blocks.STONE).defaultBlockState(), 3);
            HistoryTestAccess.installLoadedHistory(HistorySystem.of(helper.getLevel().getServer()), owner,
               new HistoryRepository.Snapshot<HistoryEntry>(
                  undo ? List.of(newestBatch) : List.of(), undo ? List.of() : List.of(olderBatch),
                  undo ? List.of(newest, older) : List.of(), undo ? List.of() : List.of(older, newest)
               ), 200);
            helper.assertTrue(undo ? HistorySystem.of(player).requestUndo(player, 2) : HistorySystem.of(player).requestRedo(player, 2),
               "history request was refused");
            started[0] = true;
         }
         HistorySystem.of(server).tickWorld(server);
         helper.assertTrue(!HistorySystem.of(helper.getLevel().getServer()).busy(owner), "multi-undo is pending");
         helper.assertTrue(helper.getLevel().getBlockState(pos).is(undo ? Blocks.STONE : Blocks.DIAMOND_BLOCK),
            "next page was not applied: block=" + helper.getLevel().getBlockState(pos)
               + ", source=" + HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, undo)
               + ", target=" + HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, !undo)
               + ", writesAllowed=" + RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed());
         helper.assertTrue(HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, undo).isEmpty(), "source order is not empty");
         helper.assertTrue(HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, !undo).equals(undo ? List.of(older, newest) : List.of(newest, older)),
            "target order differs from the applied sequence");
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "history_page_permission")
   public static void managerLoadsOlderHistoryPageBeforeMultiUndo(GameTestHelper helper) {
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
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
      var publication = repository.publishNewBatch(owner, olderBatch, List.of(older), List.of()
      ).thenCompose(ignored -> repository.publishNewBatch(owner, newestBatch, List.of(newest, older), List.of()
      ));
      int[] phase = {0};
      boolean[] permissionRestored = {false};

      helper.succeedWhen(() -> {
         if (phase[0] == 0) {
            helper.assertTrue(publication.isDone(), "history publication is still pending");
            publication.join();
            helper.getLevel().setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            HistoryTestAccess.installLoadedHistory(HistorySystem.of(helper.getLevel().getServer()),
               player.getUUID(),
               new HistoryRepository.Snapshot<HistoryEntry>(
                  List.of(), List.of(), List.of(newest, older), List.of()
               ),
               200
            );
            helper.assertTrue(
               HistorySystem.of(player).requestUndo(player, 2),
               "manager did not start the older history page load"
            );
            FastPlaceSettings.load(player).toggleEnabled(player);
            helper.assertTrue(
               !FastPlaceSettings.load(player).enabled(),
               "the test could not disable the operation permission"
            );
            phase[0] = 1;
         }

         HistorySystem.of(server).tickWorld(server);
         if (phase[0] == 1) {
            helper.assertTrue(!HistorySystem.of(helper.getLevel().getServer()).busy(owner), "older history page is still pending");
            helper.assertTrue(
               helper.getLevel().getBlockState(pos).is(Blocks.DIAMOND_BLOCK),
               "page completion bypassed the renewed operation permission check"
            );
            helper.assertTrue(
               HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, true).equals(List.of(newest, older)),
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
               HistorySystem.of(player).requestUndo(player, 2),
               "manager did not accept the retried multi-undo"
            );
            phase[0] = 2;
            HistorySystem.of(server).tickWorld(server);
         }
         helper.assertTrue(!HistorySystem.of(helper.getLevel().getServer()).busy(owner), "older history undo is still pending");
         helper.assertTrue(
            helper.getLevel().getBlockState(pos).is(Blocks.STONE),
            "multi-undo did not restore the oldest before-state: block=" + helper.getLevel().getBlockState(pos)
               + ", undo=" + HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, true)
               + ", redo=" + HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, false)
               + ", writesAllowed=" + RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed()
         );
         helper.assertTrue(
            HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, true).isEmpty(),
            "multi-undo retained a completed undo operation"
         );
         helper.assertTrue(
            HistoryTestAccess.historyOrder(HistorySystem.of(helper.getLevel().getServer()), owner, false).equals(List.of(older, newest)),
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
      PersistentRecoveryJournal journal = RecoverySystem.of(server).begin(owner, helper.getLevel().dimension(), List.of(before), List.of(after), operation
      ).orElseThrow();
      helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
      java.nio.file.Path journalDirectory = journalDirectory(server, operation);
      java.nio.file.Path index = historyRoot(server).resolve(owner.toString()).resolve("index.dat");
      JournalPreparation first = HistorySystem.of((new WorldTaskContext(server, owner)).server()).pollPreparedOperation(
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
            JournalPreparation state = HistorySystem.of((new WorldTaskContext(server, owner)).server()).pollPreparedOperation(
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
            if (!HistoryTestAccess.durableIndexFailed(HistorySystem.of(helper.getLevel().getServer()), owner)) {
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
            JournalPreparation state = HistorySystem.of((new WorldTaskContext(server, owner)).server()).pollPreparedOperation(
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
            RecoverySystem.of(helper.getLevel().getServer()).onLevelSaved(helper.getLevel());
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
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      ReversibleBlockSnapshot before = snapshot(pos, Blocks.STONE);
      ReversibleBlockSnapshot after = snapshot(pos, Blocks.GOLD_BLOCK);
      WorldChangeBatch batch = batch(helper, operation, before, after);
      var server = helper.getLevel().getServer();
      PersistentRecoveryJournal journal = RecoverySystem.of(server).begin(owner, helper.getLevel().dimension(), List.of(before), List.of(after), operation
      ).orElseThrow();
      helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
      repository.publishBatchOnly(owner, batch).join();
      helper.assertTrue(journal.sealFinalizedForHistory(), "journal did not seal");
      java.nio.file.Path journalDirectory = journalDirectory(server, operation);
      var repaired = repository.reconcileCommittedJournal(owner, operation);
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
            RecoverySystem.of(helper.getLevel().getServer()).onLevelSaved(helper.getLevel());
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
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      ReversibleBlockSnapshot before = snapshot(pos, Blocks.STONE);
      ReversibleBlockSnapshot after = snapshot(pos, Blocks.GOLD_BLOCK);
      WorldChangeBatch batch = batch(helper, operation, before, after);
      var server = helper.getLevel().getServer();
      PersistentRecoveryJournal journal = RecoverySystem.of(server).begin(owner, helper.getLevel().dimension(), List.of(before), List.of(after), operation
      ).orElseThrow();
      helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
      repository.publishBatchOnly(owner, batch).join();
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
            HistorySystem.of(server).awaitDiskWritesOnShutdown(server);
            HistorySystem.of(helper.getLevel().getServer()).clear();
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
            var repaired = repository.reconcileCommittedJournal(owner, operation);
            helper.assertTrue(repaired.join() == null, "startup reconciliation failed");
            HistoryBatchStore reopened = historyStore(server);
            helper.assertTrue(
               reopened.loadIndex(owner).join().orElseThrow().undo().equals(List.of(operation)),
               "startup reconciliation did not publish the recovered operation"
            );
            journal.historyPublished();
            RecoverySystem.of(helper.getLevel().getServer()).onLevelSaved(helper.getLevel());
            reconciled[0] = true;
         }
         helper.assertTrue(!java.nio.file.Files.exists(journalDirectory), "recovered journal cleanup is pending");
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "history_retired_journal")
   public static void retiredJournalKeepsItsBatchWithoutReinsertingHistory(GameTestHelper helper) {
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var before = snapshot(pos, Blocks.STONE);
      var after = snapshot(pos, Blocks.GOLD_BLOCK);
      var batch = batch(helper, operation, before, after);
      var journal = RecoverySystem.of(server).begin(owner, helper.getLevel().dimension(),
         List.of(before), List.of(after), operation).orElseThrow();
      helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
      repository.publishSnapshot(owner, List.of(batch), List.of()).join();
      helper.assertTrue(journal.sealFinalizedForHistory(), "journal did not seal");
      journal.historyPublished();
      var directory = journalDirectory(server, operation);
      repository.publishSnapshot(owner, List.of(), List.of()).join();
      var reopened = historyStore(server);
      helper.assertTrue(reopened.verifyBatch(owner, operation).join(), "retirement removed a recovery-referenced batch");
      helper.assertTrue(reopened.retiredBatches(owner).join().contains(operation), "retirement intent disappeared");
      repository.reconcileCommittedJournal(owner, operation).join();
      helper.assertTrue(reopened.loadIndex(owner).join().orElseThrow().undo().isEmpty(), "recovery revived intentionally removed history");
      RecoverySystem.of(helper.getLevel().getServer()).onLevelSaved(helper.getLevel());
      helper.succeedWhen(() -> {
         helper.assertTrue(!java.nio.file.Files.exists(directory), "journal cleanup is pending");
         reopened.cleanupUnreferencedBatches(owner, java.util.Set.of()).join();
         helper.assertTrue(!reopened.verifyBatch(owner, operation).join(), "saved journal still retained its retired batch");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "history_missing_journal_batch")
   public static void missingBatchWithoutRetirementStillRejectsReconciliation(GameTestHelper helper) {
      HistoryRepository<HistoryEntry> repository = HistorySystem.of(helper.getLevel().getServer()).repository();
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      boolean rejected = false;
      try {
         repository.reconcileCommittedJournal(owner, operation).join();
      } catch (java.util.concurrent.CompletionException failure) {
         rejected = failure.getCause() instanceof java.io.IOException;
      }
      helper.assertTrue(rejected, "missing batch without durable retirement intent was accepted");
      helper.succeed();
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
      return new HistoryBatchStore(historyRoot(server), Runnable::run, new Envelope.Format(0x46464832, 1, MinecraftDataVersion.current()),
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
      java.util.concurrent.CompletableFuture.runAsync(releaseIo::join, io.github.fastformer.server.lifecycle.ServerBackgroundTasks.serial(helper.getLevel().getServer(), "history-io", 512));
      java.util.concurrent.CompletableFuture<Void> shutdownBarrier;
      try {
         helper.assertTrue(HistorySystem.of((new WorldTaskContext(helper.getLevel().getServer(), owner)).server()).commitPreparedOperation(
            new WorldTaskContext(helper.getLevel().getServer(), owner), Optional.of(batch), null),
            "completed history was not accepted");
         shutdownBarrier = HistorySystem.of(helper.getLevel().getServer()).repository().pendingWrites();
         helper.assertTrue(!shutdownBarrier.isDone(), "shutdown barrier ignored queued history writes");
      } finally {
         releaseIo.complete(null);
      }
      boolean[] failureObserved = {false};
      var failedShutdownSave = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
      helper.succeedWhen(() -> {
         if (failFirstWrite && !failureObserved[0]) {
            helper.assertTrue(HistoryTestAccess.persistenceFailed(HistorySystem.of(helper.getLevel().getServer()), owner), "waiting for index write failure");
            helper.assertTrue(HistoryTestAccess.undoSize(HistorySystem.of(helper.getLevel().getServer()), owner) == 1, "failed save lost in-memory history");
            if (shutdownRetry && failedShutdownSave.get() == null) {
               failedShutdownSave.set(HistorySystem.of(helper.getLevel().getServer()).saveDirtyHistoriesOnShutdown(helper.getLevel().getServer()));
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
               HistorySystem.of(helper.getLevel().getServer()).awaitDiskWritesOnShutdown(helper.getLevel().getServer());
               HistorySystem.of(helper.getLevel().getServer()).clear();
               helper.assertTrue(HistoryTestAccess.undoSize(HistorySystem.of(helper.getLevel().getServer()), owner) == 0,
                  "shutdown did not release in-memory history");
            }
         }
         HistoryBatchStore reopened = new HistoryBatchStore(root, Runnable::run, new Envelope.Format(0x46464832, 1, MinecraftDataVersion.current()),
            256L * 1024 * 1024, 8L * 1024 * 1024 * 1024, 1024, 4, 1024);
         var order = reopened.loadIndex(owner).join();
         helper.assertTrue(order.isPresent(), "background history publication is still pending");
         helper.assertTrue(shutdownBarrier.isDone(), "shutdown barrier is still waiting for the publication chain");
         helper.assertTrue(order.orElseThrow().undo().equals(List.of(operation)), "saved undo order differs");
         helper.assertTrue(order.orElseThrow().redo().isEmpty(), "new operation retained redo history");
         try {
            var restored = ENTRY_FORMAT.decode(helper.getLevel().registryAccess(),
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
