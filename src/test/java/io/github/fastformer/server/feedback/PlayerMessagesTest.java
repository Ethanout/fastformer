package io.github.fastformer.server.feedback;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlayerMessagesTest {
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
      var message = PlayerMessages.text("fastformer.test.arguments", args);
      var contents = assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class, message.getContents());
      return contents.getArgs();
   }
}
