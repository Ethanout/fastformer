package io.github.fastformer.server.history.entry;

import io.github.fastformer.common.persistence.BlockStatePaletteCodec;
import io.github.fastformer.common.persistence.IncompatibleWorldDataException;
import io.github.fastformer.common.persistence.MinecraftDataVersion;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/** Strict current-format NBT encoding of the compact block entry representation. */
final class BlockChangeEntryCodec {
   private static final int CODEC_VERSION = 2;
   private static final int MAX_CODEC_ENTRIES = 16 * 1024 * 1024;

   private BlockChangeEntryCodec() {}

   /** Encodes the compact in-memory representation without expanding dense positions or uniform palette IDs. */
   public static CompoundTag encode(WorldChangeBatch batch) throws IOException {
      validateForEncoding(batch);
      CompoundTag root = new CompoundTag();
      root.putString("Type", WorldChangeBatch.TYPE.toString());
      root.putInt("Version", CODEC_VERSION);
      root.putInt("DataVersion", MinecraftDataVersion.current());
      batch.bounds().encode(root);
      root.putString("Dimension", batch.dimension.location().toString());
      if (batch.operationId != null) {
         root.putUUID("OperationId", batch.operationId);
      }
      root.putInt("Size", batch.size());
      if (batch.tinker != null) root.put("Tinker", batch.tinker.encode());
      if (batch.positions != null) {
         root.putLongArray("Positions", batch.positions.clone());
      } else {
         CompoundTag dense = new CompoundTag();
         dense.putInt("MinX", batch.denseMinX);
         dense.putInt("MinY", batch.denseMinY);
         dense.putInt("MinZ", batch.denseMinZ);
         dense.putInt("Width", batch.denseWidth);
         dense.putInt("Height", batch.denseHeight);
         dense.putInt("Depth", batch.denseDepth);
         root.put("Dense", dense);
      }
      root.put("BlockPalette", encodeBlockPalette(batch));
      root.put("FluidPalette", encodeFluidPalette(batch));
      putIds(root, "BeforeBlock", batch.beforeBlockIds, batch.uniformBeforeBlockId);
      putIds(root, "AfterBlock", batch.afterBlockIds, batch.uniformAfterBlockId);
      putIds(root, "BeforeFluid", batch.beforeFluidIds, batch.uniformBeforeFluidId);
      putIds(root, "AfterFluid", batch.afterFluidIds, batch.uniformAfterFluidId);
      putEntities(root, "BeforeEntities", batch.beforeEntities);
      putEntities(root, "AfterEntities", batch.afterEntities);
      return root;
   }

   /** Decodes and validates an untrusted history payload against the current registries. */
   public static WorldChangeBatch decode(HolderLookup.Provider registries, CompoundTag root) throws IOException {
      if (registries == null || root == null) {
         throw new IOException("Missing world change batch data");
      }
      if (!WorldChangeBatch.TYPE.equals(EntryCodecRegistry.typeId(root))) {
         throw new IncompatibleWorldDataException("Expected block-change history entry");
      }
      requireInt(root, "Version");
      int version = root.getInt("Version");
      if (version != CODEC_VERSION) {
         throw new IncompatibleWorldDataException("Unsupported world change batch version: " + root.getInt("Version"));
      }
      MinecraftDataVersion.read(root);
      String dimensionName = requireString(root, "Dimension");
      ResourceLocation dimensionId = ResourceLocation.tryParse(dimensionName);
      if (dimensionId == null) {
         throw new IOException("Invalid world change batch dimension: " + dimensionName);
      }
      ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
      UUID operationId = null;
      if (root.contains("OperationId")) {
         try {
            operationId = root.getUUID("OperationId");
         } catch (RuntimeException exception) {
            throw new IOException("Invalid world change batch operation ID", exception);
         }
      }
      int size = requiredSize(root);
      PositionLayout positions = decodePositions(root, size);
      BlockState[] blockStates = decodeBlockPalette(registries, root);
      FluidState[] fluidStates = decodeFluidPalette(registries, root);
      IdLayout beforeBlocks = decodeIds(root, "BeforeBlock", size, blockStates.length);
      IdLayout afterBlocks = decodeIds(root, "AfterBlock", size, blockStates.length);
      IdLayout beforeFluids = decodeIds(root, "BeforeFluid", size, fluidStates.length);
      IdLayout afterFluids = decodeIds(root, "AfterFluid", size, fluidStates.length);
      BlockEntitySnapshot[] beforeEntities = decodeEntities(root, "BeforeEntities", size);
      BlockEntitySnapshot[] afterEntities = decodeEntities(root, "AfterEntities", size);
      int estimatedBytes = WorldChangeBatch.estimateBytes(
         positions.positions(), beforeBlocks.ids(), afterBlocks.ids(), beforeFluids.ids(), afterFluids.ids(),
         beforeEntities, afterEntities, Arrays.asList(blockStates), Arrays.asList(fluidStates)
      );
      return new WorldChangeBatch(
         dimension, operationId, positions.positions(), positions.minX(), positions.minY(), positions.minZ(),
         positions.width(), positions.height(), positions.depth(), blockStates, fluidStates,
         beforeBlocks.ids(), beforeBlocks.uniformId(), afterBlocks.ids(), afterBlocks.uniformId(),
         beforeFluids.ids(), beforeFluids.uniformId(), afterFluids.ids(), afterFluids.uniformId(),
         beforeEntities, afterEntities, estimatedBytes,
         root.contains("Tinker") ? BlockEditHistoryMetadata.decode(root) : null,
         HistoryBounds.decode(root, HistoryBounds.of(positions.positions(), positions.minX(), positions.minY(), positions.minZ(),
            positions.width(), positions.height(), positions.depth()))
      );
   }

