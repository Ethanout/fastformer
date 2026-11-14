package io.github.fastformer.protocol.codec;

import io.github.fastformer.common.interaction.binding.DoubleClickPolicy;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Gesture parameters travel with the first press so both sides freeze the same rules. */
public final class GestureCodecs {
   public static final StreamCodec<ByteBuf, DoubleClickPolicy> DOUBLE_CLICK = ByteBufCodecs.fromCodec(DoubleClickPolicy.CODEC);
   private GestureCodecs() {}
}
