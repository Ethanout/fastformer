package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.WorldBatchFeedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import org.junit.jupiter.api.Test;

class WorldBatchFeedbackTest {
   @Test
   void clampsAndPublishesOnlyRealBatches() {
      WorldOperationMetrics metrics = new WorldOperationMetrics();
      WorldBatchFeedback feedback = new WorldBatchFeedback(metrics);

      feedback.record(-4, -9L);
      assertEquals(0, feedback.cells());
      assertEquals(0L, feedback.elapsedNanos());

      feedback.record(12, 3_500L);
      assertEquals(12, feedback.cells());
      assertEquals(3_500L, feedback.elapsedNanos());
      org.junit.jupiter.api.Assertions.assertTrue(metrics.summary().contains("batches=1"));
   }

   @Test
   void canRebindMetricsWhenTaskAdoptsBatchOperation() {
      WorldOperationMetrics initial = new WorldOperationMetrics();
      WorldBatchFeedback feedback = new WorldBatchFeedback(initial);
      WorldOperationMetrics adopted = new WorldOperationMetrics();

      feedback.attach(adopted);
      feedback.record(4, 7L);

      assertEquals(0, Integer.parseInt(initial.summary().replaceAll(".*batches=([0-9]+).*", "$1")));
      org.junit.jupiter.api.Assertions.assertTrue(adopted.summary().contains("batches=1"));
   }
}
