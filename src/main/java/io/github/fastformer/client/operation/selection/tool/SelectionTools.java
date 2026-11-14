package io.github.fastformer.client.operation.selection.tool;

import com.mojang.serialization.Codec;
import io.github.fastformer.common.selection.OperationSelectionMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Add-ons register tools during mod construction; client setup freezes the runtime catalog. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class SelectionTools {
   private static final SelectionToolRegistry TOOLS = new SelectionToolRegistry();
   static { for (var mode : OperationSelectionMode.values()) TOOLS.register(new BuiltinSelectionTool(mode)); }
   private SelectionTools() {}

   public static void register(SelectionTool tool) { TOOLS.register(tool); }
   @SubscribeEvent public static void freeze(FMLClientSetupEvent event) { TOOLS.freeze(); }
   public static SelectionTool require(ResourceLocation id) { return TOOLS.require(id); }
   public static Codec<SelectionToolState> codec() { return TOOLS.codec(); }
   public static ResourceLocation builtinId(OperationSelectionMode mode) {
      if (mode == null) throw new IllegalArgumentException("A selection tool requires a geometry mode");
      return ResourceLocation.fromNamespaceAndPath("fastformer", mode.name().toLowerCase(java.util.Locale.ROOT));
   }
   public static ResourceLocation next(ResourceLocation current) {
      require(current);
      var ids = choices().stream().map(SelectionTool::id).toList();
      return ids.get((ids.indexOf(current) + 1) % ids.size());
   }
   public static List<SelectionTool> choices() {
      List<SelectionTool> choices = new ArrayList<>();
      choices.add(require(builtinId(OperationSelectionMode.CUBOID)));
      choices.add(require(builtinId(OperationSelectionMode.SMART)));
      TOOLS.ordered().stream().filter(SelectionTool::cycleVisible).filter(tool -> !choices.contains(tool)).forEach(choices::add);
      return List.copyOf(choices);
   }
}
