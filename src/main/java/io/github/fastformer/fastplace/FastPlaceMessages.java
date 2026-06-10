package io.github.fastformer.fastplace;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class FastPlaceMessages {
    private FastPlaceMessages() {
    }

    static void actionBar(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    static void chat(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), false);
    }
}
