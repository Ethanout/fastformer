package io.github.fastformer.protocol.settings;

import io.github.fastformer.core.registry.Registry;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Frozen typed wire catalog. Unknown keys and keys from another catalog are rejected. */
public final class SettingCatalog {
   private final Registry<ResourceLocation, SettingKey<?>> keys = Registry.create("settings");

   public <T> SettingKey<T> register(ResourceLocation id, Class<T> type, StreamCodec<? super FriendlyByteBuf, T> codec) {
      var key = new SettingKey<>(id, type, codec);
      keys.register(id, key);
      return key;
   }

   public void freeze() { keys.freeze(); }

   public SettingRequest<?> decode(FriendlyByteBuf buffer) {
      ResourceLocation id = buffer.readResourceLocation();
      return keys.get(id).orElseThrow(() -> new DecoderException("Unknown setting: " + id)).decode(buffer);
   }

   public void encode(FriendlyByteBuf buffer, SettingRequest<?> request) {
      Objects.requireNonNull(request, "request");
      if (keys.get(request.key().id()).orElse(null) != request.key()) {
         throw new IllegalArgumentException("Setting does not belong to this catalog: " + request.key().id());
      }
      buffer.writeResourceLocation(request.key().id());
      request.key().encode(buffer, request.value());
   }
}
