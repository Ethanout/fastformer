package io.github.fastformer.fastplace;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
   private final ResourceKey<Level> dimension;
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
      List<ReversibleBlockSnapshot> ordered = new ArrayList<>(changes.size());
      var iterator = changes.descendingIterator();
      while (iterator.hasNext()) {
         ordered.add(iterator.next());
      }
      return capture(level, ordered);
   }

   /** Builds a batch from snapshots in oldest-to-newest order. */
   public static Optional<WorldChangeBatch> capture(ServerLevel level, Collection<ReversibleBlockSnapshot> changes) {
      if (level == null || changes == null || changes.isEmpty()) {
         return Optional.empty();
      }
      // LinkedHashMap gives deterministic output and removes repeated writes
      // to one position without retaining duplicate before states.
      Map<Long, ReversibleBlockSnapshot> beforeByPosition = new LinkedHashMap<>();
      for (ReversibleBlockSnapshot change : oldestFirst(changes)) {
         if (change != null) {
            beforeByPosition.putIfAbsent(change.pos().asLong(), change);
         }
      }
      if (beforeByPosition.isEmpty()) {
         return Optional.empty();
      }
      Map<Long, ReversibleBlockSnapshot> afterByPosition = new HashMap<>();
      for (Map.Entry<Long, ReversibleBlockSnapshot> entry : beforeByPosition.entrySet()) {
         BlockPos pos = BlockPos.of(entry.getKey());
         Optional<ReversibleBlockSnapshot> after = ReversibleBlockSnapshot.capture(level, pos);
         if (after.isEmpty()) {
            return Optional.empty();
         }
         afterByPosition.put(entry.getKey(), after.orElseThrow());
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
      Map<Long, ReversibleBlockSnapshot> beforeByPosition = new LinkedHashMap<>();
      for (ReversibleBlockSnapshot change : oldestFirst(changes)) {
         if (change != null) {
            beforeByPosition.putIfAbsent(change.pos().asLong(), change);
         }
      }
      for (Long position : beforeByPosition.keySet()) {
         ReversibleBlockSnapshot after = afterByPosition.get(position);
         if (after == null || after.pos().asLong() != position) {
            throw new IllegalStateException("Missing expected-after snapshot");
         }
      }
      return build(dimension, beforeByPosition, afterByPosition);
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
      if (dimension == null || afterByPosition == null || afterByPosition.isEmpty()) {
         return Optional.empty();
      }
      Map<Long, ReversibleBlockSnapshot> packed = new HashMap<>();
      for (Map.Entry<BlockPos, ReversibleBlockSnapshot> entry : afterByPosition.entrySet()) {
         if (entry.getKey() != null && entry.getValue() != null) {
            packed.put(entry.getKey().asLong(), entry.getValue());
         }
      }
      return capturePairs(dimension, changes, packed);
   }

   static Optional<WorldChangeBatch> fromPairsForTest(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> afterByPosition
   ) {
      if (dimension == null || changes == null || changes.isEmpty() || afterByPosition == null || afterByPosition.isEmpty()) {
         return Optional.empty();
      }
      Map<Long, ReversibleBlockSnapshot> beforeByPosition = new LinkedHashMap<>();
      Map<Long, ReversibleBlockSnapshot> packedAfter = new HashMap<>();
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
      return build(dimension, beforeByPosition, packedAfter);
   }

   private static List<ReversibleBlockSnapshot> oldestFirst(Collection<ReversibleBlockSnapshot> changes) {
      if (changes instanceof ArrayDeque<?> rawDeque) {
         @SuppressWarnings("unchecked")
         ArrayDeque<ReversibleBlockSnapshot> deque = (ArrayDeque<ReversibleBlockSnapshot>)rawDeque;
         List<ReversibleBlockSnapshot> ordered = new ArrayList<>(deque.size());
         var iterator = deque.descendingIterator();
         while (iterator.hasNext()) {
            ordered.add(iterator.next());
         }
         return ordered;
      }
      return new ArrayList<>(changes);
   }

   private static Optional<WorldChangeBatch> build(
      ResourceKey<Level> dimension,
      Map<Long, ReversibleBlockSnapshot> beforeByPosition,
      Map<Long, ReversibleBlockSnapshot> afterByPosition
   ) {
      List<Entry> entries = new ArrayList<>(beforeByPosition.size());
      for (Map.Entry<Long, ReversibleBlockSnapshot> entry : beforeByPosition.entrySet()) {
         ReversibleBlockSnapshot after = afterByPosition.get(entry.getKey());
         ReversibleBlockSnapshot before = entry.getValue();
         if (before == null
            || before.pos().asLong() != entry.getKey()
            || after == null
            || after.pos().asLong() != entry.getKey()) {
            return Optional.empty();
         }
         if (!before.sameContents(after)) {
            entries.add(new Entry(entry.getKey(), before, after));
         }
      }
      if (entries.isEmpty()) {
         return Optional.empty();
      }
      entries.sort(Comparator.comparingLong(Entry::packedPosition));

      DenseShape dense = denseShape(entries);
      if (dense != null) {
         entries = dense.entries();
      }

      Palette<BlockState> blockPalette = new Palette<>();
      Palette<FluidState> fluidPalette = new Palette<>();
      int size = entries.size();
      long[] positions = dense == null ? new long[size] : null;
      int[] beforeBlockIds = new int[size];
      int[] afterBlockIds = new int[size];
      int[] beforeFluidIds = new int[size];
      int[] afterFluidIds = new int[size];
      boolean hasBeforeEntity = entries.stream().anyMatch(entry -> entry.before().blockEntity() != null);
      boolean hasAfterEntity = entries.stream().anyMatch(entry -> entry.after().blockEntity() != null);
      BlockEntitySnapshot[] beforeEntities = hasBeforeEntity ? new BlockEntitySnapshot[size] : null;
      BlockEntitySnapshot[] afterEntities = hasAfterEntity ? new BlockEntitySnapshot[size] : null;
      Palette<BlockEntitySnapshot> beforeEntityPalette = new Palette<>();
      Palette<BlockEntitySnapshot> afterEntityPalette = new Palette<>();

      for (int index = 0; index < size; index++) {
         Entry entry = entries.get(index);
         if (positions != null) {
            positions[index] = entry.packedPosition();
         }
         beforeBlockIds[index] = blockPalette.id(entry.before().state());
         afterBlockIds[index] = blockPalette.id(entry.after().state());
         beforeFluidIds[index] = fluidPalette.id(entry.before().fluidState());
         afterFluidIds[index] = fluidPalette.id(entry.after().fluidState());
         if (beforeEntities != null) {
            beforeEntities[index] = beforeEntityPalette.intern(entry.before().blockEntity());
         }
         if (afterEntities != null) {
            afterEntities[index] = afterEntityPalette.intern(entry.after().blockEntity());
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

   private static DenseShape denseShape(List<Entry> entries) {
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      int maxZ = Integer.MIN_VALUE;
      for (Entry entry : entries) {
         BlockPos pos = BlockPos.of(entry.packedPosition());
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
      if (width <= 0L || height <= 0L || depth <= 0L || volume != entries.size() || volume > Integer.MAX_VALUE) {
         return null;
      }
      entries.sort((left, right) -> {
         BlockPos a = BlockPos.of(left.packedPosition());
         BlockPos b = BlockPos.of(right.packedPosition());
         int compare = Integer.compare(a.getX(), b.getX());
         if (compare == 0) {
            compare = Integer.compare(a.getY(), b.getY());
         }
         return compare == 0 ? Integer.compare(a.getZ(), b.getZ()) : compare;
      });
      int index = 0;
      for (int x = 0; x < width; x++) {
         for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
               Entry entry = entries.get(index++);
               BlockPos actual = BlockPos.of(entry.packedPosition());
               if (actual.getX() != minX + x || actual.getY() != minY + y || actual.getZ() != minZ + z) {
                  return null;
               }
            }
         }
      }
      return new DenseShape(
         minX,
         minY,
         minZ,
         (int)width,
         (int)height,
         (int)depth,
         entries
      );
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

   private record Entry(long packedPosition, ReversibleBlockSnapshot before, ReversibleBlockSnapshot after) {
   }

   private record DenseShape(int minX, int minY, int minZ, int width, int height, int depth, List<Entry> entries) {
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
