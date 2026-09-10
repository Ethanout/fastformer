package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
}
