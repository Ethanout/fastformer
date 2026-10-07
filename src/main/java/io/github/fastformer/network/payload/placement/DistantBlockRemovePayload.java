package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public record DistantBlockRemovePayload(BlockHitResult hit, Vec3 eye, Vec3 view, boolean throughFluids) implements CustomPacketPayload {
   public DistantBlockRemovePayload {
      if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
         || !io.github.fastformer.network.RaySnapshotValidation.valid(eye, view)) {
         throw new IllegalArgumentException("Invalid distant block removal");
      }
      hit = new BlockHitResult(hit.getLocation(), hit.getDirection(), hit.getBlockPos().immutable(), hit.isInside());
   }
   public static final Type<DistantBlockRemovePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "distant_block_remove"));
   public static final StreamCodec<FriendlyByteBuf, DistantBlockRemovePayload> STREAM_CODEC = CustomPacketPayload.codec(
      (payload, buffer) -> {
         buffer.writeBlockHitResult(payload.hit());
         buffer.writeVec3(payload.eye());
         buffer.writeVec3(payload.view());
         buffer.writeBoolean(payload.throughFluids());
      }, buffer -> new DistantBlockRemovePayload(buffer.readBlockHitResult(), buffer.readVec3(), buffer.readVec3(), buffer.readBoolean()));
   @Override public Type<DistantBlockRemovePayload> type() { return TYPE; }
}
