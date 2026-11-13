package io.github.fastformer.server.recovery;

import io.github.fastformer.common.persistence.BlockStatePaletteCodec;
import io.github.fastformer.common.persistence.MinecraftDataVersion;
import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import io.github.fastformer.core.persist.Envelope;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Strict current-format palette, correction and decode-budget rules; no world mutation. */
final class RecoveryJournalCodec {
   static final int VERSION = 2;
   static final long MAX_DECOMPRESSED_BYTES = 512L * 1024L * 1024L;
   private static final long MINIMUM_HEAP_RESERVE = 256L * 1024L * 1024L;

   private RecoveryJournalCodec() {}

   static Envelope.Format fileFormat(int magic) {
      return new Envelope.Format(magic, 1, MinecraftDataVersion.current());
   }

   static Envelope.Format correctionFormat() { return fileFormat(0x46465243); } // FFRC

   static CompoundTag readCorrection(Path file) throws IOException {
      return NbtEnvelopeFile.read(file, correctionFormat(), recoveryDecodeLimit());
   }

   static List<EncodedSnapshotKey> decodeEncodedPalette(CompoundTag root, String prefix) throws IOException {
      ListTag paletteTag = root.getList(prefix + "Palette", Tag.TAG_COMPOUND);
      if (paletteTag.isEmpty()) {
         throw new IOException("Missing recovery " + prefix + " palette");
      }
      List<EncodedSnapshotKey> palette = new ArrayList<>(paletteTag.size());
      for (int i = 0; i < paletteTag.size(); i++) {
         CompoundTag entry = paletteTag.getCompound(i);
         if (!entry.contains("State", Tag.TAG_COMPOUND)) {
            throw new IOException("Missing encoded recovery block state");
         }
         CompoundTag state = entry.getCompound("State").copy();
         if (ResourceLocation.tryParse(state.getString("Name")) == null) {
            throw new IOException("Invalid encoded recovery block state");
         }
         CompoundTag blockEntity = entry.contains("BlockEntity", Tag.TAG_COMPOUND)
            ? entry.getCompound("BlockEntity").copy()
            : null;
         palette.add(new EncodedSnapshotKey(state, blockEntity));
      }
      return palette;
   }

   static EncodedSnapshotKey encoded(ReversibleBlockSnapshot snapshot) {
      BlockEntitySnapshot normalized = normalized(snapshot.blockEntity());
      return new EncodedSnapshotKey(
         NbtUtils.writeBlockState(snapshot.state()),
         normalized == null ? null : normalized.data()
      );
   }

   static EncodedSnapshotKey encodedSnapshot(
      List<EncodedSnapshotKey> palette,
      int paletteId
   ) throws IOException {
      if (paletteId < 0 || paletteId >= palette.size()) {
         throw new IOException("Invalid encoded recovery palette id");
      }
      return palette.get(paletteId);
   }

   static DecodedCorrections decodeCorrections(
      ServerLevel level,
      UUID operationId,
      RecoveryJournalSegments.SegmentSet base,
      Path correction,
      ResourceKey<Level> expectedDimension
   ) throws IOException {
      if (!Files.exists(correction)) {
         return null;
      }
      CompoundTag root = readCorrection(correction);
      validateHeader(root, expectedDimension);
      if (!operationId.toString().equals(root.getString("OperationId"))
         || !root.contains("BaseCount", Tag.TAG_INT) || root.getInt("BaseCount") != base.count()
         || !root.contains("BaseDigest", Tag.TAG_LONG) || root.getLong("BaseDigest") != base.digest()) {
         throw new IOException("Recovery correction does not match its base journal");
      }
      long[] positions = root.getLongArray("Positions");
      if (positions.length == 0) {
         throw new IOException("Empty recovery correction");
      }
      List<SnapshotKey> palette = decodePalette(level, root, "After");
      int[] ids = paletteIds(root, "After", positions.length);
      return new DecodedCorrections(positions, palette, ids, root.getInt("AfterUniformPaletteId"));
   }

   static void validateHeader(CompoundTag root, ResourceKey<Level> expectedDimension) throws IOException {
      if (root.getInt("Version") != VERSION) {
         throw new IOException("Unsupported journal version " + root.getInt("Version"));
      }
      MinecraftDataVersion.read(root);
      ResourceLocation dimensionId = ResourceLocation.tryParse(root.getString("Dimension"));
      if (dimensionId == null || !expectedDimension.location().equals(dimensionId)) {
         throw new IOException("Recovery journal dimension does not match its operation");
      }
   }

