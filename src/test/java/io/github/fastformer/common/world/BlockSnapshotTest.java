package io.github.fastformer.common.world;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockSnapshotTest {
   @Test
   void blockStateIsRequired() {
      assertThrows(IllegalArgumentException.class, () -> new BlockSnapshot(null, null));
   }
}
