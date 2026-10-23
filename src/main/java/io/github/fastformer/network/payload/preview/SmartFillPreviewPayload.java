package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.fill.SmartFillSearch;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** An empty target list ends only the matching fill request. */
public record SmartFillPreviewPayload(UUID id, BlockState state, List<BlockPos> targets, OperationCallbackScope callbackScope)
   implements CustomPacketPayload {
   public static final Type<SmartFillPreviewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "smart_fill_preview"));
   public static final StreamCodec<FriendlyByteBuf, SmartFillPreviewPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> {
         buffer.writeUUID(value.id()); buffer.writeVarInt(Block.getId(value.state()));
         OperationCallbackScope.STREAM_CODEC.encode(buffer, value.callbackScope());
         buffer.writeVarInt(value.targets().size()); value.targets().forEach(buffer::writeBlockPos);
      }, buffer -> {
         UUID id = buffer.readUUID(); BlockState state = Block.stateById(buffer.readVarInt());
         var scope = OperationCallbackScope.STREAM_CODEC.decode(buffer);
         int count = buffer.readVarInt();
         if (count < 0 || count > SmartFillSearch.MAX_TARGETS) throw new IllegalArgumentException("Invalid fill size");
         var targets = new ArrayList<BlockPos>(count);
         for (int i = 0; i < count; i++) targets.add(buffer.readBlockPos());
         return new SmartFillPreviewPayload(id, state, targets, scope);
      });
   public SmartFillPreviewPayload { targets = List.copyOf(targets); }
   @Override public Type<SmartFillPreviewPayload> type() { return TYPE; }
}
