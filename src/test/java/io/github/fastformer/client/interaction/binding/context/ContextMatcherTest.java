package io.github.fastformer.client.interaction.binding.context;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextMatcherTest {
   private static Map<String, com.google.gson.JsonElement> fields(String source) {
      var result = new java.util.LinkedHashMap<String, com.google.gson.JsonElement>();
      JsonParser.parseString(source).getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
      return result;
   }

   @Test void matchesAndNegationUsingFrozenFacts() {
      var matcher = new ContextMatcher(ContextFields.defaults().freeze());
      var facts = fields("{\"session\":\"fastformer:idle\",\"modifiers\":[],\"screen\":false}");
      var context = new BindingContext(facts);
      facts.get("modifiers").getAsJsonArray().add("alt");
      assertTrue(matcher.matches(fields("{\"session\":\"fastformer:idle\",\"!modifiers\":[\"alt\"],\"screen\":false}"), context));
      assertFalse(matcher.matches(fields("{\"session\":\"fastformer:quick_shape\",\"screen\":false}"), context));
      assertFalse(matcher.matches(fields("{\"!unknown\":true}"), context));
   }

   @Test void registeredFieldCanDefineItsOwnComparison() {
      var fields = ContextFields.defaults().register("minimum", (expected, actual) -> actual.getAsInt() >= expected.getAsInt()).freeze();
      assertTrue(new ContextMatcher(fields).matches(fields("{\"minimum\":3}"), new BindingContext(fields("{\"minimum\":4}"))));
   }
}
