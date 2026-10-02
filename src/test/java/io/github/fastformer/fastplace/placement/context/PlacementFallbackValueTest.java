package io.github.fastformer.fastplace.placement.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class PlacementFallbackValueTest {
   @Test
   void rejectsUnknownContextSourcesInsteadOfTreatingThemAsLiteralValues() {
      assertTrue(PlacementFallbackValue.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("$player_horizonal")).error().isPresent());
      assertTrue(PlacementFallbackValue.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(" ")).error().isPresent());
   }

   @Test
   void preservesLiteralValuesAndKnownSourcesDuringSync() {
      for (String value : new String[] {"east", "true", "floor", "7", "$player_horizontal_opposite", "$attachment_face", "$waterlogged"}) {
         var decoded = PlacementFallbackValue.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive(value)).getOrThrow();
         assertEquals(value, decoded.expression());
         assertEquals(new JsonPrimitive(value), PlacementFallbackValue.CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
      }
   }
}
