package io.github.fastformer.server.recovery;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.async.AsyncTasks;
import io.github.fastformer.core.persist.StoragePaths;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.lifecycle.ServerBackgroundTasks;
import io.github.fastformer.server.recovery.gate.RecoveryWriteGate;
import io.github.fastformer.server.recovery.spi.HistoryReconciler;
import io.github.fastformer.server.worldwrite.WorldWriteLeases;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;

/** Explicit owner of one server's recovery gate, journal worker and committed cleanup. */
public final class RecoverySystem implements AutoCloseable {
   private static final Map<MinecraftServer, RecoverySystem> SERVERS = new IdentityHashMap<>();
   private final MinecraftServer server;
   private final Executor executor;
   private final Path directory;
   private final RecoveryWriteGate writeGate = new RecoveryWriteGate();
   private final WorldWriteLeases writeLeases = new WorldWriteLeases();
   private final CommittedJournalRetention retention = new CommittedJournalRetention();
   private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() * 1000L);
   private volatile boolean closed;

   RecoverySystem(MinecraftServer server, Executor executor, Path directory) {
      this.server = server;
      this.executor = Objects.requireNonNull(executor);
      this.directory = Objects.requireNonNull(directory);
   }

   public static synchronized void start(MinecraftServer server) {
      requireThread(server);
      if (SERVERS.containsKey(server)) throw new IllegalStateException("Recovery system already started");
      SERVERS.put(server, new RecoverySystem(server, ServerBackgroundTasks.serial(server, "recovery", 512),
         StoragePaths.recovery(server.getWorldPath(LevelResource.ROOT))));
   }

   /** Journal creation runs on the owned worker, so lookup is safe on either thread. */
   public static synchronized RecoverySystem of(MinecraftServer server) {
      return find(server).orElseThrow(() -> new IllegalStateException("Recovery system is not running"));
   }

   public static synchronized Optional<RecoverySystem> find(MinecraftServer server) {
      RecoverySystem recovery = SERVERS.get(server);
      return recovery == null || recovery.closed ? Optional.empty() : Optional.of(recovery);
   }

   public static void stop(MinecraftServer server) {
      requireThread(server);
      RecoverySystem recovery;
      synchronized (RecoverySystem.class) { recovery = SERVERS.remove(server); }
      if (recovery != null) recovery.close();
   }

   public RecoveryWriteGate writeGate() { return writeGate; }
   public WorldWriteLeases writeLeases() { return writeLeases; }
   public Executor executor() { return executor; }

   public Optional<PersistentRecoveryJournal> begin(
      UUID owner, ResourceKey<Level> dimension, Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after, UUID operationId
   ) {
      if (!writeGate.writesAllowed() || owner == null || dimension == null || before == null || before.isEmpty() || after == null) {
         return Optional.empty();
      }
      if (PersistentRecoveryJournal.hasOwnerJournal(directory, owner)) {
         LogUtils.getLogger().error("Refusing to replace an existing FastFormer recovery journal for {}", owner);
         writeGate.blockNewWrites();
         return Optional.empty();
      }
      long next = sequence.updateAndGet(previous -> Math.max(previous + 1L, System.currentTimeMillis() * 1000L));
      UUID operation = operationId == null ? UUID.randomUUID() : operationId;
      Path operationDirectory = directory.resolve(String.format("%020d-%s-%s", next, owner, operation));
      try {
         return Optional.of(PersistentRecoveryJournal.writeSegmentedJournal(
            operationDirectory, owner, operation, dimension, before, after, executor, retention));
      } catch (IOException | RuntimeException failure) {
         LogUtils.getLogger().error("Could not create FastFormer recovery journal for {}", owner, failure);
         PersistentRecoveryJournal.deleteDirectoryQuietly(operationDirectory);
         return Optional.empty();
      }
   }

   public Optional<PersistentRecoveryJournal> begin(
      UUID owner, ResourceKey<Level> dimension, Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) {
      return begin(owner, dimension, before, after, null);
   }

   public boolean recoverAll(HistoryReconciler history) {
      return recoverAll(history, () -> JournalStartupRecovery.saveDurably(server));
   }

   boolean recoverAll(HistoryReconciler history, BooleanSupplier durableSave) {
      requireThread(server);
      if (closed) throw new IllegalStateException("Recovery system is closed");
      long started = writeGate.beginRecovery();
      boolean success = false;
      try {
         if (!awaitIoIdle()) return false;
         retention.clear();
         success = JournalStartupRecovery.recoverAll(server, directory, history, durableSave);
         return success;
      } finally {
         writeGate.finishRecovery(started, success);
      }
   }

   public void onLevelSaved(ServerLevel level) {
      if (closed) return;
      if (level.getServer() != server) throw new IllegalArgumentException("Saved level belongs to another recovery system");
      retention.saved(level.dimension(), directory, executor, IOUtilities::waitUntilIOWorkerComplete);
   }

   /** The barrier covers journal work already admitted by this server. */
   public boolean awaitIoIdle() {
      try {
         AsyncTasks.run(executor, () -> {}).get(30, TimeUnit.SECONDS);
         return true;
      } catch (InterruptedException failure) {
         Thread.currentThread().interrupt();
         writeGate.blockNewWrites();
         LogUtils.getLogger().error("Interrupted while waiting for recovery journal work", failure);
         return false;
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException | OutOfMemoryError failure) {
         writeGate.blockNewWrites();
         LogUtils.getLogger().error("FastFormer recovery journal queue did not reach an idle boundary", failure);
         return false;
      }
   }

   @Override public void close() {
      closed = true;
      writeGate.close();
      writeLeases.close();
      retention.close();
   }

   private static void requireThread(MinecraftServer server) {
      if (server == null || !server.isSameThread()) throw new IllegalStateException("Recovery lifecycle requires its server thread");
   }
}
