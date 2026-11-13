package io.github.fastformer.protocol.settings;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** One registered placement effect's requested state. The server validates the effect identity. */
public record PlacementEffectValue(ResourceLocation effect, boolean enabled) {
   public static final StreamCodec<FriendlyByteBuf, PlacementEffectValue> CODEC = StreamCodec.composite(
      ResourceLocation.STREAM_CODEC, PlacementEffectValue::effect,
      ByteBufCodecs.BOOL, PlacementEffectValue::enabled, PlacementEffectValue::new);

   public PlacementEffectValue { Objects.requireNonNull(effect, "effect"); }
}
