package io.github.fastformer.fastplace.world;

import java.io.IOException;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * A compact, immutable before/after description of one world operation.
 *
 * <p>The old implementation kept one Java object (and a copied position) for
 * every changed block.  This representation packs positions into a primitive
 * array and interns repeated block/fluid/entity values into palettes.  A
 * uniform after-state (the common fast-fill case) does not need a second
 * per-block state array at all.</p>
 */
public final class WorldChangeBatch {
   private static final int CODEC_VERSION = 1;
   private static final int MAX_CODEC_ENTRIES = 16 * 1024 * 1024;
   private final ResourceKey<Level> dimension;
   private final UUID operationId;
   private final long[] positions;
   private final int denseMinX;
   private final int denseMinY;
   private final int denseMinZ;
   private final int denseWidth;
   private final int denseHeight;
   private final int denseDepth;
   private final BlockState[] blockStates;
   private final FluidState[] fluidStates;
   private final int[] beforeBlockIds;
   private final int uniformBeforeBlockId;
   private final int[] afterBlockIds;
   private final int uniformAfterBlockId;
   private final int[] beforeFluidIds;
   private final int uniformBeforeFluidId;
   private final int[] afterFluidIds;
   private final int uniformAfterFluidId;
   private final BlockEntitySnapshot[] beforeEntities;
   private final BlockEntitySnapshot[] afterEntities;
   private final int estimatedBytes;

   private WorldChangeBatch(
      ResourceKey<Level> dimension,
      UUID operationId,
      long[] positions,
      int denseMinX,
      int denseMinY,
      int denseMinZ,
      int denseWidth,
      int denseHeight,
      int denseDepth,
      BlockState[] blockStates,
      FluidState[] fluidStates,
      int[] beforeBlockIds,
      int uniformBeforeBlockId,
      int[] afterBlockIds,
      int uniformAfterBlockId,
      int[] beforeFluidIds,
      int uniformBeforeFluidId,
      int[] afterFluidIds,
      int uniformAfterFluidId,
      BlockEntitySnapshot[] beforeEntities,
      BlockEntitySnapshot[] afterEntities,
      int estimatedBytes
   ) {
      this.dimension = dimension;
      this.operationId = operationId;
      this.positions = positions;
      this.denseMinX = denseMinX;
      this.denseMinY = denseMinY;
      this.denseMinZ = denseMinZ;
      this.denseWidth = denseWidth;
      this.denseHeight = denseHeight;
      this.denseDepth = denseDepth;
      this.blockStates = blockStates;
      this.fluidStates = fluidStates;
      this.beforeBlockIds = beforeBlockIds;
      this.uniformBeforeBlockId = uniformBeforeBlockId;
      this.afterBlockIds = afterBlockIds;
      this.uniformAfterBlockId = uniformAfterBlockId;
      this.beforeFluidIds = beforeFluidIds;
      this.uniformBeforeFluidId = uniformBeforeFluidId;
      this.afterFluidIds = afterFluidIds;
      this.uniformAfterFluidId = uniformAfterFluidId;
      this.beforeEntities = beforeEntities;
      this.afterEntities = afterEntities;
      this.estimatedBytes = estimatedBytes;
   }

   /**
    * Builds a batch from the snapshots accumulated by a placement task.  The
    * task adds snapshots at the front of its deque, so the descending iterator
    * is used to retain the first (original) state when a position is written
    * more than once by a move/stack operation.
    */
   public static Optional<WorldChangeBatch> capture(ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> changes) {
      if (level == null || changes == null || changes.isEmpty()) {
         return Optional.empty();
      }
      return capture(level, (Collection<ReversibleBlockSnapshot>)changes);
   }