   static CompoundTag encodePrepared(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) throws IOException {
      PaletteLayout beforeLayout = paletteLayout(before);
      PaletteLayout afterLayout = paletteLayout(after, beforeLayout.positions());
      long[] positions = beforeLayout.positions();

      CompoundTag root = new CompoundTag();
      root.putInt("Version", VERSION);
      root.putString("Dimension", dimension.location().toString());
      root.putLongArray("Positions", positions);
      putPalette(root, "Before", beforeLayout);
      putPalette(root, "After", afterLayout);
      return NbtUtils.addCurrentDataVersion(root);
   }

   static void putPalette(CompoundTag root, String prefix, PaletteLayout layout) {
      if (layout.palette().size() > 1) {
         root.putIntArray(prefix + "PaletteIds", layout.paletteIds());
      } else {
         root.putInt(prefix + "UniformPaletteId", 0);
      }
      ListTag paletteTag = new ListTag();
      for (SnapshotKey key : layout.palette()) {
         CompoundTag entry = new CompoundTag();
         entry.put("State", NbtUtils.writeBlockState(key.state()));
         if (key.blockEntity() != null) {
            entry.put("BlockEntity", key.blockEntity().data().copy());
         }
         paletteTag.add(entry);
      }
      root.put(prefix + "Palette", paletteTag);
   }

   static PaletteLayout paletteLayout(Collection<ReversibleBlockSnapshot> snapshots) throws IOException {
      return paletteLayout(snapshots, null);
   }

   static PaletteLayout paletteLayout(
      Collection<ReversibleBlockSnapshot> snapshots,
      long[] expectedPositions
   ) throws IOException {
      if (snapshots == null || snapshots.isEmpty()) {
         throw new IOException("Recovery journal has no snapshots");
      }
      if (expectedPositions != null && expectedPositions.length != snapshots.size()) {
         throw new IOException("Recovery before/after sizes are not aligned");
      }

      // Callers already provide one original snapshot per position. Avoid a
      // second multi-million-entry map while encoding a large structure.
      long[] positions = expectedPositions == null ? new long[snapshots.size()] : expectedPositions;
      int[] paletteIds = null;
      List<SnapshotKey> palette = new ArrayList<>();
      Map<SnapshotKey, Integer> paletteIndex = new HashMap<>();
      int index = 0;
      for (ReversibleBlockSnapshot snapshot : snapshots) {
         if (snapshot == null) {
            throw new IOException("Recovery journal contains a null snapshot");
         }
         SnapshotKey key = new SnapshotKey(snapshot.state(), normalized(snapshot.blockEntity()));
         int id = paletteIndex.computeIfAbsent(key, ignored -> {
            palette.add(key);
            return palette.size() - 1;
         });
         long packedPos = snapshot.pos().asLong();
         if (expectedPositions == null) {
            positions[index] = packedPos;
         } else if (positions[index] != packedPos) {
            throw new IOException("Recovery before/after positions are not aligned");
         }
         if (id != 0 && paletteIds == null) {
            paletteIds = new int[snapshots.size()];
         }
         if (paletteIds != null) {
            paletteIds[index] = id;
         }
         index++;
      }

      return new PaletteLayout(positions, paletteIds, List.copyOf(palette));
   }

   static BlockEntitySnapshot normalized(BlockEntitySnapshot snapshot) {
      if (snapshot == null) {
         return null;
      }
      CompoundTag data = snapshot.data().copy();
      data.remove("x");
      data.remove("y");
      data.remove("z");
      return new BlockEntitySnapshot(data);
   }

   static DecodedJournal decode(ServerLevel level, CompoundTag root) throws IOException {
      validateHeader(root, level.dimension());
      long[] positions = root.getLongArray("Positions");
      if (positions.length == 0) {
         throw new IOException("Invalid recovery journal arrays");
      }
      List<SnapshotKey> beforePalette = decodePalette(level, root, "Before");
      List<SnapshotKey> afterPalette = decodePalette(level, root, "After");
      int[] beforeIds = paletteIds(root, "Before", positions.length);
      int[] afterIds = paletteIds(root, "After", positions.length);
      return new DecodedJournal(
         positions,
         beforePalette,
         afterPalette,
         beforeIds,
         afterIds,
         root.getInt("BeforeUniformPaletteId"),
         root.getInt("AfterUniformPaletteId")
      );
   }

