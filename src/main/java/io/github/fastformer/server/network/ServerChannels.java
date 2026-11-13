package io.github.fastformer.server.network;

import io.github.fastformer.core.command.Intent;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers typed server handlers; decoding never invokes game logic on the network thread. */
public final class ServerChannels {
   private final PayloadRegistrar registrar;

   public ServerChannels(PayloadRegistrar registrar) {
      this.registrar = Objects.requireNonNull(registrar, "registrar");
   }

   public <P extends CustomPacketPayload> Channel<P> receive(CustomPacketPayload.Type<P> type,
      StreamCodec<? super RegistryFriendlyByteBuf, P> codec) {
      return new Channel<>(registrar, type, codec);
   }

   public static final class Channel<P extends CustomPacketPayload> {
      private final PayloadRegistrar registrar;
      private final CustomPacketPayload.Type<P> type;
      private final StreamCodec<? super RegistryFriendlyByteBuf, P> codec;

      private Channel(PayloadRegistrar registrar, CustomPacketPayload.Type<P> type,
         StreamCodec<? super RegistryFriendlyByteBuf, P> codec) {
         this.registrar = registrar;
         this.type = Objects.requireNonNull(type, "type");
         this.codec = Objects.requireNonNull(codec, "codec");
      }

      public void handle(BiConsumer<P, ServerPlayer> handler) {
         Objects.requireNonNull(handler, "handler");
         registrar.playToServer(type, codec, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) handler.accept(payload, player);
         }));
      }

      public void toIntent(Function<P, ? extends Intent> intent) {
         toIntent(payload -> 0, intent);
      }

      /** Carries request identity; each feature still owns its sequence and gesture validation. */
      public void toIntent(ToLongFunction<P> requestId, Function<P, ? extends Intent> intent) {
         Objects.requireNonNull(requestId, "requestId");
         Objects.requireNonNull(intent, "intent");
         handle((payload, player) -> PlayerIntentSystem.dispatch(player, IntentSource.NETWORK,
            requestId.applyAsLong(payload), intent.apply(payload)));
      }
   }
}