   /** Builds a batch from snapshots in oldest-to-newest order. */
   public static Optional<WorldChangeBatch> capture(ServerLevel level, Collection<ReversibleBlockSnapshot> changes) {
      if (level == null || changes == null || changes.isEmpty()) {
         return Optional.empty();
      }
      // The primitive linked map preserves first-write order without boxed
      // Long keys or one linked-list node per changed position.
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition = oldestBeforeByPosition(changes);
      if (beforeByPosition.isEmpty()) {
         return Optional.empty();
      }
      Long2ObjectOpenHashMap<ReversibleBlockSnapshot> afterByPosition = new Long2ObjectOpenHashMap<>();
      for (Long2ObjectMap.Entry<ReversibleBlockSnapshot> entry : beforeByPosition.long2ObjectEntrySet()) {
         BlockPos pos = BlockPos.of(entry.getLongKey());
         Optional<ReversibleBlockSnapshot> after = ReversibleBlockSnapshot.capture(level, pos);
         if (after.isEmpty()) {
            return Optional.empty();
         }
         afterByPosition.put(entry.getLongKey(), after.orElseThrow());
      }
      return build(level.dimension(), beforeByPosition, afterByPosition);
   }

   /**
    * Builds a batch from before snapshots and after snapshots that were read
    * incrementally on the server thread.  This is used for large operations so
    * completion never performs millions of world reads in one tick.
    */
   public static Optional<WorldChangeBatch> capturePairs(
      ServerLevel level,
      Collection<ReversibleBlockSnapshot> changes,
      Map<Long, ReversibleBlockSnapshot> afterByPosition
   ) {
      if (level == null) {
         return Optional.empty();
      }
      return capturePairs(level.dimension(), changes, afterByPosition);
   }

   /**
    * Builds a batch without touching the live world.  This is used when a
    * task has already captured every after snapshot but its dimension is
    * temporarily unloaded (for example while a player is changing worlds).
    */
   static Optional<WorldChangeBatch> capturePairs(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> changes,
      Map<Long, ReversibleBlockSnapshot> afterByPosition
   ) {
      if (dimension == null || changes == null || changes.isEmpty() || afterByPosition == null || afterByPosition.isEmpty()) {
         return Optional.empty();
      }
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition = oldestBeforeByPosition(changes);
      if (afterByPosition.size() != beforeByPosition.size()) {
         throw new IllegalStateException("Before/after snapshot positions are not aligned");
      }
      for (long position : beforeByPosition.keySet()) {
         ReversibleBlockSnapshot after = afterByPosition.get(position);
         if (after == null || after.pos().asLong() != position) {
            throw new IllegalStateException("Missing expected-after snapshot");
         }
      }
      return build(dimension, beforeByPosition, afterByPosition::get);
   }

   public static Optional<WorldChangeBatch> capturePairsByPos(
      ServerLevel level,
      Collection<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> afterByPosition
   ) {
      if (level == null) {
         return Optional.empty();
      }
      return capturePairsByPos(level.dimension(), changes, afterByPosition);
   }

   static Optional<WorldChangeBatch> capturePairsByPos(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> afterByPosition
   ) {
      if (dimension == null || changes == null || afterByPosition == null || afterByPosition.isEmpty()) {
         return Optional.empty();
      }
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition = oldestBeforeByPosition(changes);
      if (beforeByPosition.isEmpty() || afterByPosition.size() != beforeByPosition.size()) {
         return Optional.empty();
      }
      // Keep the caller's position-keyed map as the lookup source.  Copying it
      // into a second packed map briefly doubles the snapshot-map footprint for
      // large operations, even though build() only needs point lookups.
      return capturePairs(
         dimension,
         beforeByPosition,
         packed -> {
            ReversibleBlockSnapshot snapshot = afterByPosition.get(BlockPos.of(packed));
            return snapshot == null || snapshot.pos().asLong() != packed ? null : snapshot;
         }
      );
   }

   private static Optional<WorldChangeBatch> capturePairs(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> changes,
      SnapshotLookup afterByPosition
   ) {
      if (dimension == null || changes == null || changes.isEmpty() || afterByPosition == null) {
         return Optional.empty();
      }
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition = oldestBeforeByPosition(changes);
      if (beforeByPosition.isEmpty()) {
         return Optional.empty();
      }
      return capturePairs(dimension, beforeByPosition, afterByPosition);
   }

