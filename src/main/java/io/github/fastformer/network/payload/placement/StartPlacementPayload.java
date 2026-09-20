package io.github.fastformer.network.payload.placement;

import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Starts the building selection session at the remote crosshair hit. */
public record StartPlacementPayload(RaycastPlacement placement) implements CustomPacketPayload {
   public static final StartPlacementPayload SURFACE = new StartPlacementPayload(
      RaycastPlacement.SURFACE
   );
   public static final StartPlacementPayload EMBEDDED = new StartPlacementPayload(
      RaycastPlacement.EMBEDDED
   );
   public static final Type<StartPlacementPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "start_placement")
   );
   public static final StreamCodec<FriendlyByteBuf, StartPlacementPayload> STREAM_CODEC = CustomPacketPayload.codec(
      StartPlacementPayload::write, StartPlacementPayload::new
   );

   public StartPlacementPayload {
      placement = placement == null ? RaycastPlacement.SURFACE : placement;
   }

   private StartPlacementPayload(FriendlyByteBuf buffer) {
      this(buffer.readEnum(RaycastPlacement.class));
   }

   public static StartPlacementPayload forPlacement(RaycastPlacement placement) {
      return placement == RaycastPlacement.EMBEDDED ? EMBEDDED : SURFACE;
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.placement);
   }

   @Override
   public Type<StartPlacementPayload> type() {
      return TYPE;
   }
}
