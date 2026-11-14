package io.github.fastformer.client.operation.selection.tool;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import io.github.fastformer.core.registry.Registry;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/** Registry construction is explicit; drafts never silently fall back from an unknown tool ID. */
public final class SelectionToolRegistry {
   private final Registry<ResourceLocation, SelectionTool> tools = Registry.create("selection tools");
   private final Codec<SelectionToolState> codec = ResourceLocation.CODEC.partialDispatch("tool",
      state -> DataResult.success(state.toolId()), id -> tools.get(id).map(this::checkedCodec)
         .map(DataResult::success).orElseGet(() -> DataResult.error(() -> "Unknown selection tool: " + id)));

   public synchronized void register(SelectionTool tool) { tools.register(tool.id(), tool); }
   public synchronized void freeze() { tools.freeze(); }
   public SelectionTool require(ResourceLocation id) {
      return tools.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown selection tool: " + id));
   }
   public Codec<SelectionToolState> codec() { return codec; }
   public List<SelectionTool> ordered() { return tools.ordered(Comparator.comparing(tool -> tool.id().toString())).toList(); }
   private MapCodec<SelectionToolState> checkedCodec(SelectionTool tool) {
      return tool.codec().flatXmap(state -> validate(tool, state), state -> validate(tool, state));
   }
   private static DataResult<SelectionToolState> validate(SelectionTool tool, SelectionToolState state) {
      return tool.id().equals(state.toolId()) && tool.geometry() == state.geometry() ? DataResult.success(state)
         : DataResult.error(() -> "Selection state does not belong to tool: " + tool.id());
   }
}