   private static Optional<WorldChangeBatch> capturePairs(
      ResourceKey<Level> dimension,
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition,
      SnapshotLookup afterByPosition
   ) {
      for (long position : beforeByPosition.keySet()) {
         ReversibleBlockSnapshot after = afterByPosition.get(position);
         if (after == null || after.pos().asLong() != position) {
            throw new IllegalStateException("Missing expected-after snapshot");
         }
      }
      return build(dimension, beforeByPosition, afterByPosition);
   }

   static Optional<WorldChangeBatch> fromPairsForTest(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> afterByPosition
   ) {
      if (dimension == null || changes == null || changes.isEmpty() || afterByPosition == null || afterByPosition.isEmpty()) {
         return Optional.empty();
      }
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition = new Long2ObjectLinkedOpenHashMap<>();
      Long2ObjectOpenHashMap<ReversibleBlockSnapshot> packedAfter = new Long2ObjectOpenHashMap<>();
      for (ReversibleBlockSnapshot change : changes) {
         if (change != null) {
            beforeByPosition.putIfAbsent(change.pos().asLong(), change);
         }
      }
      for (Map.Entry<BlockPos, ReversibleBlockSnapshot> entry : afterByPosition.entrySet()) {
         if (entry.getKey() != null && entry.getValue() != null) {
            packedAfter.put(entry.getKey().asLong(), entry.getValue());
         }
      }
      if (packedAfter.size() != beforeByPosition.size()) {
         return Optional.empty();
      }
      return build(dimension, beforeByPosition, packedAfter);
   }

   /** Builds the oldest-first position map without copying an entire deque. */
   private static Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> oldestBeforeByPosition(
      Collection<ReversibleBlockSnapshot> changes
   ) {
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> result = new Long2ObjectLinkedOpenHashMap<>();
      if (changes instanceof ArrayDeque<?> rawDeque) {
         @SuppressWarnings("unchecked")
         ArrayDeque<ReversibleBlockSnapshot> deque = (ArrayDeque<ReversibleBlockSnapshot>)rawDeque;
         var iterator = deque.descendingIterator();
         while (iterator.hasNext()) {
            ReversibleBlockSnapshot change = iterator.next();
            if (change != null) {
               result.putIfAbsent(change.pos().asLong(), change);
            }
         }
      } else {
         for (ReversibleBlockSnapshot change : changes) {
            if (change != null) {
               result.putIfAbsent(change.pos().asLong(), change);
            }
         }
      }
      return result;
   }

   private static Optional<WorldChangeBatch> build(
      ResourceKey<Level> dimension,
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition,
      Long2ObjectMap<ReversibleBlockSnapshot> afterByPosition
   ) {
      return build(dimension, beforeByPosition, afterByPosition::get);
   }

