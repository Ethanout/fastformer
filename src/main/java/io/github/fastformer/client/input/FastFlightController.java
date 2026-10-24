package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.FastFlightGesture;
import io.github.fastformer.client.FastFormerClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/** Flight gestures keep ability speed unchanged; dash requests are resolved by the server. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class FastFlightController {
   private static final FastFlightGesture GESTURE = new FastFlightGesture();
   private static LocalPlayer owner;

   private FastFlightController() { }

   @SubscribeEvent
   public static void onKey(InputEvent.Key event) {
      Minecraft minecraft = Minecraft.getInstance();
      validateOwner(minecraft);
      if (!eligible(minecraft)) {
         GESTURE.reset();
         return;
      }
      if (!minecraft.options.keyUp.matches(event.getKey(), event.getScanCode())) return;
      if (event.getAction() == GLFW.GLFW_PRESS) GESTURE.press(System.nanoTime());
      else if (event.getAction() == GLFW.GLFW_RELEASE && GESTURE.release(System.nanoTime())) {
         ForwardDashController.request(minecraft);
      }
   }

   @SubscribeEvent
   public static void onTick(ClientTickEvent.Pre event) {
      Minecraft minecraft = Minecraft.getInstance();
      validateOwner(minecraft);
      if (!eligible(minecraft)) GESTURE.reset();
      else if (!minecraft.options.keyUp.isDown()) GESTURE.release();
   }

   public static float flyingSpeed(LocalPlayer player, float vanillaSpeed) {
      Minecraft minecraft = Minecraft.getInstance();
      if (player != owner || !eligible(minecraft) || !minecraft.options.keyUp.isDown()
         || !FastFormerClientConfig.flightSettings().accelerationEnabled()) return vanillaSpeed;
      double multiplier = GESTURE.multiplier(System.nanoTime());
      return multiplier > 1.0 ? (float)(player.getAbilities().getFlyingSpeed() * multiplier) : vanillaSpeed;
   }

   private static boolean eligible(Minecraft minecraft) {
      return minecraft.player != null && minecraft.getConnection() != null && minecraft.screen == null && minecraft.isWindowActive()
         && !minecraft.isPaused() && minecraft.player.getAbilities().flying
         && !minecraft.player.isPassenger() && minecraft.getCameraEntity() == minecraft.player;
   }

   private static void validateOwner(Minecraft minecraft) {
      if (owner == minecraft.player) return;
      GESTURE.reset();
      owner = minecraft.player;
   }
}
