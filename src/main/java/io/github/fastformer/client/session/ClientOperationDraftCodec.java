package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.SelectionBaseline;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Versioned, bounded NBT codec for a client operation draft. */
public final class ClientOperationDraftCodec {
   private static final int MAX_PARTS = 16_384;
   private static final int MAX_POINTS = 65_536;
   private static final int MAX_BLOCKS = 2_000_000;

   private ClientOperationDraftCodec() {
   }

   public static CompoundTag encode(ClientOperationDraft draft) {
      CompoundTag root = new CompoundTag();
      root.putInt("Version", draft.version());
      if (draft.identity() != null) {
         root.put("Identity", encodeIdentity(draft.identity()));
      }
      if (draft.origin() != null) {
         root.putString("Origin", draft.origin().name());
      }
      if (draft.submissionId() != null) {
         // The submission that wrote this draft. A later result may clear only this
         // draft, never a newer one that the player built in the same scope.
         root.putUUID("SubmissionId", draft.submissionId());
      }
      root.put("Workspace", encodeWorkspace(draft.workspace()));
      root.put("Selection", encodeSelectionDraft(draft.selection()));
      return root;
   }

   public static ClientOperationDraft decode(CompoundTag root, HolderGetter<Block> blocks) throws IOException {
      if (root == null) {
         throw new IOException("Missing client operation draft root");
      }
      if (!root.contains("Version", Tag.TAG_INT)) {
         throw new IOException("Client operation draft has no version");
      }
      if (root.getInt("Version") != ClientOperationDraft.CURRENT_VERSION) {
         throw new VersionMismatchException("Unsupported client operation draft version");
      }
      // An absent identity is readable: a local-only draft has none by design. An absent
      // Origin means an older writer, which always produced a server-confirmed draft.
      OperationSubmissionOrigin origin = root.contains("Origin", Tag.TAG_STRING)
         ? enumValue(OperationSubmissionOrigin.class, root.getString("Origin"), "submission origin") : null;
      OperationDraftIdentity identity = root.contains("Identity", Tag.TAG_COMPOUND)
         ? decodeIdentity(root.getCompound("Identity")) : null;
      // An absent submission id means a draft that no submission wrote, which is every
      // draft of an older client version. Such a draft belongs to the player.
      UUID submissionId = root.contains("SubmissionId", Tag.TAG_INT_ARRAY)
         ? root.getUUID("SubmissionId") : null;
      try {
         return new ClientOperationDraft(
            root.getInt("Version"),
            identity,
            decodeWorkspace(requiredCompound(root, "Workspace"), blocks),
            decodeSelectionDraft(requiredCompound(root, "Selection")),
            origin,
            submissionId
         );
      } catch (IllegalArgumentException exception) {
         throw new IOException("Client operation draft is inconsistent", exception);
      }
   }

   /**
    * Reads only the submission id of a durable draft.
    *
    * <p>A block registry is not available on every path, and the full decode needs one.
    * This reader answers the one question that a result needs: which submission wrote
    * this draft. A draft without the key belongs to no submission.</p>
    *
    * @return the recorded submission id, or null when the file names none
    */
   public static UUID readSubmissionId(CompoundTag root) {
      if (root == null || !root.contains("SubmissionId", Tag.TAG_INT_ARRAY)) {
         return null;
      }
      return root.getUUID("SubmissionId");
   }

   /**
    * Raised when a durable draft belongs to another format version.
    *
    * <p>Callers must keep the file. A version mismatch is not corruption, and a later
    * client version may read the same file again.
    */
   public static final class VersionMismatchException extends IOException {
      public VersionMismatchException(String message) {
         super(message);
      }
   }

   /** Encodes one selection identity. Shared with the submission receipt format. */
   static CompoundTag encodeIdentity(OperationDraftIdentity identity) {
      CompoundTag tag = new CompoundTag();
      tag.putString("Mode", identity.mode().name());
      putBlockPositions(tag, "Points", identity.points());
      tag.putInt("PrismBasePointCount", identity.prismBasePointCount());
      putOptionalBlockPos(tag, "MinOffset", identity.minOffset());
      putOptionalBlockPos(tag, "MaxOffset", identity.maxOffset());
      tag.putDouble("HullInflation", identity.hullInflation());
      putOptionalBlockPos(tag, "SelectionMin", identity.selectionMin());
      putOptionalBlockPos(tag, "SelectionMax", identity.selectionMax());
      return tag;
   }

