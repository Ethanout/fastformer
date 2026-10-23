package io.github.fastformer.network.payload.placement;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record SmartFillCommandPayload(UUID id, ResourceLocation dimension, Action action, Vec3 eye, Vec3 view) implements CustomPacketPayload {
   public SmartFillCommandPayload {
      java.util.Objects.requireNonNull(id);
      java.util.Objects.requireNonNull(dimension);
      java.util.Objects.requireNonNull(action);
      if (eye == null || view == null || !Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)
         || !Double.isFinite(view.lengthSqr()) || Math.abs(view.lengthSqr() - 1) > 0.001) {
         throw new IllegalArgumentException("Fill requires a finite eye and a unit view vector");
      }
   }
   public enum Action { DISCOVER, CONFIRM, CANCEL }
   public static final Type<SmartFillCommandPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "smart_fill_command"));
   public static final StreamCodec<FriendlyByteBuf, SmartFillCommandPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> {
         buffer.writeUUID(value.id()); buffer.writeResourceLocation(value.dimension()); buffer.writeEnum(value.action());
         buffer.writeVec3(value.eye()); buffer.writeVec3(value.view());
      }, buffer -> new SmartFillCommandPayload(buffer.readUUID(), buffer.readResourceLocation(), buffer.readEnum(Action.class), buffer.readVec3(), buffer.readVec3()));
   @Override public Type<SmartFillCommandPayload> type() { return TYPE; }
}
