package io.github.fastformer.analysis;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Optional development scan. The Gradle init script adds this source only for the scan. */
@EventBusSubscriber(modid = "fastformer")
public final class BlockStateReport {
   private BlockStateReport() {}

   @SubscribeEvent
   public static void export(ServerStartedEvent event) throws IOException {
      String output = System.getProperty("fastformer.blockStateReport");
      if (output == null) return;
      JsonObject report = new JsonObject();
      report.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
      JsonObject blocks = new JsonObject();
      BuiltInRegistries.BLOCK.stream().sorted(Comparator.comparing(BlockStateReport::id)).forEach(block -> {
         JsonObject data = new JsonObject();
         data.addProperty("class", block.getClass().getName());
         data.addProperty("state_count", block.getStateDefinition().getPossibleStates().size());
         data.addProperty("block_entity", block.defaultBlockState().hasBlockEntity());
         JsonObject properties = new JsonObject();
         JsonObject defaults = new JsonObject();
         block.getStateDefinition().getProperties().stream().sorted(Comparator.comparing(Property::getName)).forEach(property -> {
            properties.add(property.getName(), values(property));
            defaults.addProperty(property.getName(), defaultValue(block, property));
         });
         data.add("properties", properties);
         data.add("default", defaults);
         blocks.add(id(block), data);
      });
      report.add("blocks", blocks);
      JsonObject weathering = new JsonObject();
      WeatheringCopper.NEXT_BY_BLOCK.get().entrySet().stream().sorted(Comparator.comparing(entry -> id(entry.getKey())))
         .forEach(entry -> weathering.addProperty(id(entry.getKey()), id(entry.getValue())));
      report.add("weathering", weathering);
      JsonObject waxing = new JsonObject();
      HoneycombItem.WAXABLES.get().entrySet().stream().sorted(Comparator.comparing(entry -> id(entry.getKey())))
         .forEach(entry -> waxing.addProperty(id(entry.getKey()), id(entry.getValue())));
      report.add("waxing", waxing);
      Path path = Path.of(output);
      Files.createDirectories(path.getParent());
      Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
   }

   private static String id(Block block) {
      return BuiltInRegistries.BLOCK.getKey(block).toString();
   }

   private static <T extends Comparable<T>> JsonArray values(Property<T> property) {
      JsonArray values = new JsonArray();
      property.getPossibleValues().forEach(value -> values.add(property.getName(value)));
      return values;
   }

   private static <T extends Comparable<T>> String defaultValue(Block block, Property<T> property) {
      return property.getName(block.defaultBlockState().getValue(property));
   }
}