   private static Optional<WorldChangeBatch> build(
      ResourceKey<Level> dimension,
      Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> beforeByPosition,
      SnapshotLookup afterByPosition
   ) {
      int changedCount = 0;
      for (Long2ObjectMap.Entry<ReversibleBlockSnapshot> entry : beforeByPosition.long2ObjectEntrySet()) {
         ReversibleBlockSnapshot after = afterByPosition.get(entry.getLongKey());
         ReversibleBlockSnapshot before = entry.getValue();
         if (before == null
            || before.pos().asLong() != entry.getLongKey()
            || after == null
            || after.pos().asLong() != entry.getLongKey()) {
            return Optional.empty();
         }
         if (!before.sameContents(after)) {
            changedCount++;
         }
      }
      if (changedCount == 0) {
         return Optional.empty();
      }
      long[] changedPositions = new long[changedCount];
      int changedIndex = 0;
      for (Long2ObjectMap.Entry<ReversibleBlockSnapshot> entry : beforeByPosition.long2ObjectEntrySet()) {
         if (!entry.getValue().sameContents(afterByPosition.get(entry.getLongKey()))) {
            changedPositions[changedIndex++] = entry.getLongKey();
         }
      }
      Arrays.sort(changedPositions);
      DenseShape dense = denseShape(changedPositions);

      Palette<BlockState> blockPalette = new Palette<>();
      Palette<FluidState> fluidPalette = new Palette<>();
      int size = changedPositions.length;
      long[] positions = dense == null ? changedPositions : null;
      int[] beforeBlockIds = new int[size];
      int[] afterBlockIds = new int[size];
      int[] beforeFluidIds = new int[size];
      int[] afterFluidIds = new int[size];
      boolean hasBeforeEntity = false;
      boolean hasAfterEntity = false;
      for (int index = 0; index < size; index++) {
         long packed = dense == null ? changedPositions[index] : dense.packedPosition(index);
         hasBeforeEntity |= beforeByPosition.get(packed).blockEntity() != null;
         hasAfterEntity |= afterByPosition.get(packed).blockEntity() != null;
      }
      BlockEntitySnapshot[] beforeEntities = hasBeforeEntity ? new BlockEntitySnapshot[size] : null;
      BlockEntitySnapshot[] afterEntities = hasAfterEntity ? new BlockEntitySnapshot[size] : null;
      Palette<BlockEntitySnapshot> beforeEntityPalette = new Palette<>();
      Palette<BlockEntitySnapshot> afterEntityPalette = new Palette<>();

      for (int index = 0; index < size; index++) {
         long packed = dense == null ? changedPositions[index] : dense.packedPosition(index);
         ReversibleBlockSnapshot before = beforeByPosition.get(packed);
         ReversibleBlockSnapshot after = afterByPosition.get(packed);
         beforeBlockIds[index] = blockPalette.id(before.state());
         afterBlockIds[index] = blockPalette.id(after.state());
         beforeFluidIds[index] = fluidPalette.id(before.fluidState());
         afterFluidIds[index] = fluidPalette.id(after.fluidState());
         if (beforeEntities != null) {
            beforeEntities[index] = beforeEntityPalette.intern(before.blockEntity());
         }
         if (afterEntities != null) {
            afterEntities[index] = afterEntityPalette.intern(after.blockEntity());
         }
      }

      int uniformAfterBlockId = uniformValue(afterBlockIds);
      int uniformAfterFluidId = uniformValue(afterFluidIds);
      int uniformBeforeBlockId = uniformValue(beforeBlockIds);
      int uniformBeforeFluidId = uniformValue(beforeFluidIds);
      int[] compactBeforeBlocks = uniformBeforeBlockId >= 0 ? null : beforeBlockIds;
      int[] compactBeforeFluids = uniformBeforeFluidId >= 0 ? null : beforeFluidIds;
      int[] compactAfterBlocks = uniformAfterBlockId >= 0 ? null : afterBlockIds;
      int[] compactAfterFluids = uniformAfterFluidId >= 0 ? null : afterFluidIds;
      int estimatedBytes = estimateBytes(
         positions,
         compactBeforeBlocks,
         compactAfterBlocks,
         compactBeforeFluids,
         compactAfterFluids,
         beforeEntities,
         afterEntities,
         blockPalette.values(),
         fluidPalette.values()
      );
      return Optional.of(new WorldChangeBatch(
          dimension,
          null,
          positions,
         dense == null ? 0 : dense.minX(),
         dense == null ? 0 : dense.minY(),
         dense == null ? 0 : dense.minZ(),
         dense == null ? 0 : dense.width(),
         dense == null ? 0 : dense.height(),
         dense == null ? 0 : dense.depth(),
         blockPalette.values().toArray(BlockState[]::new),
         fluidPalette.values().toArray(FluidState[]::new),
         compactBeforeBlocks,
         uniformBeforeBlockId,
         compactAfterBlocks,
         uniformAfterBlockId,
         compactBeforeFluids,
         uniformBeforeFluidId,
         compactAfterFluids,
         uniformAfterFluidId,
         beforeEntities,
         afterEntities,
         estimatedBytes
      ));
   }