   /** Decodes one selection identity. Shared with the submission receipt format. */
   static OperationDraftIdentity decodeIdentity(CompoundTag tag) throws IOException {
      return new OperationDraftIdentity(
         enumValue(OperationSelectionMode.class, tag.getString("Mode"), "selection mode"),
         readBlockPositions(tag, "Points"),
         tag.getInt("PrismBasePointCount"),
         readOptionalBlockPos(tag, "MinOffset"),
         readOptionalBlockPos(tag, "MaxOffset"),
         tag.getDouble("HullInflation"),
         readOptionalBlockPos(tag, "SelectionMin"),
         readOptionalBlockPos(tag, "SelectionMax")
      );
   }

   private static CompoundTag encodeWorkspace(ClientOperationWorkspace.DraftState workspace) {
      if (workspace.parts().size() > MAX_PARTS) {
         throw new IllegalArgumentException("Client operation draft has too many parts");
      }
      CompoundTag tag = new CompoundTag();
      tag.putIntArray("Selected", workspace.selectedIds().stream().mapToInt(Integer::intValue).toArray());
      tag.putInt("Active", workspace.activeId());
      ListTag parts = new ListTag();
      BlockCounter counter = new BlockCounter();
      for (ClientSelectionPart part : workspace.parts()) {
         parts.add(encodePart(part, counter));
      }
      tag.put("Parts", parts);
      return tag;
   }

   private static ClientOperationWorkspace.DraftState decodeWorkspace(
      CompoundTag tag, HolderGetter<Block> blocks
   ) throws IOException {
      ListTag partTags = tag.getList("Parts", Tag.TAG_COMPOUND);
      if (partTags.size() > MAX_PARTS) {
         throw new IOException("Client operation draft has too many parts");
      }
      ArrayList<ClientSelectionPart> parts = new ArrayList<>(partTags.size());
      LinkedHashSet<Integer> ids = new LinkedHashSet<>();
      BlockCounter counter = new BlockCounter();
      for (int index = 0; index < partTags.size(); index++) {
         ClientSelectionPart part = decodePart(partTags.getCompound(index), blocks, counter);
         if (part.id() <= 0 || !ids.add(part.id())) {
            throw new IOException("Client operation draft has invalid part ids");
         }
         parts.add(part);
      }
      Set<Integer> selected = new LinkedHashSet<>();
      for (int id : tag.getIntArray("Selected")) {
         if (ids.contains(id)) {
            selected.add(id);
         }
      }
      return new ClientOperationWorkspace.DraftState(parts, selected, tag.getInt("Active"));
   }

   private static CompoundTag encodePart(ClientSelectionPart part, BlockCounter counter) {
      CompoundTag tag = new CompoundTag();
      tag.putInt("Id", part.id());
      tag.putString("Source", part.source().name());
      tag.putBoolean("PendingDelete", part.pendingDelete());
      tag.putString("Editability", part.editability().name());
      if (part.selection() != null) tag.put("Selection", encodeSelection(part.selection()));
      tag.put("Transform", encodeTransform(part.transform()));
      tag.put("Blocks", encodeBlocks(part.blocks(), counter));
      boolean sourceMatchesBlocks = part.sourceSnapshot().equals(part.blocks());
      tag.putBoolean("SourceMatchesBlocks", sourceMatchesBlocks);
      if (!sourceMatchesBlocks) tag.put("SourceSnapshot", encodeBlocks(part.sourceSnapshot(), counter));
      if (part.baseline() != null) {
         CompoundTag baseline = new CompoundTag();
         if (part.baseline().selection() != null) baseline.put("Selection", encodeSelection(part.baseline().selection()));
         baseline.put("Transform", encodeTransform(part.baseline().transform()));
         boolean sourceMatchesPart = part.baseline().sourceSnapshot().equals(part.sourceSnapshot());
         baseline.putBoolean("SourceMatchesPart", sourceMatchesPart);
         if (!sourceMatchesPart) baseline.put("SourceSnapshot", encodeBlocks(part.baseline().sourceSnapshot(), counter));
         tag.put("Baseline", baseline);
      }
      return tag;
   }

