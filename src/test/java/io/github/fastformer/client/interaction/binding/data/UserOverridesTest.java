package io.github.fastformer.client.interaction.binding.data;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserOverridesTest {
   @Test void partialOverrideRetainsTheDefaultActionAndContext() {
      BindingFile defaults = BindingFile.parse(JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:rule","context":{"session":"fastformer:idle"},
          "input":{"button":"right","gesture":"press"},"action":"fastformer:block"}]}
         """));
      BindingFile merged = UserOverrides.merge(defaults, JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:rule","enabled":false,"priority":42}]}
         """).getAsJsonObject());
      assertFalse(merged.bindings().getFirst().enabled());
      assertEquals(42, merged.bindings().getFirst().priority());
      assertEquals(defaults.bindings().getFirst().action(), merged.bindings().getFirst().action());
      assertEquals(defaults.bindings().getFirst().context(), merged.bindings().getFirst().context());
      assertTrue(merged.diagnostics().isEmpty());
   }
}
