package io.github.fastformer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Immutable input snapshot shared by interaction target providers. */
public record InteractionContext(
   Minecraft minecraft,
   LocalPlayer player,
   Vec3 eye,
   Vec3 view,
   Vec3 camera,
   boolean alternative,
   boolean control,
   boolean nearVanillaBlock
) {
   public static InteractionContext capture(Minecraft minecraft) {
      LocalPlayer player = minecraft.player;
      if (player == null) {
         return null;
      }
      return new InteractionContext(
         minecraft,
         player,
         player.getEyePosition(),
         player.getViewVector(1.0F),
         minecraft.gameRenderer.getMainCamera().getPosition(),
         FastPlaceClientInput.modifierHeld(),
         FastPlaceClientInput.controlHeld(),
         nearVanillaBlock(minecraft)
      );
   }

   public static boolean nearVanillaBlock(Minecraft minecraft) {
      LocalPlayer player = minecraft.player;
      if (player == null || !(minecraft.hitResult instanceof BlockHitResult hit)) {
         return false;
      }
      double reach = player.blockInteractionRange();
      return player.getEyePosition().distanceToSqr(hit.getLocation()) <= reach * reach;
   }
}