   private static ClientSelectionPart decodePart(
      CompoundTag tag, HolderGetter<Block> blocks, BlockCounter counter
   ) throws IOException {
      Map<BlockPos, ClientBlockSnapshot> values = decodeBlocks(requiredList(tag, "Blocks"), blocks, counter);
      Map<BlockPos, ClientBlockSnapshot> source = tag.getBoolean("SourceMatchesBlocks")
         ? values : decodeBlocks(requiredList(tag, "SourceSnapshot"), blocks, counter);
      OperationSelectionVolume selection = tag.contains("Selection", Tag.TAG_COMPOUND)
         ? decodeSelection(tag.getCompound("Selection")) : null;
      WorkspaceTransform transform = decodeTransform(requiredCompound(tag, "Transform"));
      SelectionBaseline baseline = null;
      if (tag.contains("Baseline", Tag.TAG_COMPOUND)) {
         CompoundTag baselineTag = tag.getCompound("Baseline");
         OperationSelectionVolume baselineSelection = baselineTag.contains("Selection", Tag.TAG_COMPOUND)
            ? decodeSelection(baselineTag.getCompound("Selection")) : null;
         Map<BlockPos, ClientBlockSnapshot> baselineSource = baselineTag.getBoolean("SourceMatchesPart")
            ? source : decodeBlocks(requiredList(baselineTag, "SourceSnapshot"), blocks, counter);
         baseline = new SelectionBaseline(
            baselineSelection, decodeTransform(requiredCompound(baselineTag, "Transform")), baselineSource
         );
      }
      try {
         return new ClientSelectionPart(
            tag.getInt("Id"),
            enumValue(ClientSelectionPart.Source.class, tag.getString("Source"), "part source"),
            selection,
            values,
            transform,
            tag.getBoolean("PendingDelete"),
            source,
            baseline,
            enumValue(ClientSelectionPart.Editability.class, tag.getString("Editability"), "part editability")
         );
      } catch (IllegalArgumentException exception) {
         throw new IOException("Invalid client operation draft part", exception);
      }
   }

   private static CompoundTag encodeSelectionDraft(ClientSelectionSession.DraftState selection) {
      CompoundTag tag = new CompoundTag();
      tag.putString("Mode", selection.selectionMode().name());
      putBlockPositions(tag, "Points", selection.points());
      tag.putInt("PrismBasePointCount", selection.prismBaseCount());
      putOptionalBlockPos(tag, "Min", selection.minPoint());
      putOptionalBlockPos(tag, "Max", selection.maxPoint());
      return tag;
   }

   private static ClientSelectionSession.DraftState decodeSelectionDraft(CompoundTag tag) throws IOException {
      return new ClientSelectionSession.DraftState(
         enumValue(OperationSelectionMode.class, tag.getString("Mode"), "selection mode"),
         readBlockPositions(tag, "Points"),
         tag.getInt("PrismBasePointCount"),
         readOptionalBlockPos(tag, "Min"),
         readOptionalBlockPos(tag, "Max")
      );
   }

   private static CompoundTag encodeSelection(OperationSelectionVolume selection) {
      CompoundTag tag = new CompoundTag();
      tag.putString("Mode", selection.mode().name());
      putAabb(tag, selection.bounds());
      tag.putInt("HullInflation", selection.hullInflation());
      putOptionalBlockPos(tag, "Point1", selection.point1());
      putOptionalBlockPos(tag, "Point2", selection.point2());
      if (selection.prism() != null) {
         CompoundTag prism = new CompoundTag();
         prism.put("Base", encodeVec3List(selection.prism().base()));
         prism.put("Extrusion", encodeVec3(selection.prism().extrusion()));
         tag.put("Prism", prism);
      }
      ListTag faces = new ListTag();
      for (OperationGeometry.HullFace face : selection.hullFaces()) {
         CompoundTag faceTag = new CompoundTag();
         faceTag.put("A", encodeVec3(face.a()));
         faceTag.put("B", encodeVec3(face.b()));
         faceTag.put("C", encodeVec3(face.c()));
         faceTag.put("Normal", encodeVec3(face.normal()));
         faces.add(faceTag);
      }
      tag.put("HullFaces", faces);
      return tag;
   }