   private static void validateForEncoding(WorldChangeBatch batch) throws IOException {
      int size = batch.size();
      if (batch.dimension == null || size <= 0 || size > MAX_CODEC_ENTRIES) {
         throw new IOException("Invalid world change batch size or dimension");
      }
      validatePalette(batch.blockStates, "block");
      validatePalette(batch.fluidStates, "fluid");
      validateIds(batch.beforeBlockIds, batch.uniformBeforeBlockId, size, batch.blockStates.length, "before block");
      validateIds(batch.afterBlockIds, batch.uniformAfterBlockId, size, batch.blockStates.length, "after block");
      validateIds(batch.beforeFluidIds, batch.uniformBeforeFluidId, size, batch.fluidStates.length, "before fluid");
      validateIds(batch.afterFluidIds, batch.uniformAfterFluidId, size, batch.fluidStates.length, "after fluid");
      validateEntityArray(batch.beforeEntities, size, "before");
      validateEntityArray(batch.afterEntities, size, "after");
   }

   private static ListTag encodeBlockPalette(WorldChangeBatch batch) throws IOException {
      ListTag palette = new ListTag();
      for (BlockState state : batch.blockStates) {
         if (BuiltInRegistries.BLOCK.getKey(state.getBlock()) == null) {
            throw new IOException("World change batch contains an unregistered block");
         }
         palette.add(NbtUtils.writeBlockState(state));
      }
      return palette;
   }

