package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildingShellFaceBufferTest {
   @Test
   void indexCapacityCoversSixIntIndicesPerFace() {
      assertEquals(256, BuildingShellFaceBuffer.indexCapacity(0));
      assertEquals(2400, BuildingShellFaceBuffer.indexCapacity(100));
      assertEquals(16 * 1024 * 1024, BuildingShellFaceBuffer.indexCapacity(Integer.MAX_VALUE));
   }
}
