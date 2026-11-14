package io.github.fastformer.client.interaction.binding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.ContextFields;
import io.github.fastformer.client.interaction.binding.data.BindingFile;
import io.github.fastformer.testkit.BindingFixtures;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HoldLineDefaultBindingsTest {
   @Test void actualHoldResourceUsesOnlyRegisteredContextFields() throws Exception {
      var fields = ContextFields.defaults().freeze();
      for (var binding : resource().bindings()) {
         for (String field : binding.context().keySet()) {
            assertTrue(fields.known(field.startsWith("!") ? field.substring(1) : field), field);
         }
      }
   }

   @Test void originalCoexistenceFactsExcludeHoldBeforeAnyDeferredCapture() throws Exception {
      for (int flags = 0; flags < 16; flags++) {
         BindingSystem system = BindingFixtures.system(resource());
         BindingContext context = facts((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0);
         assertEquals(flags == 0, system.onButton(1, "right", true, 1_000_000_000L, context, new Commands()), "flags=" + flags);
         assertEquals(flags == 0, system.active(), "flags=" + flags);
      }
   }

   @Test void actualShortHoldReplaysOnceAtTheOriginalPhysicalTime() throws Exception {
      BindingSystem system = BindingFixtures.system(resource());
      Commands commands = new Commands();
      BindingContext context = facts(false, false, false, false);
      long time = 1_000_000_000L;
      assertTrue(system.onButton(1, "right", true, time, context, commands));
      assertTrue(system.onButton(1, "right", false, time + 80_000_000L, context, commands));
      system.tick(time + 500_000_000L);
      assertEquals(time, commands.replayedAt);
      assertEquals(1, commands.replays);
   }

   private static BindingFile resource() throws Exception {
      try (var stream = HoldLineDefaultBindingsTest.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/hold_line.json")) {
         assertNotNull(stream);
         BindingFile file = BindingFile.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
         assertTrue(file.diagnostics().isEmpty(), file.diagnostics().toString());
         return file;
      }
   }

   private static BindingContext facts(boolean alt, boolean far, boolean tinker, boolean secondPress) {
      Map<String, JsonElement> fields = new HashMap<>();
      fields.put("session", new JsonPrimitive("fastformer:idle"));
      fields.put("screen", new JsonPrimitive(false));
      fields.put("aiming", new JsonPrimitive("block"));
      fields.put("range", new JsonPrimitive(far ? "far" : "near"));
      fields.put("items", array("#fastformer:placeable"));
      fields.put("modifiers", alt ? array("alt") : new JsonArray());
      fields.put("aiming_tag", new JsonArray());
      fields.put("tinker_consumes", new JsonPrimitive(tinker));
      fields.put("double_second_press", new JsonPrimitive(secondPress));
      return new BindingContext(fields);
   }

   private static JsonArray array(String value) {
      JsonArray array = new JsonArray();
      array.add(value);
      return array;
   }

   private static final class Commands implements BindingCommands {
      private long replayedAt;
      private int replays;
      public void replayPress(long time) { replayedAt = time; replays++; }
   }
}
