package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldOperationMemoryTest {
   @Test
   void allowsSmallOperationWithComfortableHeapHeadroom() {
      assertTrue(WorldOperationMemory.canPrepare(
         10_000L,
         4L * 1024L * 1024L * 1024L,
         2L * 1024L * 1024L * 1024L,
         1L * 1024L * 1024L * 1024L
      ));
   }

   @Test
   void rejectsLargeOperationBeforeItConsumesSafetyReserve() {
      assertFalse(WorldOperationMemory.canPrepare(
         10_000_000L,
         4L * 1024L * 1024L * 1024L,
         3L * 1024L * 1024L * 1024L,
         256L * 1024L * 1024L
      ));
   }

   @Test
   void arithmeticOverflowCannotPassThePreflight() {
      assertFalse(WorldOperationMemory.canPrepare(Long.MAX_VALUE, Long.MAX_VALUE, 0L, Long.MAX_VALUE));
      assertFalse(WorldOperationMemory.canPrepare(1L, Long.MAX_VALUE, Long.MAX_VALUE, 0L, Long.MAX_VALUE));
   }
}
