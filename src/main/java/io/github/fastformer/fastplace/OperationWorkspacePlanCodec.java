package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Versioned bounded codec used by chunked workspace apply transfers. */
public final class OperationWorkspacePlanCodec {
   public static final int VERSION = 1;
   public static final int MAX_BLOCKS = 2_000_000;
   public static final int MAX_COMPRESSED_BYTES = 64 * 1024 * 1024;
   private static final long MAX_DECODE_BYTES = 256L * 1024L * 1024L;

   private OperationWorkspacePlanCodec() {
   }

   public static byte[] encodeCompressed(OperationWorkspacePlan plan) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      NbtIo.writeCompressed(encode(plan), output);
      byte[] bytes = output.toByteArray();
      if (bytes.length == 0 || bytes.length > MAX_COMPRESSED_BYTES) {
         throw new IOException("Compressed workspace plan exceeds the transfer limit");
      }
      return bytes;
   }

   public static OperationWorkspacePlan decodeCompressed(byte[] bytes, HolderGetter<Block> blocks) throws IOException {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_COMPRESSED_BYTES) {
         throw new IOException("Invalid compressed workspace plan size");
      }
      CompoundTag root = NbtIo.readCompressed(
         new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_DECODE_BYTES)
      );
      return decode(root, blocks);
   }

   public static CompoundTag encode(OperationWorkspacePlan plan) {
      if (plan == null || plan.parts().isEmpty() || plan.parts().size() > ClientOperationWorkspace.MAX_PARTS) {
         throw new IllegalArgumentException("Invalid workspace part count");
      }
      CompoundTag root = new CompoundTag();
      root.putInt("Version", VERSION);
      ListTag parts = new ListTag();
      int total = 0;
      for (OperationWorkspacePlan.Part part : plan.parts()) {
         CompoundTag partTag = new CompoundTag();
         partTag.putInt("Id", part.id());
         partTag.putString("Source", part.source().name());
         partTag.putBoolean("PendingDelete", part.pendingDelete());
         putTransform(partTag, part.transform());
         ListTag blocks = new ListTag();
         for (var entry : part.blocks().entrySet()) {
            if (++total > MAX_BLOCKS) {
               throw new IllegalArgumentException("Workspace plan exceeds the client block limit");
            }
            CompoundTag block = new CompoundTag();
            block.putLong("Pos", entry.getKey().asLong());
            block.put("State", NbtUtils.writeBlockState(entry.getValue().state()));
            CompoundTag blockEntity = entry.getValue().blockEntity();
            if (blockEntity != null) {
               block.put("BlockEntity", blockEntity);
            }
            blocks.add(block);
         }
         partTag.put("Blocks", blocks);
         parts.add(partTag);
      }
      root.put("Parts", parts);
      return NbtUtils.addCurrentDataVersion(root);
   }

   public static OperationWorkspacePlan decode(CompoundTag root, HolderGetter<Block> blocks) throws IOException {
      if (root == null || root.getInt("Version") != VERSION) {
         throw new IOException("Unsupported workspace plan version");
      }
      ListTag partTags = root.getList("Parts", Tag.TAG_COMPOUND);
      if (partTags.isEmpty() || partTags.size() > ClientOperationWorkspace.MAX_PARTS) {
         throw new IOException("Invalid workspace part count");
      }
      List<OperationWorkspacePlan.Part> parts = new ArrayList<>(partTags.size());
      int total = 0;
      for (int index = 0; index < partTags.size(); index++) {
         CompoundTag partTag = partTags.getCompound(index);
         ClientSelectionPart.Source source;
         try {
            source = ClientSelectionPart.Source.valueOf(partTag.getString("Source"));
         } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid workspace part source", exception);
         }
         ListTag blockTags = partTag.getList("Blocks", Tag.TAG_COMPOUND);
         if (blockTags.isEmpty()) {
            throw new IOException("Workspace part has no blocks");
         }
         LinkedHashMap<BlockPos, ClientBlockSnapshot> snapshots = new LinkedHashMap<>();
         for (int blockIndex = 0; blockIndex < blockTags.size(); blockIndex++) {
            if (++total > MAX_BLOCKS) {
               throw new IOException("Workspace plan exceeds the server block limit");
            }
            CompoundTag blockTag = blockTags.getCompound(blockIndex);
            CompoundTag stateTag = blockTag.getCompound("State");
            ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
            if (blockId == null || blocks.get(ResourceKey.create(Registries.BLOCK, blockId)).isEmpty()) {
               throw new IOException("Unknown workspace block: " + stateTag.getString("Name"));
            }
            BlockState state = NbtUtils.readBlockState(blocks, stateTag);
            CompoundTag blockEntity = blockTag.contains("BlockEntity", Tag.TAG_COMPOUND)
               ? blockTag.getCompound("BlockEntity") : null;
            snapshots.put(BlockPos.of(blockTag.getLong("Pos")), new ClientBlockSnapshot(state, blockEntity));
         }
         parts.add(new OperationWorkspacePlan.Part(
            partTag.getInt("Id"), source, snapshots, readTransform(partTag), partTag.getBoolean("PendingDelete")
         ));
      }
      return new OperationWorkspacePlan(parts);
   }

   private static void putTransform(CompoundTag tag, WorkspaceTransform transform) {
      putVec3(tag, "Translation", transform.translation());
      putVec3(tag, "Rotation", transform.rotation());
      putVec3(tag, "Scale", transform.scale());
      tag.putLong("RepeatMin", transform.repeats().min().asLong());
      tag.putLong("RepeatMax", transform.repeats().max().asLong());
      tag.putLong("RepeatStride", transform.repeatStride().asLong());
   }

   private static WorkspaceTransform readTransform(CompoundTag tag) {
      return new WorkspaceTransform(
         readVec3(tag, "Translation"),
         readVec3(tag, "Rotation"),
         new OperationStackRegion(BlockPos.of(tag.getLong("RepeatMin")), BlockPos.of(tag.getLong("RepeatMax"))),
         BlockPos.of(tag.getLong("RepeatStride")),
         tag.contains("Scale") ? readVec3(tag, "Scale") : new Vec3(1.0, 1.0, 1.0)
      );
   }

   private static void putVec3(CompoundTag tag, String key, Vec3 value) {
      CompoundTag vector = new CompoundTag();
      vector.putDouble("X", value.x);
      vector.putDouble("Y", value.y);
      vector.putDouble("Z", value.z);
      tag.put(key, vector);
   }

   private static Vec3 readVec3(CompoundTag tag, String key) {
      CompoundTag vector = tag.getCompound(key);
      return new Vec3(vector.getDouble("X"), vector.getDouble("Y"), vector.getDouble("Z"));
   }
}
