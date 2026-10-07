package io.github.fastformer.network.payload.placement;

import io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot;
import java.util.ArrayList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

public record IdleInteractionSnapshotPayload(long token, ResourceLocation dimension, InteractionShapeSnapshot snapshot) implements CustomPacketPayload {
   public IdleInteractionSnapshotPayload {
      if (token <= 0) throw new IllegalArgumentException("Invalid interaction token");
      java.util.Objects.requireNonNull(dimension);
      java.util.Objects.requireNonNull(snapshot);
   }
   public static final Type<IdleInteractionSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "idle_interaction_snapshot"));
   public static final StreamCodec<FriendlyByteBuf, IdleInteractionSnapshotPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> {
         buffer.writeVarLong(value.token);
         buffer.writeResourceLocation(value.dimension);
         buffer.writeBlockPos(value.snapshot.position());
         buffer.writeVarInt(value.snapshot.boxes().size());
         for (AABB box : value.snapshot.boxes()) {
            buffer.writeDouble(box.minX); buffer.writeDouble(box.minY); buffer.writeDouble(box.minZ);
            buffer.writeDouble(box.maxX); buffer.writeDouble(box.maxY); buffer.writeDouble(box.maxZ);
         }
      }, buffer -> {
         long token = buffer.readVarLong();
         ResourceLocation dimension = buffer.readResourceLocation();
         var position = buffer.readBlockPos();
         int size = buffer.readVarInt();
         if (size < 0 || size > 256) throw new IllegalArgumentException("Interaction shape has too many boxes");
         var boxes = new ArrayList<AABB>(size);
         for (int i = 0; i < size; i++) boxes.add(new AABB(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
         return new IdleInteractionSnapshotPayload(token, dimension, new InteractionShapeSnapshot(position, boxes));
      });

   @Override public Type<IdleInteractionSnapshotPayload> type() { return TYPE; }
}