   public ResourceKey<Level> dimension() {
      return this.dimension;
   }

   public UUID operationId() {
      return this.operationId;
   }

   /** Reuses the compressed arrays while attaching the owning operation ID. */
   public WorldChangeBatch withOperationId(UUID operationId) {
      if (operationId == null || operationId.equals(this.operationId)) {
         return this;
      }
      return new WorldChangeBatch(
         this.dimension,
         operationId,
         this.positions,
         this.denseMinX,
         this.denseMinY,
         this.denseMinZ,
         this.denseWidth,
         this.denseHeight,
         this.denseDepth,
         this.blockStates,
         this.fluidStates,
         this.beforeBlockIds,
         this.uniformBeforeBlockId,
         this.afterBlockIds,
         this.uniformAfterBlockId,
         this.beforeFluidIds,
         this.uniformBeforeFluidId,
         this.afterFluidIds,
         this.uniformAfterFluidId,
         this.beforeEntities,
         this.afterEntities,
         this.estimatedBytes
      );
   }

   /** Encodes the compact in-memory representation without expanding dense positions or uniform palette IDs. */
   public CompoundTag encode() throws IOException {
      validateForEncoding();
      CompoundTag root = new CompoundTag();
      root.putInt("Version", CODEC_VERSION);
      root.putString("Dimension", this.dimension.location().toString());
      if (this.operationId != null) {
         root.putUUID("OperationId", this.operationId);
      }
      root.putInt("Size", size());
      if (this.positions != null) {
         root.putLongArray("Positions", this.positions.clone());
      } else {
         CompoundTag dense = new CompoundTag();
         dense.putInt("MinX", this.denseMinX);
         dense.putInt("MinY", this.denseMinY);
         dense.putInt("MinZ", this.denseMinZ);
         dense.putInt("Width", this.denseWidth);
         dense.putInt("Height", this.denseHeight);
         dense.putInt("Depth", this.denseDepth);
         root.put("Dense", dense);
      }
      root.put("BlockPalette", encodeBlockPalette());
      root.put("FluidPalette", encodeFluidPalette());
      putIds(root, "BeforeBlock", this.beforeBlockIds, this.uniformBeforeBlockId);
      putIds(root, "AfterBlock", this.afterBlockIds, this.uniformAfterBlockId);
      putIds(root, "BeforeFluid", this.beforeFluidIds, this.uniformBeforeFluidId);
      putIds(root, "AfterFluid", this.afterFluidIds, this.uniformAfterFluidId);
      putEntities(root, "BeforeEntities", this.beforeEntities);
      putEntities(root, "AfterEntities", this.afterEntities);
      return root;
   }

   /** Decodes and validates an untrusted history payload against the current registries. */
   public static WorldChangeBatch decode(HolderLookup.Provider registries, CompoundTag root) throws IOException {
      if (registries == null || root == null) {
         throw new IOException("Missing world change batch data");
      }
      requireInt(root, "Version");
      if (root.getInt("Version") != CODEC_VERSION) {
         throw new IOException("Unsupported world change batch version: " + root.getInt("Version"));
      }
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
      int estimatedBytes = estimateBytes(
         positions.positions(), beforeBlocks.ids(), afterBlocks.ids(), beforeFluids.ids(), afterFluids.ids(),
         beforeEntities, afterEntities, Arrays.asList(blockStates), Arrays.asList(fluidStates)
      );
      return new WorldChangeBatch(
         dimension, operationId, positions.positions(), positions.minX(), positions.minY(), positions.minZ(),
         positions.width(), positions.height(), positions.depth(), blockStates, fluidStates,
         beforeBlocks.ids(), beforeBlocks.uniformId(), afterBlocks.ids(), afterBlocks.uniformId(),
         beforeFluids.ids(), beforeFluids.uniformId(), afterFluids.ids(), afterFluids.uniformId(),
         beforeEntities, afterEntities, estimatedBytes
      );
   }

