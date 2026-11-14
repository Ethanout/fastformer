package io.github.fastformer.common.geometry;

import io.github.fastformer.common.geometry.GeometryNumbers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeometryNumbersTest {
   @Test
   void formatsNegativeZeroAsZero() {
      assertEquals(0.0, GeometryNumbers.cleanZero(-0.0));
      assertEquals("0.0", GeometryNumbers.fixed(-0.0, 1));
   }
}
