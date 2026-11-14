package io.github.fastformer.protocol.c2s.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.common.interaction.binding.DoubleClickPolicy;
import io.github.fastformer.protocol.codec.GestureCodecs;

public record TinkerInteractionPayload(long token, BlockHitResult hit, Vec3 eye, Vec3 view, DoubleClickPolicy doubleClick) implements CustomPacketPayload {
   public TinkerInteractionPayload(long token, BlockHitResult hit, Vec3 eye, Vec3 view) {
      this(token, hit, eye, view, DoubleClickPolicy.DEFAULT);
   }
   public TinkerInteractionPayload {
      java.util.Objects.requireNonNull(doubleClick);
      if (token <= 0 || hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
         || !io.github.fastformer.common.raycast.RaySnapshotValidation.valid(eye, view)) throw new IllegalArgumentException("Invalid tinker interaction");
      hit = new BlockHitResult(hit.getLocation(), hit.getDirection(), hit.getBlockPos().immutable(), hit.isInside());
   }
   public static final Type<TinkerInteractionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "tinker_interaction"));
   public static final StreamCodec<FriendlyByteBuf, TinkerInteractionPayload> STREAM_CODEC = CustomPacketPayload.codec(
      (payload, buffer) -> {
         buffer.writeVarLong(payload.token());
         buffer.writeBlockHitResult(payload.hit());
         buffer.writeVec3(payload.eye());
         buffer.writeVec3(payload.view());
         GestureCodecs.DOUBLE_CLICK.encode(buffer, payload.doubleClick());
      }, buffer -> new TinkerInteractionPayload(buffer.readVarLong(), buffer.readBlockHitResult(), buffer.readVec3(), buffer.readVec3(),
         GestureCodecs.DOUBLE_CLICK.decode(buffer)));
   @Override public Type<TinkerInteractionPayload> type() { return TYPE; }
}