   private void validateForEncoding() throws IOException {
      int size = size();
      if (this.dimension == null || size <= 0 || size > MAX_CODEC_ENTRIES) {
         throw new IOException("Invalid world change batch size or dimension");
      }
      validatePalette(this.blockStates, "block");
      validatePalette(this.fluidStates, "fluid");
      validateIds(this.beforeBlockIds, this.uniformBeforeBlockId, size, this.blockStates.length, "before block");
      validateIds(this.afterBlockIds, this.uniformAfterBlockId, size, this.blockStates.length, "after block");
      validateIds(this.beforeFluidIds, this.uniformBeforeFluidId, size, this.fluidStates.length, "before fluid");
      validateIds(this.afterFluidIds, this.uniformAfterFluidId, size, this.fluidStates.length, "after fluid");
      validateEntityArray(this.beforeEntities, size, "before");
      validateEntityArray(this.afterEntities, size, "after");
   }

   private ListTag encodeBlockPalette() throws IOException {
      ListTag palette = new ListTag();
      for (BlockState state : this.blockStates) {
         if (BuiltInRegistries.BLOCK.getKey(state.getBlock()) == null) {
            throw new IOException("World change batch contains an unregistered block");
         }
         palette.add(NbtUtils.writeBlockState(state));
      }
      return palette;
   }