   private static OperationSelectionVolume decodeSelection(CompoundTag tag) throws IOException {
      SelectionPrism prism = null;
      if (tag.contains("Prism", Tag.TAG_COMPOUND)) {
         CompoundTag prismTag = tag.getCompound("Prism");
         List<Vec3> base = decodeVec3List(requiredList(prismTag, "Base"));
         if (base.size() < 3) throw new IOException("Invalid operation draft prism");
         prism = new SelectionPrism(base, decodeVec3(requiredCompound(prismTag, "Extrusion")));
      }
      ListTag faceTags = tag.getList("HullFaces", Tag.TAG_COMPOUND);
      if (faceTags.size() > MAX_POINTS) throw new IOException("Operation draft has too many hull faces");
      ArrayList<OperationGeometry.HullFace> faces = new ArrayList<>(faceTags.size());
      for (int index = 0; index < faceTags.size(); index++) {
         CompoundTag face = faceTags.getCompound(index);
         faces.add(new OperationGeometry.HullFace(
            decodeVec3(requiredCompound(face, "A")),
            decodeVec3(requiredCompound(face, "B")),
            decodeVec3(requiredCompound(face, "C")),
            decodeVec3(requiredCompound(face, "Normal"))
         ));
      }
      return new OperationSelectionVolume(
         enumValue(OperationSelectionMode.class, tag.getString("Mode"), "selection mode"),
         readAabb(tag), prism, faces, tag.getInt("HullInflation"),
         readOptionalBlockPos(tag, "Point1"), readOptionalBlockPos(tag, "Point2")
      );
   }

   private static ListTag encodeBlocks(Map<BlockPos, ClientBlockSnapshot> blocks, BlockCounter counter) {
      ListTag list = new ListTag();
      for (Map.Entry<BlockPos, ClientBlockSnapshot> entry : blocks.entrySet()) {
         counter.add();
         CompoundTag block = new CompoundTag();
         block.putLong("Pos", entry.getKey().asLong());
         block.put("State", NbtUtils.writeBlockState(entry.getValue().state()));
         if (entry.getValue().blockEntity() != null) block.put("BlockEntity", entry.getValue().blockEntity());
         list.add(block);
      }
      return list;
   }

   private static Map<BlockPos, ClientBlockSnapshot> decodeBlocks(
      ListTag tags, HolderGetter<Block> blocks, BlockCounter counter
   ) throws IOException {
      if (!tags.isEmpty() && blocks == null) {
         throw new IOException("Operation draft block registry is unavailable");
      }
      LinkedHashMap<BlockPos, ClientBlockSnapshot> values = new LinkedHashMap<>();
      for (int index = 0; index < tags.size(); index++) {
         counter.addChecked();
         CompoundTag block = tags.getCompound(index);
         CompoundTag stateTag = requiredCompound(block, "State");
         ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
         if (blockId == null || blocks.get(ResourceKey.create(Registries.BLOCK, blockId)).isEmpty()) {
            throw new IOException("Unknown operation draft block: " + stateTag.getString("Name"));
         }
         BlockState state = NbtUtils.readBlockState(blocks, stateTag);
         CompoundTag blockEntity = block.contains("BlockEntity", Tag.TAG_COMPOUND)
            ? block.getCompound("BlockEntity") : null;
         if (values.put(BlockPos.of(block.getLong("Pos")), new ClientBlockSnapshot(state, blockEntity)) != null) {
            throw new IOException("Duplicate operation draft block position");
         }
      }
      return Map.copyOf(values);
   }

   private static CompoundTag encodeTransform(WorkspaceTransform transform) {
      CompoundTag tag = new CompoundTag();
      tag.put("Translation", encodeVec3(transform.translation()));
      tag.put("Rotation", encodeVec3(transform.rotation()));
      tag.put("Scale", encodeVec3(transform.scale()));
      tag.putLong("RepeatMin", transform.repeats().min().asLong());
      tag.putLong("RepeatMax", transform.repeats().max().asLong());
      tag.putLong("RepeatStride", transform.repeatStride().asLong());
      return tag;
   }

   private static WorkspaceTransform decodeTransform(CompoundTag tag) throws IOException {
      return new WorkspaceTransform(
         decodeVec3(requiredCompound(tag, "Translation")),
         decodeVec3(requiredCompound(tag, "Rotation")),
         new OperationStackRegion(BlockPos.of(tag.getLong("RepeatMin")), BlockPos.of(tag.getLong("RepeatMax"))),
         BlockPos.of(tag.getLong("RepeatStride")),
         decodeVec3(requiredCompound(tag, "Scale"))
      );
   }

