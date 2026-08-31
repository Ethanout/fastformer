package io.github.fastformer.client.operation.clipboard;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Bounded NBT codec for the untrusted persistent client clipboard. */
public final class OperationClipboardCodec {
   private static final int MAX_BLOCKS = 2_000_000;

   private OperationClipboardCodec() {
   }

   public static CompoundTag encode(OperationClipboard clipboard) {
      CompoundTag root = new CompoundTag();
      root.putInt("Version", OperationClipboard.VERSION);
      ListTag parts = new ListTag();
      int total = 0;
      for (OperationClipboard.Part part : clipboard.parts()) {
         CompoundTag partTag = new CompoundTag();
         partTag.putInt("Id", part.originalId());
         ListTag blocks = new ListTag();
         for (Map.Entry<BlockPos, ClientBlockSnapshot> entry : part.blocks().entrySet()) {
            if (++total > MAX_BLOCKS) {
               throw new IllegalArgumentException("Clipboard exceeds the client block limit");
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

   public static OperationClipboard decode(CompoundTag root, HolderGetter<Block> blocks) throws IOException {
      if (root.getInt("Version") != OperationClipboard.VERSION) {
         throw new IOException("Unsupported operation clipboard version");
      }
      ListTag partTags = root.getList("Parts", Tag.TAG_COMPOUND);
      if (partTags.isEmpty() || partTags.size() > ClientOperationWorkspace.MAX_PARTS) {
         throw new IOException("Invalid operation clipboard part count");
      }
      int total = 0;
      List<OperationClipboard.Part> parts = new ArrayList<>(partTags.size());
      for (int index = 0; index < partTags.size(); index++) {
         CompoundTag partTag = partTags.getCompound(index);
         ListTag blockTags = partTag.getList("Blocks", Tag.TAG_COMPOUND);
         if (blockTags.isEmpty()) {
            throw new IOException("Clipboard part has no blocks");
         }
         LinkedHashMap<BlockPos, ClientBlockSnapshot> snapshots = new LinkedHashMap<>();
         for (int blockIndex = 0; blockIndex < blockTags.size(); blockIndex++) {
            if (++total > MAX_BLOCKS) {
               throw new IOException("Clipboard exceeds the client block limit");
            }
            CompoundTag blockTag = blockTags.getCompound(blockIndex);
            BlockPos pos = BlockPos.of(blockTag.getLong("Pos"));
            CompoundTag stateTag = blockTag.getCompound("State");
            ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
            if (blockId == null || blocks.get(ResourceKey.create(Registries.BLOCK, blockId)).isEmpty()) {
               throw new IOException("Unknown clipboard block: " + stateTag.getString("Name"));
            }
            BlockState state = NbtUtils.readBlockState(blocks, stateTag);
            CompoundTag blockEntity = blockTag.contains("BlockEntity", Tag.TAG_COMPOUND)
               ? blockTag.getCompound("BlockEntity")
               : null;
            snapshots.put(pos, new ClientBlockSnapshot(state, blockEntity));
         }
         parts.add(new OperationClipboard.Part(partTag.getInt("Id"), snapshots));
      }
      return new OperationClipboard(parts);
   }
}