   private ListTag encodeFluidPalette() throws IOException {
      ListTag palette = new ListTag();
      for (FluidState state : this.fluidStates) {
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
      var blocks = registries.lookupOrThrow(Registries.BLOCK);
      BlockState[] palette = new BlockState[encoded.size()];
      for (int index = 0; index < encoded.size(); index++) {
         CompoundTag stateTag = encoded.getCompound(index);
         String blockName = requireString(stateTag, "Name");
         ResourceLocation blockId = ResourceLocation.tryParse(blockName);
         if (blockId == null) {
            throw new IOException("Unknown block in world change batch: " + blockName);
         }
         var block = blocks.get(ResourceKey.create(Registries.BLOCK, blockId))
            .orElseThrow(() -> new IOException("Unknown block in world change batch: " + blockName)).value();
         BlockState state = block.defaultBlockState();
         if (stateTag.contains("Properties")) {
            if (!stateTag.contains("Properties", Tag.TAG_COMPOUND)) {
               throw new IOException("Invalid block properties in world change batch");
            }
            CompoundTag properties = stateTag.getCompound("Properties");
            for (String propertyName : properties.getAllKeys()) {
               if (!properties.contains(propertyName, Tag.TAG_STRING)) {
                  throw new IOException("Invalid block property value: " + propertyName);
               }
               Property<?> property = block.getStateDefinition().getProperty(propertyName);
               if (property == null) {
                  throw new IOException("Unknown block property: " + propertyName);
               }
               state = setProperty(state, property, properties.getString(propertyName));
            }
         }
         palette[index] = state;
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

   public int size() {
      return this.positions != null ? this.positions.length : this.denseWidth * this.denseHeight * this.denseDepth;
   }

   public int estimatedBytes() {
      return this.estimatedBytes;
   }

   public BlockPos position(int index) {
      if (this.positions != null) {
         return BlockPos.of(this.positions[index]);
      }
      // Entries are ordered x -> y -> z in denseShape(). The contiguous
      // slice for one x therefore contains height * depth cells.
      int plane = this.denseHeight * this.denseDepth;
      int x = index / plane;
      int remainder = index % plane;
      int y = remainder / this.denseDepth;
      int z = remainder % this.denseDepth;
      return new BlockPos(this.denseMinX + x, this.denseMinY + y, this.denseMinZ + z);
   }

   public boolean matchesExpected(ServerLevel level, int index, boolean undo) {
      if (level == null || !this.dimension.equals(level.dimension())) {
         return false;
      }
      // Undo expects the operation's after-state; redo expects its before-state.
      return matches(level, index, undo);
   }

   /**
    * Returns 1 when the cell is in the expected source state, 2 when it is
    * already in the target state, and 0 when it conflicts. This avoids a
    * second full block-entity read in the common expected-state path.
    */
   public int match(ServerLevel level, int index, boolean undo) {
      if (level == null || !this.dimension.equals(level.dimension())) {
         return 0;
      }
      if (matches(level, index, undo)) {
         return 1;
      }
      return matches(level, index, !undo) ? 2 : 0;
   }

   public boolean matchesTarget(ServerLevel level, int index, boolean undo) {
      if (level == null || !this.dimension.equals(level.dimension())) {
         return false;
      }
      return matches(level, index, !undo);
   }

   /** Applies either the before (undo) or after (redo) side. */
   public boolean apply(ServerLevel level, int index, boolean undo, int flags) {
      if (level == null || !this.dimension.equals(level.dimension())) {
         return false;
      }
      return snapshot(index, undo ? false : true).placeAt(level, position(index), flags);
   }

   /** Lazy view of the live source side expected before an undo/redo starts. */
   List<ReversibleBlockSnapshot> sourceSnapshots(boolean undo) {
      return snapshots(undo);
   }

   List<ReversibleBlockSnapshot> targetSnapshots(boolean undo) {
      return snapshots(!undo);
   }

   private List<ReversibleBlockSnapshot> snapshots(boolean after) {
      return new AbstractList<>() {
         @Override
         public ReversibleBlockSnapshot get(int index) {
            if (index < 0 || index >= WorldChangeBatch.this.size()) {
               throw new IndexOutOfBoundsException(index);
            }
            return WorldChangeBatch.this.snapshot(index, after);
         }

         @Override
         public int size() {
            return WorldChangeBatch.this.size();
         }
      };
   }

   private ReversibleBlockSnapshot snapshot(int index, boolean after) {
      BlockState state = this.blockStates[after ? afterBlockId(index) : beforeBlockId(index)];
      FluidState fluid = this.fluidStates[after ? afterFluidId(index) : beforeFluidId(index)];
      BlockEntitySnapshot[] entities = after ? this.afterEntities : this.beforeEntities;
      return new ReversibleBlockSnapshot(position(index), state, fluid, entities == null ? null : entities[index]);
   }

   /** Compares a live cell without allocating a temporary snapshot object. */
   private boolean matches(ServerLevel level, int index, boolean after) {
      BlockPos pos = position(index);
      try {
         BlockState expectedState = this.blockStates[after ? afterBlockId(index) : beforeBlockId(index)];
         FluidState expectedFluid = this.fluidStates[after ? afterFluidId(index) : beforeFluidId(index)];
         if (!level.getBlockState(pos).equals(expectedState) || !level.getFluidState(pos).equals(expectedFluid)) {
            return false;
         }
         BlockEntitySnapshot expectedEntity = (after ? this.afterEntities : this.beforeEntities) == null
            ? null
            : (after ? this.afterEntities : this.beforeEntities)[index];
         if (expectedEntity == null) {
            return level.getBlockEntity(pos) == null;
         }
         return expectedEntity.matches(level.getBlockEntity(pos), level, pos);
      } catch (RuntimeException exception) {
         return false;
      }
   }

   private int afterBlockId(int index) {
      return this.afterBlockIds == null ? this.uniformAfterBlockId : this.afterBlockIds[index];
   }

   private int beforeBlockId(int index) {
      return this.beforeBlockIds == null ? this.uniformBeforeBlockId : this.beforeBlockIds[index];
   }

   private int afterFluidId(int index) {
      return this.afterFluidIds == null ? this.uniformAfterFluidId : this.afterFluidIds[index];
   }

   private int beforeFluidId(int index) {
      return this.beforeFluidIds == null ? this.uniformBeforeFluidId : this.beforeFluidIds[index];
   }

   private static int uniformValue(int[] values) {
      if (values.length == 0) {
         return -1;
      }
      int first = values[0];
      for (int value : values) {
         if (value != first) {
            return -1;
         }
      }
      return first;
   }

   private static int estimateBytes(
      long[] positions,
      int[] beforeBlocks,
      int[] afterBlocks,
      int[] beforeFluids,
      int[] afterFluids,
      BlockEntitySnapshot[] beforeEntities,
      BlockEntitySnapshot[] afterEntities,
      List<?> blockPalette,
      List<?> fluidPalette
   ) {
      long bytes = 128L
         + (long)(positions == null ? 0 : positions.length) * Long.BYTES
         + (long)(beforeBlocks == null ? 0 : beforeBlocks.length) * Integer.BYTES
         + (long)(beforeFluids == null ? 0 : beforeFluids.length) * Integer.BYTES
         + (long)blockPalette.size() * 16L
         + (long)fluidPalette.size() * 16L;
      if (afterBlocks != null) {
         bytes += (long)afterBlocks.length * Integer.BYTES;
      }
      if (afterFluids != null) {
         bytes += (long)afterFluids.length * Integer.BYTES;
      }
      if (beforeEntities != null) {
         bytes += entityBytes(beforeEntities);
      }
      if (afterEntities != null) {
         bytes += entityBytes(afterEntities);
      }
      return (int)Math.min(Integer.MAX_VALUE, bytes);
   }

   private static DenseShape denseShape(long[] positions) {
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      int maxZ = Integer.MIN_VALUE;
      for (long packed : positions) {
         BlockPos pos = BlockPos.of(packed);
         minX = Math.min(minX, pos.getX());
         minY = Math.min(minY, pos.getY());
         minZ = Math.min(minZ, pos.getZ());
         maxX = Math.max(maxX, pos.getX());
         maxY = Math.max(maxY, pos.getY());
         maxZ = Math.max(maxZ, pos.getZ());
      }
      long width = (long)maxX - minX + 1L;
      long height = (long)maxY - minY + 1L;
      long depth = (long)maxZ - minZ + 1L;
      if (width <= 0L || height <= 0L || depth <= 0L
         || width > Long.MAX_VALUE / height
         || width * height > Long.MAX_VALUE / depth) {
         return null;
      }
      long volume = width * height * depth;
      if (width <= 0L || height <= 0L || depth <= 0L || volume != positions.length || volume > Integer.MAX_VALUE) {
         return null;
      }
      return new DenseShape(minX, minY, minZ, (int)width, (int)height, (int)depth);
   }

   private static long entityBytes(BlockEntitySnapshot[] entities) {
      long bytes = (long)entities.length * 8L;
      for (BlockEntitySnapshot entity : entities) {
         if (entity != null) {
            bytes += entity.estimatedBytes();
         }
      }
      return bytes;
   }

   private record DenseShape(int minX, int minY, int minZ, int width, int height, int depth) {
      long packedPosition(int index) {
         int plane = this.height * this.depth;
         int x = index / plane;
         int remainder = index % plane;
         int y = remainder / this.depth;
         int z = remainder % this.depth;
         return BlockPos.asLong(this.minX + x, this.minY + y, this.minZ + z);
      }
   }

   @FunctionalInterface
   private interface SnapshotLookup {
      ReversibleBlockSnapshot get(long packedPosition);
   }

   private static final class Palette<T> {
      private final List<T> values = new ArrayList<>();
      private final Map<T, Integer> ids = new HashMap<>();

      int id(T value) {
         Integer existing = this.ids.get(value);
         if (existing != null) {
            return existing;
         }
         int id = this.values.size();
         this.values.add(value);
         this.ids.put(value, id);
         return id;
      }

      T intern(T value) {
         if (value == null) {
            return null;
         }
         Integer existing = this.ids.get(value);
         if (existing != null) {
            return this.values.get(existing);
         }
         this.ids.put(value, this.values.size());
         this.values.add(value);
         return value;
      }

      List<T> values() {
         return this.values;
      }
   }
}
