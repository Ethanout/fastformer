package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FastPlaceMessagesTest {
   @Test void operationIdsBecomeSupportedTranslationArguments() {
      var id = java.util.UUID.fromString("84143053-e7df-416c-a2d0-91de44255dbe");
      assertEquals(id.toString(), clean(id)[0]);
   }
   @Test void truncationKeepsSupplementaryCharactersIntact() {
      String value = (String) clean("x".repeat(511) + "\uD83D\uDE00" + "y".repeat(10))[0];
      assertEquals("x".repeat(511) + "...", value);
   }
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
