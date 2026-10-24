package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules;

public record IdleInteractionCapturePayload(long token, BlockHitResult hit, Vec3 eye, Vec3 view,
   InteractionRaycastRules rules) implements CustomPacketPayload {
   public IdleInteractionCapturePayload(long token, BlockHitResult hit, Vec3 eye, Vec3 view) {
      this(token, hit, eye, view, InteractionRaycastRules.DEFAULT);
   }
   public IdleInteractionCapturePayload {
      java.util.Objects.requireNonNull(rules);
      if (token <= 0 || hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
         || !io.github.fastformer.network.RaySnapshotValidation.valid(eye, view)) throw new IllegalArgumentException("Invalid idle interaction capture");
      hit = new BlockHitResult(hit.getLocation(), hit.getDirection(), hit.getBlockPos().immutable(), hit.isInside());
   }
   public static final Type<IdleInteractionCapturePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "idle_interaction_capture"));
   public static final StreamCodec<FriendlyByteBuf, IdleInteractionCapturePayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> {
         buffer.writeVarLong(value.token); buffer.writeBlockHitResult(value.hit); buffer.writeVec3(value.eye); buffer.writeVec3(value.view);
         buffer.writeBoolean(value.rules.embedded()); buffer.writeBoolean(value.rules.throughFluids());
      },
      buffer -> new IdleInteractionCapturePayload(buffer.readVarLong(), buffer.readBlockHitResult(), buffer.readVec3(), buffer.readVec3(),
         new InteractionRaycastRules(buffer.readBoolean(), buffer.readBoolean())));

   @Override public Type<IdleInteractionCapturePayload> type() { return TYPE; }
}
