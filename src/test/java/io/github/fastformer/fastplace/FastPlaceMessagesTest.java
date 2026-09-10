package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FastPlaceMessagesTest {
   @Test void dynamicTextIsBounded() {
      String value = (String) clean("x".repeat(2_000))[0];
      assertEquals(515, value.length());
      assertTrue(value.endsWith("..."));
   }
   @Test void nonFiniteNumbersAreSafe() {
      Object[] values = clean(Double.NaN, Float.POSITIVE_INFINITY, 1.0);
      assertEquals(0.0, values[0]);
      assertEquals(0.0F, values[1]);
      assertEquals(1.0, values[2]);
   }
   private static Object[] clean(Object... args) {
      try {
         var method = FastPlaceMessages.class.getDeclaredMethod("cleanArgs", Object[].class);
         method.setAccessible(true);
         return (Object[]) method.invoke(null, (Object) args);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }
}
