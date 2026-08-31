package io.github.fastformer.network.payload.operation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

/** Requests one authoritative remote selection point using the server raycast. */
public record OperationPointPayload(OperationPointPayload.Role role) implements CustomPacketPayload {
   public static final Type<OperationPointPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "operation_point"));
   public static final StreamCodec<FriendlyByteBuf, OperationPointPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationPointPayload::write, OperationPointPayload::new
   );

   private OperationPointPayload(FriendlyByteBuf buffer) {
      this(buffer.readEnum(OperationPointPayload.Role.class));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.role);
   }

   @Override
   public Type<OperationPointPayload> type() {
      return TYPE;
   }

   public enum Role {
      FIRST,
      SECOND,
      EXTRA
   }
}
