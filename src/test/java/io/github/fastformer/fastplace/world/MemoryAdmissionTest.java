package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryAdmissionTest {
   @Test
   void distinguishesSoftPressureFromHardRejection() {
      MemoryAdmission soft = WorldOperationMemory.admission(1L, 100L * 1024L * 1024L, 1024L * 1024L * 1024L, 700L * 1024L * 1024L, 0L);
      assertEquals(MemoryAdmissionStatus.SOFT_PRESSURE, soft.status());
      assertTrue(soft.allowed());
      MemoryAdmission hard = WorldOperationMemory.admission(10_000_000L, 0L, 1024L * 1024L * 1024L, 900L * 1024L * 1024L, 0L);
      assertEquals(MemoryAdmissionStatus.HARD_REJECTED, hard.status());
   }
}
