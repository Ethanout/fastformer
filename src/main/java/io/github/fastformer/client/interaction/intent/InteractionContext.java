package io.github.fastformer.client.interaction.intent;

import io.github.fastformer.client.input.FastPlaceClientInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Immutable pointer snapshot. Active sessions own input at every distance. */
public record InteractionContext(Minecraft minecraft, LocalPlayer player, Vec3 eye, Vec3 view, Vec3 camera,
   boolean alternative, boolean control, boolean vanillaOwnsPointer) {
   public static InteractionContext capture(Minecraft minecraft) {
      LocalPlayer player = minecraft.player;
      return player == null ? null : new InteractionContext(minecraft, player, player.getEyePosition(),
         player.getViewVector(1), minecraft.gameRenderer.getMainCamera().getPosition(),
         FastPlaceClientInput.modifierHeld(), FastPlaceClientInput.controlHeld(), vanillaOwnsPointer(minecraft));
   }
   public static boolean vanillaOwnsPointer(Minecraft minecraft) { return FastPlaceClientInput.vanillaSession(); }
   public static float previewVisibility(Minecraft minecraft) { return 1; }
   public static float previewVisibility(Minecraft minecraft, float partialTick) { return 1; }
   public static boolean directlyNearVanillaBlock(Minecraft minecraft) {
      return minecraft.player != null && minecraft.hitResult instanceof BlockHitResult hit
         && minecraft.player.getEyePosition().distanceToSqr(hit.getLocation())
            <= minecraft.player.blockInteractionRange() * minecraft.player.blockInteractionRange();
   }
   public static boolean selectionOwnsPointer(boolean serverSelectionActive, boolean localDraftActive) {
      return serverSelectionActive || localDraftActive;
   }
   public static boolean vanillaOwnsEmptyHandClick(boolean blockWithinReach, boolean entityWithinReach) {
      return blockWithinReach || entityWithinReach;
   }
}
