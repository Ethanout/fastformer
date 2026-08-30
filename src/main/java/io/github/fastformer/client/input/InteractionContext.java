package io.github.fastformer.client.input;

import io.github.fastformer.client.FastPlaceClientInput;
import io.github.fastformer.client.FastPlaceClientPreview;
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
   private static final int DISAPPEARANCE_CAPACITY_TICKS = 20;
   private static final float MAX_CAMERA_SPEED_DEGREES_PER_TICK = 14.0F;
   private static final DisappearanceState DISAPPEARANCE = new DisappearanceState(DISAPPEARANCE_CAPACITY_TICKS);
   private static float lastYaw;
   private static float lastPitch;
   private static boolean cameraInitialized;

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
      if (player == null) {
         return false;
      }
      // Selection editing owns the pointer and retains its historical direct
      // reach behavior. Near blocks suppress selection-face picking instead
      // of handing the click back to vanilla; this keeps corner adjustment
      // available while a selection session owns the input vocabulary.
      if (FastPlaceClientPreview.operationActive()
         || io.github.fastformer.client.operation.ClientOperationController.active()) {
         return false;
      }
      return DISAPPEARANCE.disappeared();
   }

   /** Advances the near-vanilla gate once per client tick. */
   public static void tick(Minecraft minecraft) {
      LocalPlayer player = minecraft.player;
      if (player == null) {
         reset();
         return;
      }
      float yaw = player.getYRot();
      float pitch = player.getXRot();
      float speed = cameraInitialized
         ? Math.max(angleDelta(yaw, lastYaw), Math.abs(pitch - lastPitch))
         : 0.0F;
      lastYaw = yaw;
      lastPitch = pitch;
      cameraInitialized = true;

      boolean selectionSession = FastPlaceClientPreview.operationActive()
         || io.github.fastformer.client.operation.ClientOperationController.active();
      if (selectionSession) {
         DISAPPEARANCE.reset();
         return;
      }
      boolean condition = directNearVanillaBlock(minecraft, player);
      // Fast camera movement cannot confirm a deliberate mode transition. It
      // decays toward visible instead of freezing the previous state.
      boolean stable = speed <= MAX_CAMERA_SPEED_DEGREES_PER_TICK;
      DISAPPEARANCE.tick(stable, condition);
   }

   public static float previewVisibility(Minecraft minecraft) {
      return previewVisibility(minecraft, 1.0F);
   }

   /**
    * Returns the near-vanilla transition opacity at the current render
    * partial tick.  Keeping this interpolation here makes every preview
    * layer share the same transition instead of stepping independently.
    */
   public static float previewVisibility(Minecraft minecraft, float partialTick) {
      if (minecraft.player == null
         || FastPlaceClientPreview.operationActive()
         || io.github.fastformer.client.operation.ClientOperationController.active()) {
         return 1.0F;
      }
      return DISAPPEARANCE.visibility(partialTick);
   }

   public static void reset() {
      DISAPPEARANCE.reset();
      cameraInitialized = false;
   }

   private static float angleDelta(float first, float second) {
      float delta = (first - second) % 360.0F;
      if (delta > 180.0F) delta -= 360.0F;
      if (delta < -180.0F) delta += 360.0F;
      return Math.abs(delta);
   }

   public static boolean directlyNearVanillaBlock(Minecraft minecraft) {
      LocalPlayer player = minecraft.player;
      return player != null && directNearVanillaBlock(minecraft, player);
   }

   private static boolean directNearVanillaBlock(Minecraft minecraft, LocalPlayer player) {
      if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
         return false;
      }
      double reach = player.blockInteractionRange();
      return player.getEyePosition().distanceToSqr(hit.getLocation()) <= reach * reach;
   }
}
