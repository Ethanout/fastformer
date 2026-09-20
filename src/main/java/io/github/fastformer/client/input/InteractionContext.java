package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
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
      // The draft read walks the session box, so it stays behind the cheap
      // phase check. A draft can only exist while an operation phase exists.
      boolean selectionPhase = ClientOperationController.selectionSessionActive()
         || ClientOperationController.active()
         || FastPlaceClientPreview.operationActive();
      boolean operationSelection = selectionPhase && selectionOwnsPointer(
         ClientOperationController.selectionSessionActive(),
         ClientOperationController.selectionDraftActive()
      );
      // Empty-hand block breaking yields to vanilla when no selection owns the
      // pointer. The point phase counts as ownership, so it cannot lose the
      // second-point input. The immediate reach test is used here, because a
      // click owner must not depend on a fade that the camera can suspend.
      if (player.getMainHandItem().isEmpty()) {
         return !operationSelection
            && vanillaOwnsEmptyHandClick(
               minecraft.hitResult instanceof BlockHitResult
                  && withinReach(minecraft, player, player.blockInteractionRange()),
               minecraft.hitResult instanceof net.minecraft.world.phys.EntityHitResult
                  && withinReach(minecraft, player, player.entityInteractionRange())
            );
      }
      if (operationSelection) {
         return false;
      }
      // Selection editing owns the pointer and retains its historical direct
      // reach behavior. Near blocks suppress selection-face picking instead
      // of handing the click back to vanilla; this keeps corner adjustment
      // available while a selection session owns the input vocabulary.
      if (FastPlaceClientPreview.operationActive() || ClientOperationController.active()) {
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
         || io.github.fastformer.client.operation.controller.ClientOperationController.active()
         || io.github.fastformer.client.operation.controller.ClientOperationController.selectionSessionActive();
      if (selectionSession) {
         DISAPPEARANCE.reset();
         return;
      }
      boolean condition = directNearVanillaBlock(minecraft, player);
      Object target = FastPlaceClientPreview.previewRaycastTarget(player);
      // Fast camera movement cannot confirm a deliberate mode transition. It
      // decays toward visible instead of freezing the previous state.
      boolean stable = speed <= MAX_CAMERA_SPEED_DEGREES_PER_TICK;
      DISAPPEARANCE.tick(stable, condition, target);
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
      if (shouldForceFullPreviewVisibility(
         minecraft.player != null,
         FastPlaceClientPreview.operationActive(),
         io.github.fastformer.client.operation.controller.ClientOperationController.active(),
         io.github.fastformer.client.operation.controller.ClientOperationController.selectionSessionActive()
      )) {
         return 1.0F;
      }
      return DISAPPEARANCE.visibility(partialTick);
   }

   static boolean shouldForceFullPreviewVisibility(
      boolean playerPresent,
      boolean operationPreviewActive,
      boolean workspaceActive,
      boolean selectionSessionActive
   ) {
      return !playerPresent || operationPreviewActive || workspaceActive || selectionSessionActive;
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

   /**
    * True while a selection owns the pointer. The server preview and the
    * client-owned draft both count, so the gate never depends on one of them
    * alone. This is the seam the operation layer must fill when it makes the
    * AABB point phase client-owned.
    */
   public static boolean selectionOwnsPointer(boolean serverSelectionActive, boolean localDraftActive) {
      return serverSelectionActive || localDraftActive;
   }

   /**
    * Vanilla owns an empty-hand click when the crosshair already has a target
    * inside the vanilla interaction range. A block and an entity both count:
    * the vanilla crosshair reports an entity target for an attack, and that
    * click must not be diverted into a selection or a block action.
    */
   public static boolean vanillaOwnsEmptyHandClick(boolean blockWithinReach, boolean entityWithinReach) {
      return blockWithinReach || entityWithinReach;
   }

   private static boolean withinReach(Minecraft minecraft, LocalPlayer player, double reach) {
      if (minecraft.hitResult == null) {
         return false;
      }
      return player.getEyePosition().distanceToSqr(minecraft.hitResult.getLocation()) <= reach * reach;
   }

   private static boolean directNearVanillaBlock(Minecraft minecraft, LocalPlayer player) {
      if (!(minecraft.hitResult instanceof BlockHitResult hit)) {
         return false;
      }
      double reach = player.blockInteractionRange();
      return player.getEyePosition().distanceToSqr(hit.getLocation()) <= reach * reach;
   }
}
