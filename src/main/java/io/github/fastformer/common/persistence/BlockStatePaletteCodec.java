package io.github.fastformer.common.persistence;

import java.io.IOException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Strict palette decoding shared by history and write-ahead recovery. */
public final class BlockStatePaletteCodec {
   private BlockStatePaletteCodec() {}

   public static BlockState decode(HolderLookup.Provider registries, CompoundTag tag)
      throws IOException {
      if (!tag.contains("Name", Tag.TAG_STRING)) throw new IOException("Missing block state name");
      String name = tag.getString("Name");
      ResourceLocation id = ResourceLocation.tryParse(name);
      if (id == null) throw new IOException("Invalid block state name: " + name);
      var block = registries.lookupOrThrow(Registries.BLOCK).get(ResourceKey.create(Registries.BLOCK, id))
         .orElseThrow(() -> new IOException("Unknown block state: " + name)).value();
      BlockState state = block.defaultBlockState();
      if (!tag.contains("Properties")) return state;
      if (!tag.contains("Properties", Tag.TAG_COMPOUND)) throw new IOException("Invalid block state properties");
      CompoundTag properties = tag.getCompound("Properties");
      for (String propertyName : properties.getAllKeys()) {
         Property<?> property = block.getStateDefinition().getProperty(propertyName);
         if (property == null || !properties.contains(propertyName, Tag.TAG_STRING)) {
            throw new IOException("Unknown or invalid block property: " + propertyName);
         }
         state = setProperty(state, property, properties.getString(propertyName));
      }
      return state;
   }

   private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property,
      String encoded) throws IOException {
      T value = property.getValue(encoded).orElseThrow(
         () -> new IOException("Invalid value for block property " + property.getName() + ": " + encoded));
      return state.setValue(property, value);
   }
}
