package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommittedJournalRetentionTest {
   @TempDir Path directory;

   @Test
   void cleanupWaitsForDurableIoAndOnlyClaimsTheSavedWorldAndDimension() throws Exception {
      var retention = new CommittedJournalRetention();
      Path overworld = journal(directory.resolve("world"), "overworld");
      Path nether = journal(overworld.getParent(), "nether");
      Path otherSave = journal(directory.resolve("another-save"), "overworld");
      retention.retain(overworld, Level.OVERWORLD);
      retention.retain(nether, Level.NETHER);
      retention.retain(otherSave, Level.OVERWORLD);
      var queued = new ArrayDeque<Runnable>();
      var barrier = new AtomicBoolean();
      retention.saved(Level.OVERWORLD, overworld.getParent(), queued::addLast, () -> {
         assertTrue(Files.exists(overworld));
         barrier.set(true);
      });
      retention.saved(Level.OVERWORLD, overworld.getParent(), queued::addLast, () -> {});
      assertEquals(1, queued.size());
      assertTrue(Files.exists(overworld));
      queued.removeFirst().run();
      assertTrue(barrier.get());
      assertFalse(Files.exists(overworld));
      assertTrue(Files.exists(nether));
      assertTrue(Files.exists(otherSave));
   }

   @Test
   void oldQueuedCleanupCannotDeleteAReplacementRegistrationOrReleaseItsClaim() throws Exception {
      var retention = new CommittedJournalRetention();
      var queued = new ArrayDeque<Runnable>();
      Path path = journal(directory, "same-path");
      retention.retain(path, Level.OVERWORLD);
      retention.saved(Level.OVERWORLD, directory, queued::addLast, () -> {});
      retention.forget(path);
      retention.retain(path, Level.OVERWORLD);
      retention.saved(Level.OVERWORLD, directory, queued::addLast, () -> {});
      queued.removeFirst().run();
      assertTrue(Files.exists(path));
      retention.saved(Level.OVERWORLD, directory, queued::addLast, () -> {});
      assertEquals(1, queued.size(), "old completion released the successor's pending claim");
      queued.removeFirst().run();
      assertFalse(Files.exists(path));
   }

   @Test
   void closingOneRuntimeRetiresItsQueueWithoutAffectingTheNextRuntimeAtTheSamePath() throws Exception {
      var first = new CommittedJournalRetention();
      var second = new CommittedJournalRetention();
      var queued = new ArrayDeque<Runnable>();
      Path path = journal(directory, "same-path");
      first.retain(path, Level.OVERWORLD);
      first.saved(Level.OVERWORLD, directory, queued::addLast, () -> {});
      first.close();
      first.retain(path, Level.OVERWORLD);
      second.retain(path, Level.OVERWORLD);
      queued.removeFirst().run();
      assertTrue(Files.exists(path));
      first.saved(Level.OVERWORLD, directory, queued::addLast, () -> {});
      assertTrue(queued.isEmpty());
      second.saved(Level.OVERWORLD, directory, Runnable::run, () -> {});
      assertFalse(Files.exists(path));
   }

   @Test
   void failedAdmissionAndFailedDurabilityRetainTheJournalForAnotherSave() throws Exception {
      var retention = new CommittedJournalRetention();
      Path path = journal(directory, "retry");
      retention.retain(path, Level.OVERWORLD);
      retention.saved(Level.OVERWORLD, directory, work -> { throw new RejectedExecutionException("expected rejection"); }, () -> {});
      assertTrue(Files.exists(path));
      retention.saved(Level.OVERWORLD, directory, Runnable::run, () -> { throw new IllegalStateException("expected IO failure"); });
      assertTrue(Files.exists(path));
      retention.saved(Level.OVERWORLD, directory, Runnable::run, () -> {});
      assertFalse(Files.exists(path));
   }

   @Test
   void startupInvalidationRetiresAlreadyQueuedCleanup() throws Exception {
      var retention = new CommittedJournalRetention();
      var queued = new ArrayDeque<Runnable>();
      Path path = journal(directory, "recovery");
      retention.retain(path, Level.OVERWORLD);
      retention.saved(Level.OVERWORLD, directory, queued::addLast, () -> {});
      retention.clear();
      queued.removeFirst().run();
      assertTrue(Files.exists(path));
   }

   private static Path journal(Path parent, String name) throws Exception {
      Path path = Files.createDirectories(parent.resolve(name));
      Files.writeString(path.resolve("seal.done"), "committed");
      return path;
   }
}
