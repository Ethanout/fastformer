package io.github.fastformer.client;

import io.github.fastformer.FastFormer;
import io.github.fastformer.network.CycleStageModePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = FastFormer.MOD_ID, value = Dist.CLIENT)
public final class FastPlaceClientInput {
    private FastPlaceClientInput() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS
                && minecraft.options.keySprint.matches(event.getKey(), event.getScanCode())
                && NetworkRegistry.hasChannel(minecraft.getConnection(), CycleStageModePayload.TYPE.id())) {
            PacketDistributor.sendToServer(CycleStageModePayload.INSTANCE);
        }
    }
}
