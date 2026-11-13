package io.github.fastformer.protocol.settings;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** A registered setting's identity and value type. It contains no player or mutation handler. */
public final class SettingKey<T> {
   private final ResourceLocation id;
   private final Class<T> valueType;
   private final StreamCodec<? super FriendlyByteBuf, T> codec;

   SettingKey(ResourceLocation id, Class<T> valueType, StreamCodec<? super FriendlyByteBuf, T> codec) {
      this.id = Objects.requireNonNull(id, "id");
      this.valueType = Objects.requireNonNull(valueType, "valueType");
      this.codec = Objects.requireNonNull(codec, "codec");
   }

   public ResourceLocation id() { return id; }
   public T cast(Object value) { return valueType.cast(Objects.requireNonNull(value, "value")); }
   public SettingRequest<T> set(T value) { return new SettingRequest<>(this, Optional.of(cast(value))); }
   public SettingRequest<T> query() { return new SettingRequest<>(this, Optional.empty()); }

   SettingRequest<T> decode(FriendlyByteBuf buffer) {
      return buffer.readBoolean() ? set(codec.decode(buffer)) : query();
   }

   void encode(FriendlyByteBuf buffer, Optional<?> value) {
      buffer.writeBoolean(value.isPresent());
      value.ifPresent(present -> codec.encode(buffer, cast(present)));
   }
}
