package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class WorldHistoryPublicationTest {
   @Test
   void nullCancellationSupplierMeansPublicationIsNotCancelled() {
      CompletableFuture<Boolean> journal = CompletableFuture.completedFuture(true);
      WorldHistoryPublication publication = WorldHistoryPublication.afterJournal(
         journal,
         net.minecraft.world.level.Level.OVERWORLD,
         java.util.List.of(),
         java.util.Map.of(),
         null
      );

      assertTrue(publication.batch().isEmpty());
   }

   @Test
   void cancellationOwnsThePublicationAndSuppressesPendingBatch() {
      CompletableFuture<Boolean> journal = new CompletableFuture<>();
      WorldHistoryPublication publication = WorldHistoryPublication.afterJournal(
         journal,
         net.minecraft.world.level.Level.OVERWORLD,
         java.util.List.of(),
         java.util.Map.of(),
         () -> false
      );

      publication.cancel();
      journal.complete(true);

      assertTrue(publication.batch().isEmpty());
   }

   @Test
   void stopForRecoveryWaitsForStartedCompressionBeforeReleasingSnapshots() throws Exception {
      CountDownLatch compressionStarted = new CountDownLatch(1);
      CountDownLatch releaseCompression = new CountDownLatch(1);
      BlockPos position = new BlockPos(2, 3, 4);
      ReversibleBlockSnapshot before = snapshot(position, "before");
      ReversibleBlockSnapshot after = snapshot(position, "after");
      BlockingSnapshots snapshots = new BlockingSnapshots(List.of(before), compressionStarted, releaseCompression);
      WorldHistoryPublication publication = WorldHistoryPublication.afterJournal(
         CompletableFuture.completedFuture(true),
         net.minecraft.world.level.Level.OVERWORLD,
         snapshots,
         Map.of(position, after),
         () -> false
      );

      assertTrue(compressionStarted.await(5, TimeUnit.SECONDS));
      CompletableFuture<Void> recovery = publication.stopForRecovery();
      assertFalse(recovery.isDone());
      assertTrue(snapshots.values.contains(before));

      releaseCompression.countDown();
      recovery.join();
      assertTrue(publication.batch().isEmpty());
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      CompoundTag data = new CompoundTag();
      data.putString("marker", marker);
      return new ReversibleBlockSnapshot(
         position, null, null, new BlockEntitySnapshot(data)
      );
   }

   private static final class BlockingSnapshots extends AbstractCollection<ReversibleBlockSnapshot> {
      private final List<ReversibleBlockSnapshot> values;
      private final CountDownLatch started;
      private final CountDownLatch release;

      private BlockingSnapshots(
         List<ReversibleBlockSnapshot> values,
         CountDownLatch started,
         CountDownLatch release
      ) {
         this.values = values;
         this.started = started;
         this.release = release;
      }

      @Override
      public Iterator<ReversibleBlockSnapshot> iterator() {
         Iterator<ReversibleBlockSnapshot> delegate = this.values.iterator();
         return new Iterator<>() {
            @Override
            public boolean hasNext() {
               return delegate.hasNext();
            }

            @Override
            public ReversibleBlockSnapshot next() {
               BlockingSnapshots.this.started.countDown();
               try {
                  if (!BlockingSnapshots.this.release.await(5, TimeUnit.SECONDS)) {
                     throw new IllegalStateException("compression test was not released");
                  }
               } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException("compression test interrupted", exception);
               }
               return delegate.next();
            }
         };
      }

      @Override
      public int size() {
         return this.values.size();
      }
   }
}
