package io.github.fastformer.client.operation.selection.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.fastformer.common.selection.OperationSelectionMode;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SelectionToolRegistryTest {
   @Test void duplicateAndLateRegistrationFailAndUnknownToolsDoNotDecodeAsCuboids() {
      var registry = new SelectionToolRegistry();
      var tool = new BuiltinSelectionTool(OperationSelectionMode.CUBOID);
      registry.register(tool);
      assertThrows(IllegalArgumentException.class, () -> registry.register(tool));
      assertSame(tool, registry.require(tool.id()));
      registry.freeze();
      assertThrows(IllegalStateException.class, () -> registry.register(new BuiltinSelectionTool(OperationSelectionMode.PRISM)));
      assertThrows(IllegalArgumentException.class, () -> registry.require(ResourceLocation.parse("example:unknown")));
      assertTrue(registry.codec().parse(JsonOps.INSTANCE, JsonParser.parseString("{\"tool\":\"example:unknown\"}")).error().isPresent());
      var wrongGeometry = new SelectionToolState(tool.id(), OperationSelectionMode.SMART, List.of(), 0, null, null, false, Map.of());
      assertTrue(registry.codec().encodeStart(JsonOps.INSTANCE, wrongGeometry).error().isPresent());
   }

   @Test void toolParametersAndSecondOnlyStateRoundTripThroughRegisteredCodec() {
      var registry = new SelectionToolRegistry();
      var tool = new BuiltinSelectionTool(OperationSelectionMode.CUBOID);
      registry.register(tool);
      registry.freeze();
      var mutable = new BlockPos.MutableBlockPos(2, 3, 4);
      var state = new SelectionToolState(tool.id(), tool.geometry(), List.of(mutable), 0, mutable, mutable, true, Map.of("radius", "2"));
      mutable.set(9, 9, 9);
      var encoded = registry.codec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
      var decoded = registry.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow();
      assertEquals(state, decoded);
      assertEquals(new BlockPos(2, 3, 4), decoded.secondPoint());
      assertThrows(UnsupportedOperationException.class, () -> decoded.parameters().put("radius", "3"));
      encoded.getAsJsonObject().remove("second_only");
      assertTrue(registry.codec().parse(JsonOps.INSTANCE, encoded).error().isPresent());
   }
}