   private static CompoundTag encodeVec3(Vec3 value) {
      CompoundTag tag = new CompoundTag();
      tag.putDouble("X", value.x);
      tag.putDouble("Y", value.y);
      tag.putDouble("Z", value.z);
      return tag;
   }

   private static Vec3 decodeVec3(CompoundTag tag) throws IOException {
      double x = tag.getDouble("X");
      double y = tag.getDouble("Y");
      double z = tag.getDouble("Z");
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
         throw new IOException("Operation draft contains a non-finite vector");
      }
      return new Vec3(x, y, z);
   }

   private static ListTag encodeVec3List(List<Vec3> values) {
      if (values.size() > MAX_POINTS) throw new IllegalArgumentException("Operation draft has too many points");
      ListTag list = new ListTag();
      values.forEach(value -> list.add(encodeVec3(value)));
      return list;
   }

   private static List<Vec3> decodeVec3List(ListTag tags) throws IOException {
      if (tags.size() > MAX_POINTS) throw new IOException("Operation draft has too many points");
      ArrayList<Vec3> result = new ArrayList<>(tags.size());
      for (int index = 0; index < tags.size(); index++) result.add(decodeVec3(tags.getCompound(index)));
      return List.copyOf(result);
   }

   private static void putAabb(CompoundTag tag, AABB bounds) {
      tag.put("BoundsMin", encodeVec3(new Vec3(bounds.minX, bounds.minY, bounds.minZ)));
      tag.put("BoundsMax", encodeVec3(new Vec3(bounds.maxX, bounds.maxY, bounds.maxZ)));
   }

   private static AABB readAabb(CompoundTag tag) throws IOException {
      Vec3 min = decodeVec3(requiredCompound(tag, "BoundsMin"));
      Vec3 max = decodeVec3(requiredCompound(tag, "BoundsMax"));
      if (max.x < min.x || max.y < min.y || max.z < min.z) throw new IOException("Invalid operation draft bounds");
      return new AABB(min, max);
   }

   private static void putBlockPositions(CompoundTag tag, String key, List<BlockPos> positions) {
      if (positions.size() > MAX_POINTS) throw new IllegalArgumentException("Operation draft has too many points");
      tag.putLongArray(key, positions.stream().mapToLong(BlockPos::asLong).toArray());
   }

   private static List<BlockPos> readBlockPositions(CompoundTag tag, String key) throws IOException {
      long[] values = tag.getLongArray(key);
      if (values.length > MAX_POINTS) throw new IOException("Operation draft has too many points");
      return java.util.Arrays.stream(values).mapToObj(BlockPos::of).toList();
   }

   private static void putOptionalBlockPos(CompoundTag tag, String key, BlockPos value) {
      if (value != null) {
         tag.putBoolean(key + "Present", true);
         tag.putLong(key, value.asLong());
      }
   }

   private static BlockPos readOptionalBlockPos(CompoundTag tag, String key) {
      return tag.getBoolean(key + "Present") ? BlockPos.of(tag.getLong(key)) : null;
   }

   private static CompoundTag requiredCompound(CompoundTag owner, String key) throws IOException {
      if (!owner.contains(key, Tag.TAG_COMPOUND)) throw new IOException("Operation draft is missing " + key);
      return owner.getCompound(key);
   }

   private static ListTag requiredList(CompoundTag owner, String key) throws IOException {
      if (!owner.contains(key, Tag.TAG_LIST)) throw new IOException("Operation draft is missing " + key);
      return owner.getList(key, Tag.TAG_COMPOUND);
   }

   private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) throws IOException {
      try {
         return Enum.valueOf(type, value);
      } catch (IllegalArgumentException exception) {
         throw new IOException("Invalid operation draft " + name, exception);
      }
   }

   private static final class BlockCounter {
      private int count;

      void add() {
         if (++this.count > MAX_BLOCKS) throw new IllegalArgumentException("Operation draft exceeds the block limit");
      }

      void addChecked() throws IOException {
         if (++this.count > MAX_BLOCKS) throw new IOException("Operation draft exceeds the block limit");
      }
   }
}
