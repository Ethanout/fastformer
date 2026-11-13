package io.github.fastformer.client.interaction.binding.data;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BindingFileTest {
   @Test void roundTripPreservesOptionsAndFlattenedTree() {
      var file = BindingFile.parse(JsonParser.parseString("""
         {"option":"fastformer:hold_line","option_default":false,
          "tree":{"session:fastformer:quick_shape":{"stage:line":{
             "right/press":{"action":"fastformer:block"}}}}}
         """));
      var restored = BindingFile.CODEC.parse(JsonOps.INSTANCE, BindingFile.CODEC.encodeStart(JsonOps.INSTANCE, file).getOrThrow()).getOrThrow();
      assertEquals(file.options(), restored.options());
      assertEquals(file.bindings(), restored.bindings());
      assertTrue(restored.diagnostics().isEmpty());
   }

   @Test void oneInvalidRuleDoesNotDiscardValidSibling() {
      var file = BindingFile.parse(JsonParser.parseString("""
         {"bindings":[{"id":"bad id"},{"id":"fastformer:good",
          "input":{"button":"right","gesture":"press"},"action":"fastformer:block"}]}
         """));
      assertEquals(1, file.bindings().size());
      assertEquals(1, file.diagnostics().size());
   }
}
