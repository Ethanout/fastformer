package io.github.fastformer.server.recovery;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Retains one runtime's committed journals until durable chunk IO and history publication. */
final class CommittedJournalRetention implements AutoCloseable {
   private static final class Entry {
      final Path path;
      final ResourceKey<Level> dimension;
      boolean pending;

      Entry(Path path, ResourceKey<Level> dimension) {
         this.path = path;
         this.dimension = dimension;
      }
   }

   private final Map<Path, Entry> committed = new HashMap<>();
   private boolean closed;

   synchronized void retain(Path path, ResourceKey<Level> dimension) {
      if (!closed && path != null && dimension != null) committed.computeIfAbsent(path, ignored -> new Entry(path, dimension));
   }

   synchronized void forget(Path path) { committed.remove(path); }

   synchronized void clear() { committed.clear(); }

   @Override public synchronized void close() {
      closed = true;
      committed.clear();
   }

   void saved(ResourceKey<Level> dimension, Path directory, Executor executor, Runnable durableIo) {
      List<Entry> ready = claim(dimension, directory);
      if (ready.isEmpty()) return;
      try {
         executor.execute(() -> {
            try {
               durableIo.run();
               for (Entry entry : ready) deleteCurrent(entry);
            } catch (RuntimeException failure) {
               LogUtils.getLogger().error("Could not confirm durable chunk IO for FastFormer committed journals", failure);
            } finally {
               release(ready);
            }
         });
      } catch (RuntimeException failure) {
         release(ready);
         LogUtils.getLogger().warn("Committed journals retained because cleanup could not be queued", failure);
      }
   }

   private synchronized List<Entry> claim(ResourceKey<Level> dimension, Path directory) {
      if (closed) return List.of();
      List<Entry> ready = committed.values().stream()
         .filter(entry -> !entry.pending && entry.dimension.equals(dimension))
         .filter(entry -> PersistentRecoveryJournal.belongsToDirectory(entry.path, directory))
         .toList();
      ready.forEach(entry -> entry.pending = true);
      return ready;
   }

   private synchronized void deleteCurrent(Entry entry) {
      // Validate and delete under the same ownership boundary: close/recovery cannot
      // replace an entry between this check and removing its directory.
      if (closed || committed.get(entry.path) != entry) return;
      try {
         PersistentRecoveryJournal.deleteDirectoryQuietly(entry.path);
         if (!Files.exists(entry.path)) committed.remove(entry.path, entry);
      } catch (RuntimeException failure) {
         LogUtils.getLogger().warn("Committed FastFormer journal will be replayed on next startup: {}", entry.path, failure);
      }
   }

   private synchronized void release(List<Entry> entries) {
      entries.forEach(entry -> entry.pending = false);
   }
}