   private static ListTag encodeFluidPalette(WorldChangeBatch batch) throws IOException {
      ListTag palette = new ListTag();
      for (FluidState state : batch.fluidStates) {
         ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(state.getType());
         if (fluidId == null) {
            throw new IOException("World change batch contains an unregistered fluid");
         }
         CompoundTag encoded = new CompoundTag();
         encoded.putString("Name", fluidId.toString());
         if (!state.getValues().isEmpty()) {
            CompoundTag properties = new CompoundTag();
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
               properties.putString(entry.getKey().getName(), propertyValueName(entry.getKey(), entry.getValue()));
            }
            encoded.put("Properties", properties);
         }
         palette.add(encoded);
      }
      return palette;
   }

   @SuppressWarnings({"rawtypes", "unchecked"})
   private static String propertyValueName(Property property, Comparable value) {
      return property.getName(value);
   }

   private static void putIds(CompoundTag root, String name, int[] ids, int uniformId) {
      if (ids == null) {
         root.putInt(name + "UniformId", uniformId);
      } else {
         root.putIntArray(name + "Ids", ids.clone());
      }
   }

   private static void putEntities(CompoundTag root, String name, BlockEntitySnapshot[] entities) {
      if (entities == null) {
         return;
      }
      ListTag encoded = new ListTag();
      for (int index = 0; index < entities.length; index++) {
         if (entities[index] != null) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Index", index);
            entry.put("Data", entities[index].data().copy());
            encoded.add(entry);
         }
      }
      if (!encoded.isEmpty()) {
         root.put(name, encoded);
      }
   }

   private static int requiredSize(CompoundTag root) throws IOException {
      requireInt(root, "Size");
      int size = root.getInt("Size");
      if (size <= 0 || size > MAX_CODEC_ENTRIES) {
         throw new IOException("Invalid world change batch size: " + size);
      }
      return size;
   }

   private static PositionLayout decodePositions(CompoundTag root, int size) throws IOException {
      boolean sparse = root.contains("Positions", Tag.TAG_LONG_ARRAY);
      boolean dense = root.contains("Dense", Tag.TAG_COMPOUND);
      if (sparse == dense) {
         throw new IOException("World change batch must contain exactly one position layout");
      }
      if (sparse) {
         long[] positions = root.getLongArray("Positions").clone();
         if (positions.length != size) {
            throw new IOException("World change batch position count does not match size");
         }
         for (int index = 1; index < positions.length; index++) {
            if (positions[index - 1] >= positions[index]) {
               throw new IOException("World change batch positions are not strictly ordered");
            }
         }
         return new PositionLayout(positions, 0, 0, 0, 0, 0, 0);
      }
      CompoundTag encoded = root.getCompound("Dense");
      int minX = requiredInt(encoded, "MinX");
      int minY = requiredInt(encoded, "MinY");
      int minZ = requiredInt(encoded, "MinZ");
      int width = positiveDimension(encoded, "Width");
      int height = positiveDimension(encoded, "Height");
      int depth = positiveDimension(encoded, "Depth");
      if (width > size || height > size || depth > size || (long)width * height > size) {
         throw new IOException("World change batch dense volume does not match size");
      }
      long volume = (long)width * height * depth;
      if (volume != size) {
         throw new IOException("World change batch dense volume does not match size");
      }
      long maxX = (long)minX + width - 1L;
      long maxY = (long)minY + height - 1L;
      long maxZ = (long)minZ + depth - 1L;
      if (maxX > Integer.MAX_VALUE || maxY > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE) {
         throw new IOException("World change batch dense bounds overflow");
      }
      return new PositionLayout(null, minX, minY, minZ, width, height, depth);
   }

   private static BlockState[] decodeBlockPalette(HolderLookup.Provider registries, CompoundTag root) throws IOException {
      ListTag encoded = requiredCompoundList(root, "BlockPalette");
      BlockState[] palette = new BlockState[encoded.size()];
      for (int index = 0; index < encoded.size(); index++) {
         palette[index] = BlockStatePaletteCodec.decode(registries, encoded.getCompound(index));
      }
      return palette;
   }

   private static FluidState[] decodeFluidPalette(HolderLookup.Provider registries, CompoundTag root) throws IOException {
      ListTag encoded = requiredCompoundList(root, "FluidPalette");
      var fluids = registries.lookupOrThrow(Registries.FLUID);
      FluidState[] palette = new FluidState[encoded.size()];
      for (int index = 0; index < encoded.size(); index++) {
         CompoundTag stateTag = encoded.getCompound(index);
         String fluidName = requireString(stateTag, "Name");
         ResourceLocation fluidId = ResourceLocation.tryParse(fluidName);
         if (fluidId == null) {
            throw new IOException("Invalid fluid in world change batch: " + fluidName);
         }
         Fluid fluid = fluids.get(ResourceKey.create(Registries.FLUID, fluidId))
            .orElseThrow(() -> new IOException("Unknown fluid in world change batch: " + fluidName)).value();
         FluidState state = fluid.defaultFluidState();
         if (stateTag.contains("Properties")) {
            if (!stateTag.contains("Properties", Tag.TAG_COMPOUND)) {
               throw new IOException("Invalid fluid properties in world change batch");
            }
            CompoundTag properties = stateTag.getCompound("Properties");
            for (String propertyName : properties.getAllKeys()) {
               if (!properties.contains(propertyName, Tag.TAG_STRING)) {
                  throw new IOException("Invalid fluid property value: " + propertyName);
               }
               Property<?> property = fluid.getStateDefinition().getProperty(propertyName);
               if (property == null) {
                  throw new IOException("Unknown fluid property: " + propertyName);
               }
               state = setProperty(state, property, properties.getString(propertyName));
            }
         }
         palette[index] = state;
      }
      return palette;
   }

   private static <S extends StateHolder<?, S>, T extends Comparable<T>> S setProperty(
      S state, Property<T> property, String encodedValue
   ) throws IOException {
      T value = property.getValue(encodedValue)
         .orElseThrow(() -> new IOException("Invalid value for property " + property.getName() + ": " + encodedValue));
      return state.setValue(property, value);
   }

   private static IdLayout decodeIds(CompoundTag root, String name, int size, int paletteSize) throws IOException {
      boolean hasIds = root.contains(name + "Ids", Tag.TAG_INT_ARRAY);
      boolean hasUniform = root.contains(name + "UniformId", Tag.TAG_INT);
      if (hasIds == hasUniform) {
         throw new IOException("World change batch must contain exactly one " + name + " ID layout");
      }
      if (hasUniform) {
         int uniform = root.getInt(name + "UniformId");
         validatePaletteId(uniform, paletteSize, name);
         return new IdLayout(null, uniform);
      }
      int[] ids = root.getIntArray(name + "Ids").clone();
      if (ids.length != size) {
         throw new IOException("World change batch " + name + " ID count does not match size");
      }
      for (int id : ids) {
         validatePaletteId(id, paletteSize, name);
      }
      return new IdLayout(ids, -1);
   }

   private static BlockEntitySnapshot[] decodeEntities(CompoundTag root, String name, int size) throws IOException {
      if (!root.contains(name)) {
         return null;
      }
      ListTag encoded = requiredCompoundList(root, name, true);
      BlockEntitySnapshot[] entities = new BlockEntitySnapshot[size];
      boolean[] assigned = new boolean[size];
      for (int entryIndex = 0; entryIndex < encoded.size(); entryIndex++) {
         CompoundTag entry = encoded.getCompound(entryIndex);
         int index = requiredInt(entry, "Index");
         if (index < 0 || index >= size || assigned[index]) {
            throw new IOException("Invalid or duplicate world change batch block entity index: " + index);
         }
         if (!entry.contains("Data", Tag.TAG_COMPOUND)) {
            throw new IOException("Missing world change batch block entity data");
         }
         assigned[index] = true;
         entities[index] = new BlockEntitySnapshot(entry.getCompound("Data"));
      }
      return entities;
   }

   private static ListTag requiredCompoundList(CompoundTag root, String name) throws IOException {
      return requiredCompoundList(root, name, false);
   }

   private static ListTag requiredCompoundList(CompoundTag root, String name, boolean allowEmpty) throws IOException {
      if (!root.contains(name, Tag.TAG_LIST)) {
         throw new IOException("Missing or invalid world change batch " + name);
      }
      ListTag list = (ListTag)root.get(name);
      if ((!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
         || (!allowEmpty && list.isEmpty()) || list.size() > MAX_CODEC_ENTRIES) {
         throw new IOException("Invalid world change batch " + name + " size");
      }
      return list;
   }

   private static String requireString(CompoundTag tag, String name) throws IOException {
      if (!tag.contains(name, Tag.TAG_STRING) || tag.getString(name).isBlank()) {
         throw new IOException("Missing or invalid world change batch " + name);
      }
      return tag.getString(name);
   }

   private static void requireInt(CompoundTag tag, String name) throws IOException {
      if (!tag.contains(name, Tag.TAG_INT)) {
         throw new IOException("Missing or invalid world change batch " + name);
      }
   }

   private static int requiredInt(CompoundTag tag, String name) throws IOException {
      requireInt(tag, name);
      return tag.getInt(name);
   }

   private static int positiveDimension(CompoundTag tag, String name) throws IOException {
      int value = requiredInt(tag, name);
      if (value <= 0) {
         throw new IOException("Invalid world change batch dense " + name);
      }
      return value;
   }

   private static void validatePalette(Object[] palette, String name) throws IOException {
      if (palette == null || palette.length == 0 || palette.length > MAX_CODEC_ENTRIES) {
         throw new IOException("Invalid world change batch " + name + " palette");
      }
      for (Object value : palette) {
         if (value == null) {
            throw new IOException("World change batch " + name + " palette contains null");
         }
      }
   }

   private static void validateIds(int[] ids, int uniformId, int size, int paletteSize, String name) throws IOException {
      if (ids == null) {
         validatePaletteId(uniformId, paletteSize, name);
         return;
      }
      if (ids.length != size) {
         throw new IOException("World change batch " + name + " ID count does not match size");
      }
      for (int id : ids) {
         validatePaletteId(id, paletteSize, name);
      }
   }

   private static void validatePaletteId(int id, int paletteSize, String name) throws IOException {
      if (id < 0 || id >= paletteSize) {
         throw new IOException("Invalid world change batch " + name + " palette ID: " + id);
      }
   }

   private static void validateEntityArray(BlockEntitySnapshot[] entities, int size, String name) throws IOException {
      if (entities != null && entities.length != size) {
         throw new IOException("World change batch " + name + " block entity count does not match size");
      }
   }

   private record PositionLayout(long[] positions, int minX, int minY, int minZ, int width, int height, int depth) {
   }

   private record IdLayout(int[] ids, int uniformId) {
   }

}
