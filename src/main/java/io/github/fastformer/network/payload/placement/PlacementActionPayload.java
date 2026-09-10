package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A semantic placement action. Shape data stays in the authoritative server session. */
public record PlacementActionPayload(Action action, long requestId) implements CustomPacketPayload {
   public static final int PROTOCOL_VERSION = 2;
   public static final Type<PlacementActionPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "placement_action")
   );
   public static final StreamCodec<FriendlyByteBuf, PlacementActionPayload> STREAM_CODEC =
      CustomPacketPayload.codec(PlacementActionPayload::write, PlacementActionPayload::new);

   public PlacementActionPayload(Action action) {
      this(action, 0);
   }

   public PlacementActionPayload {
      action = action == null ? Action.CONFIRM : action;
      if (requestId < 0L) {
         throw new IllegalArgumentException("requestId must not be negative");
      }
   }

   private PlacementActionPayload(FriendlyByteBuf buffer) {
      this(readAction(buffer));
   }

   private PlacementActionPayload(ActionAndRequest value) {
      this(value.action(), value.requestId());
   }

   private static ActionAndRequest readAction(FriendlyByteBuf buffer) {
      int version = buffer.readVarInt();
      if (version != PROTOCOL_VERSION) {
         throw new IllegalArgumentException("Unsupported placement action protocol version: " + version);
      }
      return new ActionAndRequest(buffer.readEnum(Action.class), buffer.readVarLong());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(PROTOCOL_VERSION);
      buffer.writeEnum(this.action);
      buffer.writeVarLong(this.requestId);
   }

   @Override
   public Type<PlacementActionPayload> type() {
      return TYPE;
   }

   public enum Action {
      CONFIRM,
      QUICK_SHAPE
   }

   private record ActionAndRequest(Action action, long requestId) {
   }
}