   static List<SnapshotKey> decodePalette(ServerLevel level, CompoundTag root, String prefix) throws IOException {
      ListTag paletteTag = root.getList(prefix + "Palette", Tag.TAG_COMPOUND);
      if (paletteTag.isEmpty()) {
         throw new IOException("Missing recovery " + prefix + " palette");
      }
      List<SnapshotKey> palette = new ArrayList<>(paletteTag.size());
      MinecraftDataVersion.read(root);
      for (int i = 0; i < paletteTag.size(); i++) {
         CompoundTag entry = paletteTag.getCompound(i);
         BlockState state = BlockStatePaletteCodec.decode(level.registryAccess(), entry.getCompound("State"));
         BlockEntitySnapshot blockEntity = entry.contains("BlockEntity", Tag.TAG_COMPOUND)
            ? new BlockEntitySnapshot(entry.getCompound("BlockEntity"))
            : null;
         palette.add(new SnapshotKey(state, blockEntity));
      }
      return palette;
   }

   static int[] paletteIds(CompoundTag root, String prefix, int size) throws IOException {
      String indices = prefix + "PaletteIds";
      String uniform = prefix + "UniformPaletteId";
      boolean hasIndices = root.contains(indices);
      boolean hasUniform = root.contains(uniform);
      if (hasIndices == hasUniform) {
         throw new IOException("Recovery palette requires exactly one index layout: " + prefix);
      }
      if (hasUniform) {
         if (!root.contains(uniform, Tag.TAG_INT) || root.getInt(uniform) < 0) {
            throw new IOException("Invalid recovery uniform palette id: " + prefix);
         }
         return null;
      }
      if (!root.contains(indices, Tag.TAG_INT_ARRAY)) {
         throw new IOException("Invalid recovery palette index array: " + prefix);
      }
      int[] ids = root.getIntArray(indices);
      if (ids.length != size) {
         throw new IOException("Invalid recovery palette ids");
      }
      return ids;
   }

   static ReversibleBlockSnapshot snapshot(
      BlockPos pos,
      List<SnapshotKey> palette,
      int paletteId
   ) throws IOException {
      if (paletteId < 0 || paletteId >= palette.size()) {
         throw new IOException("Invalid recovery palette id");
      }
      SnapshotKey key = palette.get(paletteId);
      return new ReversibleBlockSnapshot(pos, key.state(), key.state().getFluidState(), key.blockEntity());
   }

   static long recoveryDecodeLimit() {
      Runtime runtime = Runtime.getRuntime();
      return recoveryDecodeLimit(runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory());
   }

   static long recoveryDecodeLimit(long maxMemory, long totalMemory, long freeMemory) {
      long used = Math.max(0L, totalMemory - freeMemory);
      long available = Math.max(0L, maxMemory - used);
      long reserve = Math.max(MINIMUM_HEAP_RESERVE, maxMemory / 8L);
      long usable = Math.max(0L, available - reserve);
      return Math.max(1L, Math.min(MAX_DECOMPRESSED_BYTES, usable / 2L));
   }

   record SnapshotKey(BlockState state, BlockEntitySnapshot blockEntity) {
   }

   record EncodedSnapshotKey(CompoundTag state, CompoundTag blockEntity) {
   }

   record JournalPair(ReversibleBlockSnapshot before, ReversibleBlockSnapshot after) {
   }

   record DecodedJournal(
      long[] positions,
      List<SnapshotKey> beforePalette,
      List<SnapshotKey> afterPalette,
      int[] beforeIds,
      int[] afterIds,
      int uniformBefore,
      int uniformAfter
   ) {
      int size() {
         return this.positions.length;
      }

      JournalPair pair(int index) throws IOException {
         BlockPos pos = BlockPos.of(this.positions[index]);
         return new JournalPair(
            snapshot(pos, this.beforePalette, this.beforeIds == null ? this.uniformBefore : this.beforeIds[index]),
            snapshot(pos, this.afterPalette, this.afterIds == null ? this.uniformAfter : this.afterIds[index])
         );
      }
   }

   record DecodedCorrections(
      long[] positions,
      List<SnapshotKey> palette,
      int[] ids,
      int uniformId
   ) {
      int size() {
         return this.positions.length;
      }

      long position(int index) {
         return this.positions[index];
      }

      ReversibleBlockSnapshot snapshot(int index) throws IOException {
         BlockPos pos = BlockPos.of(this.positions[index]);
         int paletteId = this.ids == null ? this.uniformId : this.ids[index];
         return RecoveryJournalCodec.snapshot(pos, this.palette, paletteId);
      }
   }

   record PaletteLayout(long[] positions, int[] paletteIds, List<SnapshotKey> palette) {
   }

}
