package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Resolves a double click and restores its first wrench change before selection starts. */
public record IdleSelectionEntryPayload(long requestId, long token, Vec3 eye, Vec3 view) implements CustomPacketPayload {
   public IdleSelectionEntryPayload {
      if (requestId <= 0 || token <= 0 || !io.github.fastformer.network.RaySnapshotValidation.valid(eye, view))
         throw new IllegalArgumentException("Invalid idle selection entry");
   }
   public static final Type<IdleSelectionEntryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "idle_selection_entry"));
   public static final StreamCodec<FriendlyByteBuf, IdleSelectionEntryPayload> STREAM_CODEC = CustomPacketPayload.codec(
      (payload, buffer) -> {
         buffer.writeVarLong(payload.requestId()); buffer.writeVarLong(payload.token());
         buffer.writeVec3(payload.eye()); buffer.writeVec3(payload.view());
      }, buffer -> new IdleSelectionEntryPayload(buffer.readVarLong(), buffer.readVarLong(), buffer.readVec3(), buffer.readVec3()));
   @Override public Type<IdleSelectionEntryPayload> type() { return TYPE; }
}
