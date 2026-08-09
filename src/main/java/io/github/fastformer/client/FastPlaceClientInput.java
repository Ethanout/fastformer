package io.github.fastformer.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.operation.ClientOperationController;
import io.github.fastformer.client.operation.ClientSelectionPart;
import io.github.fastformer.client.operation.PixelPerfectAngles;
import io.github.fastformer.client.operation.RepeatDragQuantizer;
import io.github.fastformer.network.CycleStageModePayload;
import io.github.fastformer.network.ClosePathPayload;
import io.github.fastformer.network.ModifierStatePayload;
import io.github.fastformer.network.GeometryGizmoDragPayload;
import io.github.fastformer.network.GeometryInteractionPayload;
import io.github.fastformer.network.GeometryPointPayload;
import io.github.fastformer.network.OperationExtendPayload;
import io.github.fastformer.network.OperationApplyPayload;
import io.github.fastformer.network.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.OperationTransformPayload;
import io.github.fastformer.network.ConfirmPayload;
import io.github.fastformer.network.QuickShapePayload;
import io.github.fastformer.network.StartPlacementPayload;
import io.github.fastformer.network.QuitFastPlacePayload;
import io.github.fastformer.network.OperationPointPayload;
import io.github.fastformer.network.OperationPointDragPayload;
import io.github.fastformer.network.OperationInsertPointPayload;
import io.github.fastformer.network.OperationRemovePointPayload;
import io.github.fastformer.network.OperationSelectPointPayload;
import io.github.fastformer.network.ScrollCandidatePayload;
import io.github.fastformer.network.UndoFastPlacePayload;
import io.github.fastformer.network.WorldRedoPayload;
import io.github.fastformer.network.WorldUndoPayload;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.PlaceableItems;
import io.github.fastformer.fastplace.OperationPointDragConstraint;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.Key;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@EventBusSubscriber(
   modid = "fastformer",
   value = {Dist.CLIENT}
)
public final class FastPlaceClientInput {
   private static final double OPERATION_REACH = LongRangeBlockRaycast.MAX_REACH;
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;
   private static final long MODIFIER_SHORT_PRESS_NANOS = 250_000_000L;
   private static final long UNDO_SHORT_PRESS_NANOS = 250_000_000L;
   private static final long OPERATION_FACE_SHORT_PRESS_NANOS = 140_000_000L;
   private static final long OPERATION_POINT_SHORT_PRESS_NANOS = 250_000_000L;
   private static final long OPERATION_POINT_DOUBLE_CLICK_NANOS = 350_000_000L;
   private static final long PATH_DOUBLE_CLICK_NANOS = 350_000_000L;
   private static final ShortPressTracker UNDO_PRESS = new ShortPressTracker();
   private static OperationDrag operationDrag;
   private static OperationPointDrag operationPointDrag;
   private static int operationClickCapturedButton = -1;
   private static GeometryGizmoDrag geometryGizmoDrag;
   private static WorkspaceGizmoDrag workspaceGizmoDrag;
   private static WorkspaceFaceDrag workspaceFaceDrag;
   private static boolean undoPressCaptured;
   private static boolean worldUndoKeyDown;
   private static boolean worldRedoKeyDown;
   private static int geometryClickCapturedButton = -1;
   private static boolean altDown;
   private static boolean radialChordDown;
   private static boolean modifierRouted;
   private static boolean modifierCycleEligible;
   private static boolean modifierUsedForInput;
   private static long modifierPressedAt;
   private static long lastPathClickAt;
   private static BlockPos lastPathClickPoint;
   private static boolean lastPathClickGeometry;
   private static long lastOperationPointLeftClickAt;
   private static int lastOperationPointLeftClickIndex = -1;
   private static long lastOperationPointRightClickAt;
   private static int lastOperationPointRightClickIndex = -1;
   private static boolean operationSessionWasActive;

   private FastPlaceClientInput() {
   }

   @SubscribeEvent
   public static void onKey(Key event) {
      Minecraft minecraft = Minecraft.getInstance();
      boolean altKey = event.getKey() == 342 || event.getKey() == 346;
      boolean ctrlKey = event.getKey() == 341 || event.getKey() == 345;
      boolean controlDown = physicalCtrlDown(minecraft, false);
      if (minecraft.player != null && minecraft.screen == null && event.getAction() == 1) {
         if (controlDown && event.getKey() == 67 && ClientOperationController.active()) {
            ClientOperationController.copySelected();
            return;
         }
         if (controlDown && event.getKey() == 86) {
            ClientOperationController.paste(minecraft);
            return;
         }
         if (controlDown && event.getKey() == 65 && ClientOperationController.active()) {
            ClientOperationController.workspace().selectAll();
            return;
         }
         if (controlDown && ((event.getKey() >= 49 && event.getKey() <= 57) || event.getKey() == 48)
            && ClientOperationController.active()) {
            int id = event.getKey() == 48 ? 10 : event.getKey() - 48;
            ClientOperationController.workspace().selectOnly(id);
            return;
         }
         if ((event.getKey() == 261 || event.getKey() == 259) && ClientOperationController.active()) {
            if (controlDown) {
               ClientOperationController.removeSelectedParts();
            } else {
               ClientOperationController.markSelectedForDeletion();
            }
            return;
         }
         if (controlDown && event.getKey() == 90 && ClientOperationController.active()) {
            ClientOperationController.undo();
            return;
         }
      }
      if (altKey
         && (event.getAction() == 0 || event.getAction() == 1)
         && minecraft.player != null
         && minecraft.getConnection() != null) {
         handleAltTransition(minecraft, altDownAfterEvent(minecraft, event.getKey(), event.getAction()));
      }
      if ((altKey || ctrlKey)
         && (event.getAction() == 0 || event.getAction() == 1)
         && minecraft.player != null
         && minecraft.getConnection() != null) {
         handleRadialKeyTransition(minecraft, physicalCtrlDown(minecraft, false) && physicalAltDown(minecraft, false));
      }
      if (minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && (event.getKey() == 90 || event.getKey() == 89)
         && event.getAction() == 1
         && physicalCtrlDown(minecraft, false)) {
         if (FastPlaceClientPreview.operationActive()) {
            cancelOperationGesture(minecraft);
         }
         if (event.getKey() == 90
            && !worldUndoKeyDown
            && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldUndoPayload.TYPE.id())) {
            worldUndoKeyDown = true;
            PacketDistributor.sendToServer(WorldUndoPayload.INSTANCE, new CustomPacketPayload[0]);
         } else if (event.getKey() == 89
            && !worldRedoKeyDown
            && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldRedoPayload.TYPE.id())) {
            worldRedoKeyDown = true;
            PacketDistributor.sendToServer(WorldRedoPayload.INSTANCE, new CustomPacketPayload[0]);
         }
      }
      if (event.getAction() == 0) {
         if (event.getKey() == 90) {
            worldUndoKeyDown = false;
         } else if (event.getKey() == 89) {
            worldRedoKeyDown = false;
         }
      }
      if (minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null) {
         if (event.getAction() == 1
            && (event.getKey() == 256 || event.getKey() == 81)
            && (FastPlaceClientPreview.active()
               || FastPlaceClientPreview.operationActive()
               || FastPlaceClientPreview.geometryActive()
               || FastPlaceClientPreview.taskActive() && FastPlaceClientPreview.activityCancellable())
            && NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id())) {
            if (FastPlaceClientPreview.operationActive()) {
               cancelOperationGesture(minecraft);
               ClientOperationController.clearWorkspace();
            }
            PacketDistributor.sendToServer(QuitFastPlacePayload.INSTANCE, new CustomPacketPayload[0]);
         }
         if (event.getAction() == 1
            && (event.getKey() == 257 || event.getKey() == 335)
            && (FastPlaceClientPreview.active() || FastPlaceClientPreview.operationActive() || FastPlaceClientPreview.geometryActive())
            && NetworkRegistry.hasChannel(minecraft.getConnection(), ConfirmPayload.TYPE.id())) {
            if (InteractionContext.nearVanillaBlock(minecraft)) {
               return;
            }
            // A confirmation ends the current input gesture.  If the mouse
            // button release arrives after the server has already consumed
            // this confirmation, it must not become a stale session undo.
            UNDO_PRESS.cancel();
            undoPressCaptured = false;
            if (altDown) {
               modifierUsedForInput = true;
            }
            if (FastPlaceClientPreview.operationActive()) {
               if (ClientOperationController.active()
                  && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationWorkspaceApplyPayload.TYPE.id())) {
                  ClientOperationController.submitWorkspace(minecraft);
               } else if (FastPlaceClientPreview.operationAdjustmentStarted()
                  && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationApplyPayload.TYPE.id())) {
                  PacketDistributor.sendToServer(
                     new OperationApplyPayload(physicalCtrlDown(minecraft, false)), new CustomPacketPayload[0]
                  );
               }
               return;
            }
            PacketDistributor.sendToServer(ConfirmPayload.INSTANCE, new CustomPacketPayload[0]);
         }
      }
   }

   private static void handleAltTransition(Minecraft minecraft, boolean down) {
      if (down == altDown) {
         return;
      }
      altDown = down;
      if (down) {
         modifierPressedAt = System.nanoTime();
         modifierCycleEligible = modifierCycleAvailable();
         modifierUsedForInput = false;
         modifierRouted = minecraft.screen == null
            && modifierSubmodeAvailable()
            && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id());
         if (modifierRouted) {
            PacketDistributor.sendToServer(new ModifierStatePayload(true), new CustomPacketPayload[0]);
         }
         return;
      }

      if (modifierRouted && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id())) {
         PacketDistributor.sendToServer(new ModifierStatePayload(false), new CustomPacketPayload[0]);
      }
      if (modifierRouted
         && modifierCycleEligible
         && !modifierUsedForInput
         && System.nanoTime() - modifierPressedAt <= MODIFIER_SHORT_PRESS_NANOS
         && NetworkRegistry.hasChannel(minecraft.getConnection(), CycleStageModePayload.TYPE.id())) {
         BlockPos candidate = FastPlaceClientPreview.lineModeCandidate();
         PacketDistributor.sendToServer(
            candidate != null ? new CycleStageModePayload(true, candidate) : CycleStageModePayload.INSTANCE, new CustomPacketPayload[0]
         );
      }
      modifierRouted = false;
      modifierCycleEligible = false;
      modifierUsedForInput = false;
   }

   private static boolean modifierSessionActive() {
      return FastPlaceClientPreview.active()
         || FastPlaceClientPreview.operationActive()
         || FastPlaceClientPreview.geometryActive()
            && (FastPlaceClientPreview.geometryAllows(GeometryAction.MODE_CYCLE)
               || FastPlaceClientPreview.geometryAllows(GeometryAction.SUBMODE));
   }

   private static boolean modifierCycleAvailable() {
      return FastPlaceClientPreview.geometryActive()
         ? FastPlaceClientPreview.geometryAllows(GeometryAction.MODE_CYCLE)
         : modifierSessionActive();
   }

   private static boolean modifierSubmodeAvailable() {
      return FastPlaceClientPreview.geometryActive()
         ? FastPlaceClientPreview.geometryAllows(GeometryAction.SUBMODE)
         : FastPlaceClientPreview.active()
            || FastPlaceClientPreview.operationActive()
            || FastPlaceClientPreview.buildingRaycastSubmodeAvailable();
   }

   private static void handleRadialKeyTransition(Minecraft minecraft, boolean down) {
      if (down == radialChordDown) {
         return;
      }
      radialChordDown = down;
      if (down && tryOpenGeometryRadial(minecraft)) {
         modifierUsedForInput = true;
      }
   }

   @SubscribeEvent
   public static void onInteraction(InteractionKeyMappingTriggered event) {
      Minecraft minecraft = Minecraft.getInstance();
      if (modifierHeld() && (event.isAttack() || event.isUseItem())) {
         return;
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty()
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !FastPlaceClientPreview.active()
         && !FastPlaceClientPreview.geometryActive()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         if (FastPlaceClientPreview.operationSelectionReady()
            && FastPlaceClientPreview.operationGizmoHit() != null) {
            if (InteractionContext.nearVanillaBlock(minecraft) && !modifierHeld()) {
               return;
            }
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (InteractionContext.nearVanillaBlock(minecraft) && !modifierHeld()) {
            return;
         }
         if (FastPlaceClientPreview.operationAdjustmentStarted() || FastPlaceClientPreview.operationPrism()) {
            if (!undoPressCaptured) {
               UNDO_PRESS.press(System.nanoTime());
               undoPressCaptured = true;
            }
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (FastPlaceClientPreview.operationActive() && modifierHeld()) {
            boolean handled = operationClickCapturedButton != 0
               && FastPlaceClientPreview.operationSelectionReady()
               && beginOperationGizmoDrag(minecraft, 0);
            if (handled) {
               modifierUsedForInput = true;
            }
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (FastPlaceClientPreview.operationCuboid()) {
            if (!modifierHeld() && !InteractionContext.nearVanillaBlock(minecraft)) {
               FastPlaceClientPreview.OperationPointerTarget target = FastPlaceClientPreview.operationPointerTarget();
               if (target.kind() == FastPlaceClientPreview.OperationPointerKind.WORLD) {
                  PacketDistributor.sendToServer(
                     new OperationPointPayload(OperationPointPayload.Role.FIRST), new CustomPacketPayload[0]
                  );
                  operationClickCapturedButton = 0;
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
               if (target.kind() == FastPlaceClientPreview.OperationPointerKind.FACE) {
                  if (operationClickCapturedButton != 0 && beginOperationFaceAdjustment(minecraft, -1, 0)) {
                     operationClickCapturedButton = 0;
                  }
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
            }
         }
         if (FastPlaceClientPreview.operationPrism()
            && (operationClickCapturedButton == 0 || beginOperationPointDrag(minecraft, 0))) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.nearVanillaBlock(minecraft)
            && FastPlaceClientPreview.operationPrism()
            && (operationClickCapturedButton == 0 || sendOperationEdgeInsertion(minecraft))) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.nearVanillaBlock(minecraft)
            && FastPlaceClientPreview.operationPrism()
            && FastPlaceClientPreview.operationCandidatePoint() != null
            && (operationClickCapturedButton == 0 || sendNextOperationPrismPoint(minecraft))) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!FastPlaceClientPreview.operationActive()
            && !InteractionContext.nearVanillaBlock(minecraft)
            && FastPlaceClientPreview.operationCandidatePoint() != null) {
            PacketDistributor.sendToServer(
               new OperationPointPayload(OperationPointPayload.Role.FIRST), new CustomPacketPayload[0]
            );
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (FastPlaceClientPreview.operationActive() && !InteractionContext.nearVanillaBlock(minecraft)) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
      }
      if (event.isUseItem()
         && minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty()
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !FastPlaceClientPreview.active()
         && !FastPlaceClientPreview.geometryActive()
         && !FastPlaceClientPreview.operationActive()
         && !InteractionContext.nearVanillaBlock(minecraft)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())
         && FastPlaceClientPreview.operationCandidatePoint() != null) {
         PacketDistributor.sendToServer(
            new OperationPointPayload(OperationPointPayload.Role.SECOND), new CustomPacketPayload[0]
         );
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (event.isUseItem()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && FastPlaceClientPreview.geometryActive()) {
         if (InteractionContext.nearVanillaBlock(minecraft)) {
            return;
         }
         if (handleGeometryRightClick(minecraft)) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && (FastPlaceClientPreview.active() || FastPlaceClientPreview.operationActive() || FastPlaceClientPreview.geometryActive())) {
         if (InteractionContext.nearVanillaBlock(minecraft)) {
            return;
         }
         if (FastPlaceClientPreview.geometryActive()
            && NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id())) {
            if (geometryClickCapturedButton == 0) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
            if (geometryGizmoDrag == null && beginGeometryGizmoDrag(minecraft, 0)) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
            if (beginGeometryInteraction(minecraft, 0)) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
         }
         if (!NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())) {
            return;
         }
         if (geometryGizmoDrag == null && !undoPressCaptured) {
            UNDO_PRESS.press(System.nanoTime());
            undoPressCaptured = true;
         }
         event.setSwingHand(false);
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public static void onMouseButton(Pre event) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
         return;
      }
      if (altDown && event.getAction() == 1) {
         modifierUsedForInput = true;
      }
      OperationInteractionIntent pointerIntent = FastPlaceClientPreview.operationInteractionIntent().orElse(null);
      if (event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1 || event.getButton() == 2)
         && !ClientOperationController.active()
         && !FastPlaceClientPreview.active()
         && !FastPlaceClientPreview.geometryActive()
         && !FastPlaceClientPreview.operationActive()
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && !InteractionContext.nearVanillaBlock(minecraft)
         && longRangeBlockHit(minecraft) != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), StartPlacementPayload.TYPE.id())) {
         PacketDistributor.sendToServer(
            event.getButton() == 2 ? StartPlacementPayload.EMBEDDED_INSTANCE : StartPlacementPayload.INSTANCE,
            new CustomPacketPayload[0]
         );
         event.setCanceled(true);
         return;
      }
      if (modifierHeld()
         && event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1)
         && operationDrag == null
         && workspaceGizmoDrag == null
         && workspaceFaceDrag == null) {
         if (ClientOperationController.active()
            && adjustWorkspaceSelectionAtCrosshair(minecraft, event.getButton())) {
            modifierUsedForInput = true;
            operationClickCapturedButton = event.getButton();
            event.setCanceled(true);
         }
         return;
      }
      if (event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1 || event.getButton() == 2)
         && (event.getButton() != 2 || ClientOperationController.activeSelectionTransformed())
         && ClientOperationController.active()
         && operationDrag == null && workspaceGizmoDrag == null && workspaceFaceDrag == null
         && pointerIntent instanceof OperationInteractionIntent.CreateSelection create) {
         if (ClientOperationController.handleCreateClick(event.getButton(), create.point())) {
            operationClickCapturedButton = event.getButton();
            event.setCanceled(true);
            return;
         }
      }

      if (event.getButton() == 0
         && activeSession()
         && (NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())
            || NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id()))) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            boolean operationAdjust = FastPlaceClientPreview.operationActive()
               && FastPlaceClientPreview.operationSelectionReady()
               && operationAdjustModifierHeld();
            boolean operationAlt = FastPlaceClientPreview.operationActive() && modifierHeld();
            if (OperationInputSemantics.yieldToVanillaNearBlock(
               InteractionContext.nearVanillaBlock(minecraft), operationAdjust || operationAlt,
               FastPlaceClientPreview.operationGizmoHit() != null
                  || pointerIntent instanceof OperationInteractionIntent.Face
                  || pointerIntent instanceof OperationInteractionIntent.Part
            )) {
               return;
            }
            OperationInputSemantics.LeftAction operationLeft = FastPlaceClientPreview.operationActive()
               ? OperationInputSemantics.leftAction(
                  FastPlaceClientPreview.operationPrism()
                     ? io.github.fastformer.fastplace.OperationSelectionMode.PRISM
                     : io.github.fastformer.fastplace.OperationSelectionMode.CUBOID,
                  FastPlaceClientPreview.operationSelectionReady() || ClientOperationController.active(),
                  FastPlaceClientPreview.operationAdjustmentStarted() || ClientOperationController.active(),
                  FastPlaceClientPreview.operationGizmoHit() != null
               )
               : OperationInputSemantics.LeftAction.VANILLA;
            OperationInteractionIntent.Gizmo workspaceGizmoHit =
               pointerIntent instanceof OperationInteractionIntent.Gizmo gizmo ? gizmo : null;
            OperationInteractionIntent.Face workspaceFaceHit =
               pointerIntent instanceof OperationInteractionIntent.Face face ? face : null;
            int workspacePartHit = pointerIntent instanceof OperationInteractionIntent.Part part ? part.partId() : 0;
            if (workspaceGizmoHit != null) {
               beginWorkspaceGizmoDrag(minecraft, workspaceGizmoHit, 0);
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (workspaceFaceHit != null) {
               selectOrBeginWorkspaceFace(minecraft, workspaceFaceHit, 0, -1);
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (workspacePartHit > 0) {
               if (physicalCtrlDown(minecraft, false)) {
                  ClientOperationController.workspace().toggleSelected(workspacePartHit);
               } else {
                  ClientOperationController.workspace().selectOnly(workspacePartHit);
               }
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (ClientOperationController.active()
               && adjustWorkspaceSelectionAtCrosshair(minecraft, 0)) {
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (operationLeft == OperationInputSemantics.LeftAction.GIZMO_DRAG) {
               beginOperationGizmoDrag(minecraft, 0);
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (FastPlaceClientPreview.operationPrism() && beginOperationPointDrag(minecraft, 0)) {
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (operationLeft == OperationInputSemantics.LeftAction.SELECTION_UNDO
               || operationLeft == OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO) {
               if (!undoPressCaptured) {
                  UNDO_PRESS.press(System.nanoTime());
                  undoPressCaptured = true;
               }
               consumed = true;
            } else if (operationAlt) {
               boolean handled = operationClickCapturedButton != 0 && beginOperationGizmoDrag(minecraft, 0);
               if (handled) {
                  modifierUsedForInput = true;
               }
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (operationAdjust) {
               boolean handled = FastPlaceClientPreview.operationCuboid()
                  ? beginOperationGizmoDrag(minecraft, 0)
                  : beginOperationGizmoDrag(minecraft, 0)
                     || sendOperationPointSelection(minecraft)
                     || beginOperationFaceAdjustment(minecraft, -1, 0);
               if (operationClickCapturedButton != 0 && handled) {
                  modifierUsedForInput = true;
               }
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (FastPlaceClientPreview.operationCuboid() && !InteractionContext.nearVanillaBlock(minecraft)) {
               FastPlaceClientPreview.OperationPointerTarget target = FastPlaceClientPreview.operationPointerTarget();
               if (target.kind() == FastPlaceClientPreview.OperationPointerKind.WORLD) {
                  PacketDistributor.sendToServer(
                     new OperationPointPayload(OperationPointPayload.Role.FIRST), new CustomPacketPayload[0]
                  );
                  operationClickCapturedButton = 0;
                  consumed = true;
               } else if (target.kind() == FastPlaceClientPreview.OperationPointerKind.FACE) {
                  beginOperationFaceAdjustment(minecraft, -1, 0);
                  // A face hit is an adjustment target, never a rollback target.
                  operationClickCapturedButton = 0;
                  consumed = true;
               }
            } else if (operationClickCapturedButton == 0 || operationDrag != null && operationDrag.mouseButton() == 0) {
               consumed = true;
            } else if (geometryClickCapturedButton == 0) {
               consumed = true;
            } else if (geometryGizmoDrag != null) {
               consumed = true;
            } else if (FastPlaceClientPreview.geometryActive() && beginGeometryGizmoDrag(minecraft, 0)) {
               UNDO_PRESS.cancel();
               undoPressCaptured = false;
               consumed = true;
            } else if (FastPlaceClientPreview.geometryActive() && beginGeometryInteraction(minecraft, 0)) {
               UNDO_PRESS.cancel();
               undoPressCaptured = false;
               consumed = true;
            } else if (FastPlaceClientPreview.operationActive()) {
               operationClickCapturedButton = 0;
               consumed = true;
            } else {
               if (NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())) {
                  UNDO_PRESS.press(System.nanoTime());
                  undoPressCaptured = true;
                  consumed = true;
               }
            }
         } else if (event.getAction() == 0) {
            if (operationPointDrag != null && operationPointDrag.mouseButton() == 0) {
               OperationPointDrag finished = finishOperationPointDrag(minecraft);
               finishOperationPointClick(minecraft, finished);
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (operationDrag != null && operationDrag.mouseButton() == 0) {
               finishOperationDrag(minecraft);
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (workspaceGizmoDrag != null && workspaceGizmoDrag.mouseButton() == 0) {
               finishWorkspaceGizmoDrag();
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (workspaceFaceDrag != null && workspaceFaceDrag.mouseButton() == 0) {
               finishWorkspaceFaceDrag();
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (geometryGizmoDrag != null && geometryGizmoDrag.mouseButton() == 0) {
               finishGeometryGizmoDrag(minecraft);
               UNDO_PRESS.cancel();
               undoPressCaptured = false;
               consumed = true;
            } else if (operationClickCapturedButton == 0) {
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (geometryClickCapturedButton == 0) {
               geometryClickCapturedButton = -1;
               consumed = true;
            } else if (undoPressCaptured) {
               boolean shortPress = UNDO_PRESS.release(System.nanoTime(), UNDO_SHORT_PRESS_NANOS);
               undoPressCaptured = false;
               if (shortPress && NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())) {
                  PacketDistributor.sendToServer(UndoFastPlacePayload.INSTANCE, new CustomPacketPayload[0]);
               }
               consumed = true;
            }
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && FastPlaceClientPreview.geometryActive()) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            if (InteractionContext.nearVanillaBlock(minecraft)) {
               return;
            }
            if (geometryClickCapturedButton == 1) {
               consumed = true;
            } else {
               consumed = handleGeometryRightClick(minecraft);
            }
         } else if (event.getAction() == 0
            && geometryClickCapturedButton == 1) {
            geometryClickCapturedButton = -1;
            consumed = true;
         } else if (event.getAction() == 0
            && geometryGizmoDrag != null
            && geometryGizmoDrag.mouseButton() == 1) {
            finishGeometryGizmoDrag(minecraft);
            consumed = true;
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && FastPlaceClientPreview.operationActive()) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            OperationInteractionIntent.Gizmo workspaceGizmoHit =
               pointerIntent instanceof OperationInteractionIntent.Gizmo gizmo ? gizmo : null;
            OperationInteractionIntent.Face workspaceFaceHit =
               pointerIntent instanceof OperationInteractionIntent.Face face ? face : null;
            int workspacePartHit = pointerIntent instanceof OperationInteractionIntent.Part part ? part.partId() : 0;
            if (workspaceGizmoHit != null) {
               beginWorkspaceGizmoDrag(minecraft, workspaceGizmoHit, 1);
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (workspaceFaceHit != null) {
               selectOrBeginWorkspaceFace(minecraft, workspaceFaceHit, 1, 1);
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (workspacePartHit > 0) {
               if (physicalCtrlDown(minecraft, false)) {
                  ClientOperationController.workspace().toggleSelected(workspacePartHit);
               } else {
                  ClientOperationController.workspace().selectOnly(workspacePartHit);
               }
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (ClientOperationController.active()
               && adjustWorkspaceSelectionAtCrosshair(minecraft, 1)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (FastPlaceClientPreview.operationSelectionConfirmed()) {
               if (InteractionContext.nearVanillaBlock(minecraft)) {
                  return;
               }
               beginOperationGizmoDrag(minecraft, 1);
               operationClickCapturedButton = 1;
               consumed = true;
            } else {
            boolean operationAdjust = FastPlaceClientPreview.operationSelectionReady()
               && operationAdjustModifierHeld();
            boolean operationAlt = modifierHeld();
            boolean operationPointTarget = FastPlaceClientPreview.operationPrism()
               && FastPlaceClientPreview.operationPointUnderCrosshairIndex() >= 0;
            if (InteractionContext.nearVanillaBlock(minecraft) && !operationAdjust && !operationAlt && !operationPointTarget) {
               return;
            }
            if (operationAlt) {
               boolean handled = beginOperationGizmoDrag(minecraft, 1);
               if (handled) {
                  modifierUsedForInput = true;
               }
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (operationAdjust) {
               if (beginOperationGizmoDrag(minecraft, 1)
                  || sendOperationPointSelection(minecraft)
                  || beginOperationFaceAdjustment(minecraft, 1, 1)) {
                  modifierUsedForInput = true;
               }
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (FastPlaceClientPreview.operationPrism() && beginOperationPointDrag(minecraft, 1)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (!InteractionContext.nearVanillaBlock(minecraft)
               && FastPlaceClientPreview.operationPrism()
               && sendOperationEdgeInsertion(minecraft)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (!InteractionContext.nearVanillaBlock(minecraft)
               && FastPlaceClientPreview.operationPrism()
               && sendNextOperationPrismPoint(minecraft)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (FastPlaceClientPreview.operationCuboid()) {
               FastPlaceClientPreview.OperationPointerTarget target = FastPlaceClientPreview.operationPointerTarget();
               if (target.kind() == FastPlaceClientPreview.OperationPointerKind.WORLD) {
                  PacketDistributor.sendToServer(
                     new OperationPointPayload(OperationPointPayload.Role.SECOND), new CustomPacketPayload[0]
                  );
                  operationClickCapturedButton = 1;
                  consumed = true;
               } else if (target.kind() == FastPlaceClientPreview.OperationPointerKind.FACE) {
                  consumed = beginOperationFaceAdjustment(minecraft, 1, 1);
                  operationClickCapturedButton = 1;
               }
            } else if (FastPlaceClientPreview.operationPrismBaseOpen() && tryClosePathGesture(minecraft, false)) {
               consumed = true;
            }
            }
         } else if (event.getAction() == 0) {
            if (operationPointDrag != null && operationPointDrag.mouseButton() == 1) {
               OperationPointDrag finished = finishOperationPointDrag(minecraft);
               finishOperationPointClick(minecraft, finished);
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (operationDrag != null && operationDrag.mouseButton() == 1) {
               finishOperationDrag(minecraft);
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (workspaceGizmoDrag != null && workspaceGizmoDrag.mouseButton() == 1) {
               finishWorkspaceGizmoDrag();
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (workspaceFaceDrag != null && workspaceFaceDrag.mouseButton() == 1) {
               finishWorkspaceFaceDrag();
               operationClickCapturedButton = -1;
               consumed = true;
            } else if (geometryGizmoDrag != null && geometryGizmoDrag.mouseButton() == 1) {
               finishGeometryGizmoDrag(minecraft);
               consumed = true;
            } else if (operationClickCapturedButton == 1) {
               operationClickCapturedButton = -1;
               consumed = true;
            }
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && FastPlaceClientPreview.active()) {
         if (event.getAction() == 1 && !InteractionContext.nearVanillaBlock(minecraft) && tryClosePathGesture(minecraft, false)) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 2
         && FastPlaceClientPreview.operationActive()
         && ClientOperationController.active()) {
         if (event.getAction() == 1 && adjustWorkspaceSelectionAtCrosshair(minecraft, 2)) {
            operationClickCapturedButton = 2;
            event.setCanceled(true);
         } else if (event.getAction() == 0 && workspaceFaceDrag != null && workspaceFaceDrag.mouseButton() == 2) {
            finishWorkspaceFaceDrag();
            operationClickCapturedButton = -1;
            event.setCanceled(true);
         } else if (event.getAction() == 0 && operationClickCapturedButton == 2) {
            operationClickCapturedButton = -1;
            event.setCanceled(true);
         }
      } else if (event.getButton() == 2
         && event.getAction() == 1
         && FastPlaceClientPreview.active()
         && FastPlaceClientPreview.middleConfirmEnabled()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), QuickShapePayload.TYPE.id())) {
         PacketDistributor.sendToServer(QuickShapePayload.INSTANCE, new CustomPacketPayload[0]);
         event.setCanceled(true);
      } else if (event.getButton() == 2
         && event.getAction() == 1
         && FastPlaceClientPreview.operationActive()
         && !modifierHeld()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         BlockPos point = FastPlaceClientPreview.operationCandidatePoint();
         if (point != null) {
            PacketDistributor.sendToServer(new OperationPointPayload(OperationPointPayload.Role.EXTRA), new CustomPacketPayload[0]);
            event.setCanceled(true);
         }
      }
   }

   @SubscribeEvent
   public static void onClientTick(Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      pollModifierKeys(minecraft);
      if (minecraft.player == null || minecraft.getConnection() == null) {
         if (operationSessionWasActive) {
            minecraft.options.keyAttack.setDown(false);
         }
         operationSessionWasActive = false;
         operationDrag = null;
         operationPointDrag = null;
         operationClickCapturedButton = -1;
         geometryGizmoDrag = null;
         geometryClickCapturedButton = -1;
         UNDO_PRESS.cancel();
         undoPressCaptured = false;
         return;
      }
      boolean operationActive = FastPlaceClientPreview.operationActive();
      if (operationSessionWasActive && !operationActive) {
         minecraft.options.keyAttack.setDown(false);
      }
      operationSessionWasActive = operationActive;
      if (!activeSession()) {
         operationDrag = null;
         operationPointDrag = null;
         resetOperationPointClicks();
         UNDO_PRESS.cancel();
         undoPressCaptured = false;
         geometryClickCapturedButton = -1;
         operationClickCapturedButton = -1;
         workspaceGizmoDrag = null;
         workspaceFaceDrag = null;
      }
      if (operationDrag == null && operationPointDrag == null
         && geometryGizmoDrag == null && workspaceGizmoDrag == null && workspaceFaceDrag == null) {
         return;
      }

      if (workspaceFaceDrag != null) {
         updateWorkspaceFaceDrag(minecraft);
         return;
      }

      if (workspaceGizmoDrag != null) {
         updateWorkspaceGizmoDrag(minecraft);
         return;
      }

      if (geometryGizmoDrag != null) {
         updateGeometryGizmoDrag(minecraft);
      }
      if (operationPointDrag != null) {
         updateOperationPointDrag(minecraft);
      }
      if (operationDrag == null) {
         return;
      }
      if (!FastPlaceClientPreview.operationActive()) {
         operationDrag = null;
         operationPointDrag = null;
         return;
      }
      long now = System.nanoTime();
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(
         operationDrag.frame().origin(), operationDrag.normal(), eye, view
      );
      int projectedSteps = operationDrag.frame().project(axisPoint, operationDrag.normal());
      if (operationDrag.faceHit() != null
         && operationDrag.deferredClick().awaitingRelease(now, OPERATION_FACE_SHORT_PRESS_NANOS)) {
         return;
      }
      if (operationDrag.deferredClick().steps() != 0) {
         operationDrag = operationDrag.withDeferredClick(operationDrag.deferredClick().cancel());
      }
      if (operationDrag.faceHit() != null && FastPlaceClientPreview.operationCuboid()) {
         OperationSelectionVolume selection = FastPlaceClientPreview.operationSelection();
         boolean inside = selection != null
            && selection.bounds().inflate(OperationSelectionVolume.RAYCAST_INFLATE).contains(eye);
         if (inside != operationDrag.frame().reversed()) {
            operationDrag = operationDrag.withFrame(
               operationDrag.frame().rebase(axisPoint, projectedSteps, inside)
            );
         }
      }
      int totalSteps = operationDrag.frame().project(axisPoint, operationDrag.normal());
      int delta = totalSteps - operationDrag.sentSteps();
      if (delta != 0 && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         int clippedDelta = clampDragSteps(delta);
         PacketDistributor.sendToServer(
            new OperationExtendPayload(operationDrag.axis(), operationDrag.positive(), clippedDelta, false),
            new CustomPacketPayload[0]
         );
         operationDrag = operationDrag.withSentSteps(operationDrag.sentSteps() + clippedDelta);
         if (operationDrag.gizmoKey() != null) {
            FastPlaceClientPreview.noteGizmoFeedback(
               operationDrag.gizmoKey().axis(),
               AxisGizmo.Operation.MOVE,
               operationDrag.sentSteps(),
               operationDrag.gizmoBaseValue()
            );
         }
      }
   }

   private static void updateOperationPointDrag(Minecraft minecraft) {
      OperationPointDrag drag = operationPointDrag;
      if (drag == null || !FastPlaceClientPreview.operationActive() || FastPlaceClientPreview.operationSelectionConfirmed()) {
         operationPointDrag = null;
         return;
      }
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      OperationPointDragConstraint constraint = selectOperationPointDragConstraint(drag, eye, view);
      if (constraint != drag.constraint()) {
         drag = rebaseOperationPointDragConstraint(drag, constraint, eye, view);
         operationPointDrag = drag;
      }
      BlockPos desired = constraint == OperationPointDragConstraint.LINE
         ? lineDragTarget(drag, eye, view)
         : planeDragTarget(drag, eye, view);
      if (desired == null) {
         return;
      }
      BlockPos target = nextPointDragTarget(drag.sentTarget(), desired, drag, constraint);
      if (target.equals(drag.sentTarget())
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         operationPointDrag = drag.withConstraint(constraint);
         return;
      }
      PacketDistributor.sendToServer(
         new OperationPointDragPayload(drag.pointIndex(), target, constraint, false), new CustomPacketPayload[0]
      );
      operationPointDrag = drag.withSentTarget(target).withConstraint(constraint);
   }

   private static BlockPos nextPointDragTarget(
      BlockPos current, BlockPos desired, OperationPointDrag drag, OperationPointDragConstraint constraint
   ) {
      for (int limit = MAX_DRAG_STEPS_PER_PACKET; limit >= 1; limit /= 2) {
         BlockPos candidate = new BlockPos(
            stepToward(current.getX(), desired.getX(), limit),
            stepToward(current.getY(), desired.getY(), limit),
            stepToward(current.getZ(), desired.getZ(), limit)
         );
         candidate = constrainPointDragTarget(candidate, drag, constraint);
         if (maximumCoordinateDelta(current, candidate) <= MAX_DRAG_STEPS_PER_PACKET) {
            return candidate;
         }
      }
      return current;
   }

   private static BlockPos planeDragTarget(OperationPointDrag drag, Vec3 eye, Vec3 view) {
      if (drag.plane() == null) {
         return lineDragTarget(drag, eye, view);
      }
      Vec3 intersection = drag.plane().rayIntersection(eye, view);
      if (intersection != null) {
         return drag.plane().snap(BlockPos.containing(intersection.add(drag.planeGrabOffset())));
      }

      Vec3 origin = drag.plane().anchor();
      BlockPos reference = BlockPos.containing(origin);
      int axis = OperationGeometry.closestWorldAxisToRay(origin, eye, view, drag.plane().dependentAxis());
      if (axis < 0) {
         return null;
      }
      double currentOffset = pointAxisOffset(origin, eye, view, axis);
      long steps = Math.round(currentOffset - vecAxisComponent(drag.axisBaselines(), axis));
      return drag.plane().snap(withAxisCoordinate(
         reference, axis, safeCoordinate(axisCoordinate(reference, axis), steps)
      ));
   }

   private static BlockPos lineDragTarget(OperationPointDrag drag, Vec3 eye, Vec3 view) {
      return drag.line() == null
         ? null
         : drag.line().pointAtOffset(drag.line().rayOffset(eye, view) - drag.lineGrabBaseline());
   }

   private static BlockPos constrainPointDragTarget(
      BlockPos target, OperationPointDrag drag, OperationPointDragConstraint constraint
   ) {
      if (constraint == OperationPointDragConstraint.LINE && drag.line() != null) {
         return drag.line().snap(target);
      }
      return drag.plane() == null ? target : drag.plane().snap(target);
   }

   private static OperationPointDragConstraint selectOperationPointDragConstraint(
      OperationPointDrag drag, Vec3 eye, Vec3 view
   ) {
      if (drag.line() == null) {
         return drag.plane() == null ? OperationPointDragConstraint.FREE : OperationPointDragConstraint.PLANE;
      }
      if (drag.plane() == null) {
         return OperationPointDragConstraint.LINE;
      }
      Vec3 ray = normalize(view);
      SelectionPrism.GridLine targetingLine = drag.plane() == null
         ? drag.line()
         : drag.line().through(drag.initialPoint());
      Vec3 linePoint = OperationGeometry.closestPointOnAxisToRay(
         targetingLine.anchor(), targetingLine.direction(), eye, ray
      );
      double rayDistance = Math.max(0.0, linePoint.subtract(eye).dot(ray));
      double distanceSqr = linePoint.distanceToSqr(eye.add(ray.scale(rayDistance)));
      double radius = Math.clamp(rayDistance * 0.012, 0.35, 1.25);
      if (drag.constraint() == OperationPointDragConstraint.LINE) {
         radius *= 1.2;
      }
      return distanceSqr <= radius * radius ? OperationPointDragConstraint.LINE : OperationPointDragConstraint.PLANE;
   }

   private static OperationPointDrag rebaseOperationPointDragConstraint(
      OperationPointDrag drag, OperationPointDragConstraint constraint, Vec3 eye, Vec3 view
   ) {
      if (drag.plane() == null || drag.line() == null) {
         return drag.withConstraint(constraint);
      }
      if (constraint == OperationPointDragConstraint.LINE) {
         SelectionPrism.GridLine line = drag.line().through(drag.sentTarget());
         double baseline = line.rayOffset(eye, view) - line.offset(drag.sentTarget());
         return drag.withLineFrame(line, baseline, constraint);
      }
      SelectionPrism.GridPlane plane = drag.plane().through(drag.sentTarget());
      Vec3 intersection = plane.rayIntersection(eye, view);
      Vec3 grabOffset = intersection == null
         ? Vec3.ZERO
         : Vec3.atCenterOf(drag.sentTarget()).subtract(intersection);
      Vec3 center = Vec3.atCenterOf(drag.sentTarget());
      Vec3 axisBaselines = new Vec3(
         pointAxisOffset(center, eye, view, 0),
         pointAxisOffset(center, eye, view, 1),
         pointAxisOffset(center, eye, view, 2)
      );
      return drag.withPlaneFrame(plane, grabOffset, axisBaselines, constraint);
   }

   private static int stepToward(int current, int target, int limit) {
      long delta = Math.clamp((long)target - current, -(long)limit, (long)limit);
      return (int)((long)current + delta);
   }

   private static long maximumCoordinateDelta(BlockPos first, BlockPos second) {
      return Math.max(
         Math.max(Math.abs((long)second.getX() - first.getX()), Math.abs((long)second.getY() - first.getY())),
         Math.abs((long)second.getZ() - first.getZ())
      );
   }

   private static int safeCoordinate(int initial, long offset) {
      return (int)Math.clamp((long)initial + offset, Integer.MIN_VALUE + 1L, Integer.MAX_VALUE - 1L);
   }

   private static int axisCoordinate(BlockPos point, int axis) {
      return axis == 0 ? point.getX() : axis == 1 ? point.getY() : point.getZ();
   }

   private static BlockPos withAxisCoordinate(BlockPos point, int axis, int coordinate) {
      return switch (axis) {
         case 0 -> new BlockPos(coordinate, point.getY(), point.getZ());
         case 1 -> new BlockPos(point.getX(), coordinate, point.getZ());
         case 2 -> new BlockPos(point.getX(), point.getY(), coordinate);
         default -> point;
      };
   }

   @SubscribeEvent
   public static void onMouseScroll(MouseScrollingEvent event) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null
         && minecraft.screen == null
         && ClientOperationController.active()
         && event.getScrollDeltaY() != 0.0) {
         int steps = event.getScrollDeltaY() > 0.0 ? 1 : -1;
         BlockPos offset = OperationGeometry.viewAxisStep(minecraft.player.getViewVector(1.0F), steps);
         if (ClientOperationController.moveSelected(offset)) {
            event.setCanceled(true);
         }
         return;
      }
      if (minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && FastPlaceClientPreview.usesScrollContext()
         && event.getScrollDeltaY() != 0.0
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ScrollCandidatePayload.TYPE.id())) {
         if (modifierInputRoute(ModifierInput.SCROLL) == ModifierInputRoute.VANILLA) {
            modifierUsedForInput = true;
            return;
         }
         if (InteractionContext.nearVanillaBlock(minecraft)) {
            return;
          }
          PacketDistributor.sendToServer(new ScrollCandidatePayload(event.getScrollDeltaY() > 0.0 ? 1 : -1), new CustomPacketPayload[0]);
          FastPlaceClientPreview.noteScrollFeedback();
          if (altDown) {
             modifierUsedForInput = true;
          }
         event.setCanceled(true);
      }
   }

   private static ModifierInputRoute modifierInputRoute(ModifierInput input) {
      if (FastPlaceClientPreview.operationSelectionConfirmed()) {
         return ModifierInputRoute.SESSION;
      }
      return altDown && input == ModifierInput.SCROLL
         ? ModifierInputRoute.VANILLA
         : ModifierInputRoute.SESSION;
   }

   private static boolean beginOperationDrag(Minecraft minecraft, int mouseButton, int shortPressSteps) {
      OperationSelectionVolume selection = FastPlaceClientPreview.operationSelection();
      if (selection == null
         || shortPressSteps == 0
         || !FastPlaceClientPreview.operationSelectionReady()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         return false;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      OperationGeometry.RayHit hit = FastPlaceClientPreview.operationCuboid()
         ? FastPlaceClientPreview.operationFaceHit()
         : selection.raycast(eye, minecraft.player.getViewVector(1.0F), OPERATION_REACH);
      if (hit == null) {
         return false;
      }

      Vec3 normal = hit.normal();
      int axis = hit.axis();
      boolean positive = normal.dot(selection.axis(axis)) > 0.0;
      if (!FastPlaceClientPreview.operationCuboid() && (axis != 2 || !positive)) {
         return false;
      }
      boolean reverseInside = FastPlaceClientPreview.operationCuboid() && selection.contains(eye);
      int deferredSteps = reverseInside ? -shortPressSteps : shortPressSteps;
      operationDrag = new OperationDrag(
         axis,
         positive,
         DragAxisFrame.start(hit.point(), reverseInside),
         normal,
         0,
         mouseButton,
         hit,
         null,
         0.0,
         DeferredDragClick.start(System.nanoTime(), deferredSteps)
      );
      return true;
   }

   private static boolean beginOperationGizmoDrag(Minecraft minecraft, int mouseButton) {
      OperationInteractionIntent.Gizmo workspaceTarget = FastPlaceClientPreview.operationWorkspaceGizmoHit();
      if (workspaceTarget != null) {
         return beginWorkspaceGizmoDrag(minecraft, workspaceTarget, mouseButton);
      }
      AxisGizmo gizmo = FastPlaceClientPreview.operationGizmo();
      AxisGizmo.Hit hit = FastPlaceClientPreview.operationGizmoHit();
      if (gizmo == null || hit == null) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      if (FastPlaceClientPreview.operationSelectionReady()) {
         return beginConfirmedOperationGizmoDrag(minecraft, gizmo, hit, mouseButton);
      }
      int axis = geometryAxisIndex(handle.axis());
      boolean positive = handle.direction() != AxisGizmo.Direction.NEGATIVE;
      Vec3 vector = gizmo.axisVector(handle.axis()).scale(positive ? 1.0 : -1.0);
      int encodedAxis = handle.operation() == AxisGizmo.Operation.MOVE
         ? axis + (FastPlaceClientPreview.operationPointSelected() ? 6 : 3)
         : axis;
      operationDrag = new OperationDrag(
         encodedAxis,
         positive,
         DragAxisFrame.start(hit.point(), !positive),
         vector,
         0,
         mouseButton,
         null,
         handle.key(),
         axisComponent(gizmo.center(), handle.axis()),
         DeferredDragClick.none()
      );
      return true;
   }

   private static boolean beginWorkspaceGizmoDrag(
      Minecraft minecraft, OperationInteractionIntent.Gizmo target, int mouseButton
   ) {
      if (ClientOperationController.workspaceSubmissionPending()) {
         return false;
      }
      AxisGizmo.Handle handle = target.hit().handle();
      var workspace = ClientOperationController.workspace();
      if (!target.common()) {
         boolean selected = workspace.selectedIds().contains(target.partId());
         boolean control = physicalCtrlDown(minecraft, false);
         if (!selected) {
            if (control) {
               workspace.toggleSelected(target.partId());
            } else {
               workspace.selectOnly(target.partId());
            }
         }
         workspace.activate(target.partId());
      }
      if (!workspace.beginEdit()) {
         return false;
      }
      Vec3 axis = target.gizmo().axisVector(handle.axis());
      if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
         axis = axis.scale(-1.0);
      }
      Vec3 radial = handle.drawsRing() ? target.hit().point().subtract(target.gizmo().center()) : Vec3.ZERO;
      if (handle.drawsRing() && radial.lengthSqr() < 1.0E-7) {
         ClientOperationController.cancelTransformGesture();
         return false;
      }
      workspaceGizmoDrag = new WorkspaceGizmoDrag(
         target.partId(), target.common(), handle.operation(), handle.axis(),
         target.hit().point(), axis, 0, target.gizmo().center(),
         handle.drawsRing() ? radial.normalize() : Vec3.ZERO,
         handle.drawsRing() ? rotationTangent(axis, radial.normalize()) : Vec3.ZERO,
         handle.direction(), mouseButton, java.util.List.copyOf(workspace.selectedParts())
      );
      return true;
   }

   private static boolean beginWorkspaceFaceDrag(
      Minecraft minecraft, OperationInteractionIntent.Face target, int mouseButton, int shortPressSteps
   ) {
      if (ClientOperationController.workspaceSubmissionPending()) {
         return false;
      }
      if (target == null || !target.adjustable()) {
         return false;
      }
      var workspace = ClientOperationController.workspace();
      ClientSelectionPart part = workspace.part(target.partId()).orElse(null);
      if (!ClientOperationController.sourceSnapshotMatches(part)) {
         return false;
      }
      if (!workspace.selectedIds().contains(part.id())) {
         workspace.selectOnly(part.id());
      }
      workspace.activate(part.id());
      if (!workspace.beginEdit()) {
         return false;
      }
      OperationGeometry.RayHit hit = target.hit();
      int axis = hit.axis();
      Vec3 worldAxis = worldAxis(axis);
      boolean positive = hit.normal().dot(worldAxis) > 0.0;
      workspaceFaceDrag = new WorkspaceFaceDrag(
         part, axis, positive, DragAxisFrame.start(hit.point(), false), hit.normal(), 0,
         mouseButton, DeferredDragClick.start(System.nanoTime(), shortPressSteps), hit
      );
      return true;
   }

   private static void selectOrBeginWorkspaceFace(
      Minecraft minecraft, OperationInteractionIntent.Face target, int mouseButton, int shortPressSteps
   ) {
      if (target == null) {
         return;
      }
      var workspace = ClientOperationController.workspace();
      if (!workspace.selectedIds().contains(target.partId())) {
         workspace.selectOnly(target.partId());
      }
      workspace.activate(target.partId());
      if (target.adjustable()) {
         beginWorkspaceFaceDrag(minecraft, target, mouseButton, shortPressSteps);
      }
   }

   private static boolean beginConfirmedOperationGizmoDrag(
      Minecraft minecraft, AxisGizmo gizmo, AxisGizmo.Hit hit, int mouseButton
   ) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      double baseValue = FastPlaceClientPreview.operationGizmoValue(handle.axis(), handle.operation());
      if (handle.drawsRing()) {
         Vec3 radial = hit.point().subtract(gizmo.center());
         if (radial.lengthSqr() < 1.0E-7) {
            return false;
         }
         geometryGizmoDrag = new GeometryGizmoDrag(
            handle.operation(), handle.axis(), hit.point(), gizmo.axisVector(handle.axis()), 0, baseValue,
            gizmo.center(), radial.normalize(), rotationTangent(gizmo.axisVector(handle.axis()), radial.normalize()),
            handle.direction(), mouseButton
         );
      } else {
         Vec3 axis = gizmo.axisVector(handle.axis());
         if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
            axis = axis.scale(-1.0);
         }
         geometryGizmoDrag = new GeometryGizmoDrag(
            handle.operation(), handle.axis(), hit.point(), axis, 0, baseValue,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, handle.direction(), mouseButton
         );
      }
      FastPlaceClientPreview.noteGizmoFeedback(handle.axis(), handle.operation(), 0, baseValue);
      return true;
   }

   private static boolean beginOperationPointDrag(Minecraft minecraft, int mouseButton) {
      if (!FastPlaceClientPreview.operationPrism()
         || operationDrag != null
         || operationPointDrag != null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         return false;
      }
      int pointIndex = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      Vec3 center = FastPlaceClientPreview.operationPointCenter(pointIndex);
      if (pointIndex < 0 || center == null) {
         return false;
      }
      BlockPos initialPoint = BlockPos.containing(center);
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisBaselines = new Vec3(
         pointAxisOffset(center, eye, view, 0),
         pointAxisOffset(center, eye, view, 1),
         pointAxisOffset(center, eye, view, 2)
      );
      SelectionPrism.GridPlane plane = FastPlaceClientPreview.operationPointGridPlane(pointIndex);
      SelectionPrism.GridLine line = FastPlaceClientPreview.operationPointGridLine(pointIndex);
      Vec3 planeHit = plane == null ? null : plane.rayIntersection(eye, view);
      Vec3 planeGrabOffset = planeHit == null ? Vec3.ZERO : center.subtract(planeHit);
      double lineGrabBaseline = line == null ? 0.0 : line.rayOffset(eye, view) - line.offset(initialPoint);
      operationPointDrag = new OperationPointDrag(
         pointIndex,
         mouseButton,
         initialPoint,
         initialPoint,
         plane,
         planeGrabOffset,
         line,
         lineGrabBaseline,
         axisBaselines,
         line != null && plane == null
            ? OperationPointDragConstraint.LINE
            : plane != null ? OperationPointDragConstraint.PLANE : OperationPointDragConstraint.FREE,
         System.nanoTime()
      );
      PacketDistributor.sendToServer(
         new OperationPointDragPayload(pointIndex, initialPoint, operationPointDrag.constraint(), false),
         new CustomPacketPayload[0]
      );
      return true;
   }

   private static boolean sendOperationEdgeInsertion(Minecraft minecraft) {
      if (FastPlaceClientPreview.operationPrismEdgeInsertion() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationInsertPointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(OperationInsertPointPayload.INSTANCE, new CustomPacketPayload[0]);
      return true;
   }

   private static double pointAxisOffset(Vec3 origin, Vec3 eye, Vec3 view, int axis) {
      Vec3 axisVector = worldAxis(axis);
      return OperationGeometry.closestPointOnAxisToRay(origin, axisVector, eye, view)
         .subtract(origin)
         .dot(axisVector);
   }

   private static boolean sendNextOperationPrismPoint(Minecraft minecraft) {
      if (!FastPlaceClientPreview.operationPrism()
         || FastPlaceClientPreview.operationCandidatePoint() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         return false;
      }
      OperationPointPayload.Role role = FastPlaceClientPreview.operationNeedsFirst()
         ? OperationPointPayload.Role.FIRST
         : FastPlaceClientPreview.operationNeedsSecond()
         ? OperationPointPayload.Role.SECOND
         : OperationPointPayload.Role.EXTRA;
      PacketDistributor.sendToServer(new OperationPointPayload(role), new CustomPacketPayload[0]);
      return true;
   }

   private static boolean sendOperationPointSelection(Minecraft minecraft) {
      if (!FastPlaceClientPreview.operationPrism()) {
         return false;
      }
      int index = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      if (index < 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationSelectPointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(new OperationSelectPointPayload(index), new CustomPacketPayload[0]);
      return true;
   }

   private static boolean sendOperationPointRemoval(Minecraft minecraft, int index) {
      if (index < 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationRemovePointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(new OperationRemovePointPayload(index), new CustomPacketPayload[0]);
      return true;
   }

   private static boolean beginOperationFaceAdjustment(Minecraft minecraft, int steps, int mouseButton) {
      return beginOperationDrag(minecraft, mouseButton, steps);
   }

   private static void pollModifierKeys(Minecraft minecraft) {
      if (minecraft.player == null || minecraft.getConnection() == null) {
         altDown = false;
         radialChordDown = false;
         modifierRouted = false;
         modifierCycleEligible = false;
         modifierUsedForInput = false;
         worldUndoKeyDown = false;
         worldRedoKeyDown = false;
         return;
      }
      if (!physicalCtrlDown(minecraft, false)) {
         worldUndoKeyDown = false;
         worldRedoKeyDown = false;
      }
      handleAltTransition(minecraft, physicalAltDown(minecraft, false));
      handleRadialKeyTransition(minecraft, physicalCtrlDown(minecraft, false) && physicalAltDown(minecraft, false));
   }

   private static boolean physicalAltDown(Minecraft minecraft, boolean eventPressed) {
      long window = minecraft.getWindow().getWindow();
      return eventPressed || InputConstants.isKeyDown(window, 342) || InputConstants.isKeyDown(window, 346);
   }

   private static boolean altDownAfterEvent(Minecraft minecraft, int eventKey, int action) {
      if (action == 1) {
         return true;
      }
      long window = minecraft.getWindow().getWindow();
      int otherAlt = eventKey == 342 ? 346 : 342;
      return InputConstants.isKeyDown(window, otherAlt);
   }

   private static boolean physicalCtrlDown(Minecraft minecraft, boolean eventPressed) {
      long window = minecraft.getWindow().getWindow();
      return eventPressed || InputConstants.isKeyDown(window, 341) || InputConstants.isKeyDown(window, 345);
   }

   private static boolean tryOpenGeometryRadial(Minecraft minecraft) {
      if (minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !FastPlaceClientPreview.active()
         && !FastPlaceClientPreview.operationActive()
         && (!FastPlaceClientPreview.geometryActive() || FastPlaceClientPreview.geometryAwaitingFirstPoint())) {
         minecraft.setScreen(new GeometryRadialScreen());
         return true;
      }
      return false;
   }

   private static boolean beginGeometryGizmoDrag(Minecraft minecraft, int mouseButton) {
      if (!FastPlaceClientPreview.geometryAllows(GeometryAction.GIZMO_DRAG)
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id())) {
         return false;
      }
      AxisGizmo.Hit hit = FastPlaceClientPreview.geometryGizmoHit();
      if (hit == null) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      AxisGizmo gizmo = FastPlaceClientPreview.geometryGizmo();
      if (gizmo == null) {
         return false;
      }
      double baseValue = FastPlaceClientPreview.geometryGizmoValue(handle.axis(), handle.operation());
      if (handle.drawsRing()) {
         Vec3 radial = hit.point().subtract(gizmo.center());
         if (radial.lengthSqr() < 1.0E-7) {
            return false;
         }
          geometryGizmoDrag = new GeometryGizmoDrag(
             handle.operation(),
            handle.axis(),
             hit.point(),
             gizmo.axisVector(handle.axis()),
             0,
             baseValue,
             gizmo.center(),
             radial.normalize(),
             rotationTangent(gizmo.axisVector(handle.axis()), radial.normalize()),
             handle.direction(),
             mouseButton
          );
          FastPlaceClientPreview.noteGizmoFeedback(handle.axis(), handle.operation(), 0, baseValue);
          return true;
      }
      if (!handle.drawsEndpoint()) {
         return false;
      }
      Vec3 axis = gizmo.axisVector(handle.axis());
      if (handle.operation() == AxisGizmo.Operation.SCALE && handle.direction() == AxisGizmo.Direction.NEGATIVE) {
         axis = axis.scale(-1.0);
      }
      geometryGizmoDrag = new GeometryGizmoDrag(
         handle.operation(), handle.axis(), hit.point(), axis, 0, baseValue, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
         handle.direction(), mouseButton
      );
      FastPlaceClientPreview.noteGizmoFeedback(handle.axis(), handle.operation(), 0, baseValue);
      return true;
   }

   private static boolean beginGeometryInteraction(Minecraft minecraft, int mouseButton) {
      if (!FastPlaceClientPreview.geometryActive()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id())) {
         return false;
      }

      PointerGesture gesture = mouseButton == 0 ? PointerGesture.LEFT_CLICK : PointerGesture.RIGHT_CLICK;
      GeometryInteractionHit hit = FastPlaceClientPreview.geometryInteractionHit();
      GeometryInteractionAction action = hit == null ? null : hit.target().action(gesture);
      if (action != null) {
         sendGeometryInteraction(hit.target(), action, gesture, mouseButton);
         return true;
      }
      if (FastPlaceClientPreview.geometryPointSelected()) {
         sendGeometryClearSelection(gesture, mouseButton);
         return true;
      }
      return false;
   }

   private static void sendGeometryInteraction(
      GeometryInteractionTarget target,
      GeometryInteractionAction action,
      PointerGesture gesture,
      int mouseButton
   ) {
      PacketDistributor.sendToServer(
         new GeometryInteractionPayload(target.type(), target.index(), action, gesture),
         new CustomPacketPayload[0]
      );
      geometryClickCapturedButton = mouseButton;
   }

   private static void sendGeometryClearSelection(PointerGesture gesture, int mouseButton) {
      PacketDistributor.sendToServer(
         GeometryInteractionPayload.clearSelection(gesture),
         new CustomPacketPayload[0]
      );
      geometryClickCapturedButton = mouseButton;
   }

   private static boolean sendGeometryPointInput(Minecraft minecraft, int mouseButton) {
      if (!(minecraft.hitResult instanceof BlockHitResult)
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryPointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(GeometryPointPayload.INSTANCE, new CustomPacketPayload[0]);
      geometryClickCapturedButton = mouseButton;
      return true;
   }

   private static boolean handleGeometryRightClick(Minecraft minecraft) {
      if (geometryClickCapturedButton == 1) {
         return true;
      }
      return geometryGizmoDrag != null
         || beginGeometryGizmoDrag(minecraft, 1)
         || beginGeometryInteraction(minecraft, 1)
         || tryClosePathGesture(minecraft, true)
         || sendGeometryPointInput(minecraft, 1);
   }

   private static boolean tryClosePathGesture(Minecraft minecraft, boolean geometrySession) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
         resetPathDoubleClick();
         return false;
      }
      if (FastPlaceClientPreview.closePathAtHoveredStart()) {
         PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
         resetPathDoubleClick();
         return true;
      }
      if (!FastPlaceClientPreview.canDoubleClickClosePath()) {
         resetPathDoubleClick();
         return false;
      }

      BlockPos candidate = FastPlaceClientPreview.pathCandidatePoint();
      long now = System.nanoTime();
      boolean doubleClick = candidate != null
         && candidate.equals(lastPathClickPoint)
         && geometrySession == lastPathClickGeometry
         && now - lastPathClickAt <= PATH_DOUBLE_CLICK_NANOS;
      lastPathClickAt = now;
      lastPathClickPoint = candidate;
      lastPathClickGeometry = geometrySession;
      if (!doubleClick) {
         return false;
      }

      PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
      resetPathDoubleClick();
      return true;
   }

   private static void resetPathDoubleClick() {
      lastPathClickAt = 0L;
      lastPathClickPoint = null;
   }

   private static void resetOperationPointClicks() {
      lastOperationPointLeftClickAt = 0L;
      lastOperationPointLeftClickIndex = -1;
      lastOperationPointRightClickAt = 0L;
      lastOperationPointRightClickIndex = -1;
   }

   private static void updateGeometryGizmoDrag(Minecraft minecraft) {
      boolean operationTransform = FastPlaceClientPreview.operationSelectionReady();
      if (!FastPlaceClientPreview.geometryActive() && !operationTransform) {
         geometryGizmoDrag = null;
         return;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      int totalSteps = geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
         ? geometryRotationSteps(geometryGizmoDrag, eye, view)
         : operationTransform
            ? operationEndpointSteps(geometryGizmoDrag, eye, view)
            : geometryEndpointSteps(geometryGizmoDrag, eye, view);
      if (operationTransform && geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE) {
         totalSteps = operationRotationSteps(minecraft, totalSteps);
      }
      int delta = totalSteps - geometryGizmoDrag.sentSteps();
      if (delta != 0 && (operationTransform
         ? NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())
         : NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id()))) {
         int clippedDelta = clampDragSteps(delta);
         int operationTotal = geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
            ? Math.clamp(totalSteps, -128, 128)
            : Math.clamp(totalSteps, 0, 128);
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               geometryOperationIndex(geometryGizmoDrag.operation()),
               geometryAxisIndex(geometryGizmoDrag.axis()),
               gizmoDirection(geometryGizmoDrag),
               operationTotal,
               false
            )
            : new GeometryGizmoDragPayload(
               geometryOperationIndex(geometryGizmoDrag.operation()),
               geometryAxisIndex(geometryGizmoDrag.axis()),
               clippedDelta,
               false
            );
         PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]);
          geometryGizmoDrag = geometryGizmoDrag.withSentSteps(geometryGizmoDrag.sentSteps() + clippedDelta);
          FastPlaceClientPreview.noteGizmoFeedback(
             geometryGizmoDrag.axis(), geometryGizmoDrag.operation(), geometryGizmoDrag.sentSteps(), geometryGizmoDrag.baseValue()
          );
      }
   }

   private static void updateWorkspaceGizmoDrag(Minecraft minecraft) {
      WorkspaceGizmoDrag drag = workspaceGizmoDrag;
      if (drag == null || minecraft.player == null) {
         return;
      }
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      GeometryGizmoDrag geometry = new GeometryGizmoDrag(
         drag.operation(), drag.axis(), drag.origin(), drag.axisVector(), drag.sentSteps(), 0.0,
         drag.center(), drag.startRadial(), drag.startTangent(), drag.direction(), drag.mouseButton()
      );
      int totalSteps;
      double rotationRadians = Double.NaN;
      if (drag.operation() == AxisGizmo.Operation.ROTATE) {
         int rawSteps = geometryRotationSteps(geometry, eye, view);
         rotationRadians = operationRotationRadians(minecraft, rawSteps);
         totalSteps = rotationSteps(rotationRadians);
      } else {
         double rawOffset = operationEndpointOffset(geometry, eye, view);
         totalSteps = workspaceScaleUsesRepeat(drag)
            ? RepeatDragQuantizer.copiesForOffset(rawOffset, workspaceRepeatUnit(drag))
            : (int)Math.round(rawOffset);
         totalSteps = Math.clamp(totalSteps, -128, 128);
      }
      if (totalSteps == drag.sentSteps()) {
         return;
      }
      int direction = drag.direction() == AxisGizmo.Direction.NEGATIVE ? -1 : 1;
      ClientOperationController.updateTransformGesture(
         drag.baseline(), drag.common(), drag.operation(), drag.axis(), direction, totalSteps, rotationRadians
      );
      workspaceGizmoDrag = drag.withSentSteps(totalSteps);
      if (altDown) {
         modifierUsedForInput = true;
      }
   }

   private static boolean workspaceScaleUsesRepeat(WorkspaceGizmoDrag drag) {
      return drag.operation() == AxisGizmo.Operation.SCALE
         && drag.baseline().stream().noneMatch(part -> part.selection() != null && part.selection().prism() != null);
   }

   private static int workspaceRepeatUnit(WorkspaceGizmoDrag drag) {
      if (drag.operation() != AxisGizmo.Operation.SCALE
         || drag.baseline().stream().anyMatch(part -> part.selection() != null && part.selection().prism() != null)) {
         return 1;
      }
      if (!drag.common()) {
         ClientSelectionPart target = drag.baseline().stream()
            .filter(part -> part.id() == drag.partId())
            .findFirst()
            .orElse(null);
         if (target == null) {
            return 1;
         }
         return RepeatDragQuantizer.structureExtent(target, drag.axis());
      }
      return drag.baseline().stream()
         .map(io.github.fastformer.client.operation.WorkspacePreviewComposer::resolve)
         .filter(values -> !values.isEmpty())
         .map(values -> io.github.fastformer.client.operation.OccupiedBlockBounds.from(values.keySet()).orElseThrow())
         .reduce(io.github.fastformer.client.operation.OccupiedBlockBounds::union)
         .map(bounds -> bounds.width(drag.axis()))
         .orElse(1);
   }

   private static void updateWorkspaceFaceDrag(Minecraft minecraft) {
      WorkspaceFaceDrag drag = workspaceFaceDrag;
      if (drag == null || minecraft.player == null) {
         return;
      }
      long now = System.nanoTime();
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(
         drag.frame().origin(), drag.normal(), minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)
      );
      int projectedSteps = drag.frame().project(axisPoint, drag.normal());
      if (drag.deferredClick().awaitingRelease(now, OPERATION_FACE_SHORT_PRESS_NANOS)) {
         return;
      }
      if (drag.deferredClick().steps() != 0) {
         drag = drag.withDeferredClick(drag.deferredClick().cancel());
      }
      int totalSteps = drag.frame().project(axisPoint, drag.normal());
      if (totalSteps != drag.sentSteps()) {
         ClientOperationController.updateAabbFaceGesture(
            drag.baseline(), drag.axis(), drag.positive(), totalSteps
         );
         drag = drag.withSentSteps(totalSteps);
      }
      workspaceFaceDrag = drag;
   }

   public static boolean precisionHudActive() {
      return modifierReticleMode() != ModifierReticleMode.NONE;
   }

   public static boolean modifierHeld() {
      return altDown;
   }

   public static boolean controlHeld() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player != null && physicalCtrlDown(minecraft, false);
   }

   public static boolean operationAdjustModifierHeld() {
      return altDown;
   }

   private enum ModifierInput {
      SCROLL
   }

   private enum ModifierInputRoute {
      SESSION,
      VANILLA
   }

   public static ModifierReticleMode modifierReticleMode() {
      if (!altDown || radialChordDown || !modifierSubmodeAvailable()) {
         return ModifierReticleMode.NONE;
      }
      if (FastPlaceClientPreview.embeddedModifierReticle()) {
         return ModifierReticleMode.EMBEDDED;
      }
      if (FastPlaceClientPreview.halfGridModifierReticle()) {
         return ModifierReticleMode.HALF_GRID;
      }
      return ModifierReticleMode.NONE;
   }

   public static OperationGeometry.RayHit operationFaceDragHit() {
      return operationDrag == null ? null : operationDrag.faceHit();
   }

   public static OperationGeometry.RayHit workspaceFaceDragHit() {
      return workspaceFaceDrag == null ? null : workspaceFaceDrag.hit();
   }

   public static int workspaceFaceDragPartId() {
      return workspaceFaceDrag == null ? 0 : workspaceFaceDrag.baseline().id();
   }

   public static AxisGizmo.Axis operationGizmoDragAxis() {
      if (FastPlaceClientPreview.operationSelectionReady() && geometryGizmoDrag != null) {
         return geometryGizmoDrag.axis();
      }
      return operationDrag == null || operationDrag.gizmoKey() == null
         ? null
         : operationDrag.gizmoKey().axis();
   }

   public static AxisGizmo.Operation operationGizmoDragOperation() {
      if (FastPlaceClientPreview.operationSelectionReady() && geometryGizmoDrag != null) {
         return geometryGizmoDrag.operation();
      }
      return operationDrag == null || operationDrag.gizmoKey() == null
         ? null
         : operationDrag.gizmoKey().operation();
   }

   public static AxisGizmo.HandleKey operationGizmoDragKey() {
      if (workspaceGizmoDrag != null) {
         return new AxisGizmo.HandleKey(
            workspaceGizmoDrag.operation(), workspaceGizmoDrag.axis(), workspaceGizmoDrag.direction()
         );
      }
      if (FastPlaceClientPreview.operationSelectionReady() && geometryGizmoDrag != null) {
         return new AxisGizmo.HandleKey(
            geometryGizmoDrag.operation(), geometryGizmoDrag.axis(), geometryGizmoDrag.direction()
         );
      }
      return operationDrag == null ? null : operationDrag.gizmoKey();
   }

   public static boolean workspaceGizmoDragMatches(int partId, boolean common) {
      return workspaceGizmoDrag != null
         && workspaceGizmoDrag.common() == common
         && (common || workspaceGizmoDrag.partId() == partId);
   }

   public static int operationGizmoDragSteps() {
      if (workspaceGizmoDrag != null) {
         return workspaceGizmoDrag.sentSteps();
      }
      if (FastPlaceClientPreview.operationSelectionReady() && geometryGizmoDrag != null) {
         return geometryGizmoDrag.sentSteps();
      }
      return operationDrag == null || operationDrag.gizmoKey() == null ? 0 : operationDrag.sentSteps();
   }

   public static double operationGizmoDragBaseValue() {
      if (FastPlaceClientPreview.operationSelectionReady() && geometryGizmoDrag != null) {
         return geometryGizmoDrag.baseValue();
      }
      return operationDrag == null || operationDrag.gizmoKey() == null ? 0.0 : operationDrag.gizmoBaseValue();
   }

   public static SelectionPrism.GridPlane operationPointDragPlane() {
      if (operationPointDrag == null || operationPointDrag.plane() == null) {
         return null;
      }
      return operationPointDrag.plane();
   }

   public static SelectionPrism.GridLine operationPointDragLine() {
      if (operationPointDrag == null || operationPointDrag.line() == null) {
         return null;
      }
      return operationPointDrag.line();
   }

   public static BlockPos operationPointDragTarget() {
      return operationPointDrag == null ? null : operationPointDrag.sentTarget();
   }

   public static AxisGizmo.Axis geometryGizmoDragAxis() {
      return geometryGizmoDrag == null ? null : geometryGizmoDrag.axis();
   }

   public static AxisGizmo.Operation geometryGizmoDragOperation() {
      return geometryGizmoDrag == null ? null : geometryGizmoDrag.operation();
   }

   public static AxisGizmo.HandleKey geometryGizmoDragKey() {
      return geometryGizmoDrag == null
         ? null
         : new AxisGizmo.HandleKey(
            geometryGizmoDrag.operation(),
            geometryGizmoDrag.axis(),
            geometryGizmoDrag.direction()
         );
   }

   public static int geometryGizmoDragSteps() {
      return geometryGizmoDrag == null ? 0 : geometryGizmoDrag.sentSteps();
   }

   public static double geometryGizmoDragBaseValue() {
      return geometryGizmoDrag == null ? 0.0 : geometryGizmoDrag.baseValue();
   }

   private static int geometryEndpointSteps(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(drag.origin(), drag.axisVector(), eye, view);
      return (int)Math.round(axisPoint.subtract(drag.origin()).dot(drag.axisVector()) * 2.0);
   }

   private static int operationEndpointSteps(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      return (int)Math.round(operationEndpointOffset(drag, eye, view));
   }

   private static double operationEndpointOffset(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(drag.origin(), drag.axisVector(), eye, view);
      return axisPoint.subtract(drag.origin()).dot(drag.axisVector());
   }

   private static int geometryRotationSteps(GeometryGizmoDrag drag, Vec3 eye, Vec3 view) {
      Vec3 normal = normalize(drag.axisVector());
      double denominator = normal.dot(view);
      if (Math.abs(denominator) < 1.0E-7) {
         return drag.sentSteps();
      }
      double distance = drag.center().subtract(eye).dot(normal) / denominator;
      if (distance < 0.0) {
         return drag.sentSteps();
      }
      Vec3 radial = eye.add(view.scale(distance)).subtract(drag.center());
      if (radial.lengthSqr() < 1.0E-7) {
         return drag.sentSteps();
      }
      Vec3 current = radial.normalize();
      double sin = drag.startTangent().dot(current);
      double cos = drag.startRadial().dot(current);
      double angle = Math.atan2(sin, cos);
      int rawSteps = (int)Math.round(angle * 1024.0 / (Math.PI * 2.0));
      int turns = (int)Math.round((drag.sentSteps() - rawSteps) / 1024.0);
      return rawSteps + turns * 1024;
   }

   private static int operationRotationSteps(Minecraft minecraft, int rawSteps) {
      return rotationSteps(operationRotationRadians(minecraft, rawSteps));
   }

   private static int rotationSteps(double radians) {
      return (int)Math.round(radians * 1024.0 / (Math.PI * 2.0));
   }

   private static double operationRotationRadians(Minecraft minecraft, int rawSteps) {
      double radians = rawSteps * Math.PI * 2.0 / 1024.0;
      if (physicalCtrlDown(minecraft, false)) {
         return PixelPerfectAngles.free(radians);
      }
      if (altDown) {
         modifierUsedForInput = true;
         return PixelPerfectAngles.snap(radians);
      }
      return PixelPerfectAngles.defaultSnap(radians);
   }

   private static Vec3 rotationTangent(Vec3 normal, Vec3 radial) {
      return normalize(normal).cross(radial).normalize();
   }

   private static void finishGeometryGizmoDrag(Minecraft minecraft) {
      boolean operationTransform = FastPlaceClientPreview.operationSelectionReady();
      if (geometryGizmoDrag != null && (operationTransform
         ? NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())
         : NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id()))) {
         FastPlaceClientPreview.noteGizmoFeedback(
            geometryGizmoDrag.axis(), geometryGizmoDrag.operation(), geometryGizmoDrag.sentSteps(), geometryGizmoDrag.baseValue()
         );
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               geometryOperationIndex(geometryGizmoDrag.operation()),
               geometryAxisIndex(geometryGizmoDrag.axis()),
               gizmoDirection(geometryGizmoDrag),
               geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
                  ? Math.clamp(geometryGizmoDrag.sentSteps(), -128, 128)
                  : Math.clamp(geometryGizmoDrag.sentSteps(), 0, 128),
               true
            )
            : new GeometryGizmoDragPayload(
               geometryOperationIndex(geometryGizmoDrag.operation()),
               geometryAxisIndex(geometryGizmoDrag.axis()),
               0,
               true
            );
         PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]);
      }
      geometryGizmoDrag = null;
   }

   private static void finishWorkspaceGizmoDrag() {
      if (workspaceGizmoDrag != null) {
         ClientOperationController.finishTransformGesture();
         if (altDown) {
            modifierUsedForInput = true;
         }
      }
      workspaceGizmoDrag = null;
   }

   private static void finishWorkspaceFaceDrag() {
      WorkspaceFaceDrag drag = workspaceFaceDrag;
      if (drag != null) {
         ClientOperationController.finishTransformGesture();
      }
      workspaceFaceDrag = null;
   }

   private static int gizmoDirection(GeometryGizmoDrag drag) {
      return drag.operation() == AxisGizmo.Operation.ROTATE
         ? 0
         : drag.direction() == AxisGizmo.Direction.NEGATIVE ? -1 : 1;
   }

   private static boolean activeSession() {
      return FastPlaceClientPreview.active()
         || FastPlaceClientPreview.operationActive()
         || FastPlaceClientPreview.geometryActive();
   }

   private static void finishOperationDrag(Minecraft minecraft) {
      if (operationDrag != null && operationDrag.gizmoKey() != null) {
         FastPlaceClientPreview.noteGizmoFeedback(
            operationDrag.gizmoKey().axis(),
            operationDrag.gizmoKey().operation(),
            operationDrag.sentSteps(),
            operationDrag.gizmoBaseValue()
         );
      }
      if (operationDrag != null && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         int releaseSteps = operationDrag.faceHit() == null
            ? operationDrag.deferredClick().releaseSteps(System.nanoTime(), OPERATION_FACE_SHORT_PRESS_NANOS)
            : 0;
         PacketDistributor.sendToServer(
            new OperationExtendPayload(operationDrag.axis(), operationDrag.positive(), releaseSteps, true),
            new CustomPacketPayload[0]
         );
      }
      operationDrag = null;
   }

   private static OperationPointDrag finishOperationPointDrag(Minecraft minecraft) {
      OperationPointDrag finished = operationPointDrag;
      if (finished != null && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         PacketDistributor.sendToServer(
            new OperationPointDragPayload(
               finished.pointIndex(), finished.sentTarget(), finished.constraint(), true
            ),
            new CustomPacketPayload[0]
         );
      }
      operationPointDrag = null;
      return finished;
   }

   private static void finishOperationPointClick(Minecraft minecraft, OperationPointDrag finished) {
      long now = System.nanoTime();
      boolean shortUnmovedClick = finished != null
         && now - finished.pressedAt() <= OPERATION_POINT_SHORT_PRESS_NANOS
         && finished.sentTarget().equals(finished.initialPoint());
      if (finished == null || !shortUnmovedClick) {
         lastOperationPointLeftClickAt = 0L;
         lastOperationPointLeftClickIndex = -1;
         lastOperationPointRightClickAt = 0L;
         lastOperationPointRightClickIndex = -1;
         return;
      }
      if (finished.pointIndex() == 0
         && FastPlaceClientPreview.operationPrismBaseOpen()
         && FastPlaceClientPreview.operationPointCount() >= 3
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
         PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
         lastOperationPointRightClickAt = 0L;
         lastOperationPointRightClickIndex = -1;
         lastOperationPointLeftClickAt = 0L;
         lastOperationPointLeftClickIndex = -1;
         resetPathDoubleClick();
         return;
      }
      if (finished.mouseButton() == 1) {
         boolean doubleClick = finished.pointIndex() == lastOperationPointRightClickIndex
            && now - lastOperationPointRightClickAt <= OPERATION_POINT_DOUBLE_CLICK_NANOS;
         if (doubleClick
            && FastPlaceClientPreview.operationPrismBaseOpen()
            && FastPlaceClientPreview.operationPointCount() >= 3
            && NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
            PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
            lastOperationPointRightClickAt = 0L;
            lastOperationPointRightClickIndex = -1;
            resetPathDoubleClick();
         } else {
            lastOperationPointRightClickAt = now;
            lastOperationPointRightClickIndex = finished.pointIndex();
         }
         lastOperationPointLeftClickAt = 0L;
         lastOperationPointLeftClickIndex = -1;
         return;
      }
      lastOperationPointRightClickAt = 0L;
      lastOperationPointRightClickIndex = -1;
      boolean doubleClick = finished.pointIndex() == lastOperationPointLeftClickIndex
         && now - lastOperationPointLeftClickAt <= OPERATION_POINT_DOUBLE_CLICK_NANOS;
      if (doubleClick) {
         sendOperationPointRemoval(minecraft, finished.pointIndex());
         lastOperationPointLeftClickAt = 0L;
         lastOperationPointLeftClickIndex = -1;
      } else {
         lastOperationPointLeftClickAt = now;
         lastOperationPointLeftClickIndex = finished.pointIndex();
      }
   }

   private static boolean adjustWorkspaceSelectionAtCrosshair(Minecraft minecraft, int mouseButton) {
      if (!ClientOperationController.active()) {
         return false;
      }
      BlockHitResult hit = longRangeBlockHit(minecraft);
      return hit != null
         && ClientOperationController.adjustActiveAabbPoint(mouseButton, hit.getBlockPos());
   }

   private static BlockHitResult longRangeBlockHit(Minecraft minecraft) {
      if (minecraft.level == null || minecraft.player == null) {
         return null;
      }
      BlockHitResult hit = LongRangeBlockRaycast.clip(
         minecraft.level, minecraft.player,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)
      ).hit();
      return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit : null;
   }

   private static void cancelOperationGesture(Minecraft minecraft) {
      if (workspaceGizmoDrag != null || workspaceFaceDrag != null) {
         ClientOperationController.cancelTransformGesture();
      }
      workspaceGizmoDrag = null;
      workspaceFaceDrag = null;
      operationDrag = null;
      operationPointDrag = null;
      operationClickCapturedButton = -1;
      UNDO_PRESS.cancel();
      undoPressCaptured = false;
      minecraft.options.keyAttack.setDown(false);
   }

   private static int geometryOperationIndex(AxisGizmo.Operation operation) {
      return switch (operation) {
         case MOVE -> 0;
         case SCALE -> 1;
         case ROTATE -> 2;
      };
   }

   private static int geometryAxisIndex(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> 0;
         case Y -> 1;
         case Z -> 2;
      };
   }

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   private static int clampDragSteps(int steps) {
      return Math.clamp(steps, -MAX_DRAG_STEPS_PER_PACKET, MAX_DRAG_STEPS_PER_PACKET);
   }

   private static Vec3 worldAxis(int axis) {
      return switch (axis) {
         case 0 -> new Vec3(1.0, 0.0, 0.0);
         case 1 -> new Vec3(0.0, 1.0, 0.0);
         case 2 -> new Vec3(0.0, 0.0, 1.0);
         default -> Vec3.ZERO;
      };
   }

   private static double vecAxisComponent(Vec3 value, int axis) {
      return axis == 0 ? value.x : axis == 1 ? value.y : value.z;
   }

   private record OperationDrag(
      int axis,
      boolean positive,
      DragAxisFrame frame,
      Vec3 normal,
      int sentSteps,
      int mouseButton,
      OperationGeometry.RayHit faceHit,
      AxisGizmo.HandleKey gizmoKey,
      double gizmoBaseValue,
      DeferredDragClick deferredClick
   ) {
      private OperationDrag withFrame(DragAxisFrame value) {
         return new OperationDrag(
            this.axis,
            this.positive,
            value,
            this.normal,
            this.sentSteps,
            this.mouseButton,
            this.faceHit,
            this.gizmoKey,
            this.gizmoBaseValue,
            this.deferredClick
         );
      }

      private OperationDrag withSentSteps(int value) {
         return new OperationDrag(
            this.axis,
            this.positive,
            this.frame,
            this.normal,
            value,
            this.mouseButton,
            this.faceHit,
            this.gizmoKey,
            this.gizmoBaseValue,
            this.deferredClick
         );
      }

      private OperationDrag withDeferredClick(DeferredDragClick value) {
         return new OperationDrag(
            this.axis,
            this.positive,
            this.frame,
            this.normal,
            this.sentSteps,
            this.mouseButton,
            this.faceHit,
            this.gizmoKey,
            this.gizmoBaseValue,
            value
         );
      }
   }

   private record WorkspaceFaceDrag(
      ClientSelectionPart baseline,
      int axis,
      boolean positive,
      DragAxisFrame frame,
      Vec3 normal,
      int sentSteps,
      int mouseButton,
      DeferredDragClick deferredClick,
      OperationGeometry.RayHit hit
   ) {
      private WorkspaceFaceDrag withSentSteps(int value) {
         return new WorkspaceFaceDrag(
            this.baseline, this.axis, this.positive, this.frame, this.normal, value,
            this.mouseButton, this.deferredClick, this.hit
         );
      }

      private WorkspaceFaceDrag withDeferredClick(DeferredDragClick value) {
         return new WorkspaceFaceDrag(
            this.baseline, this.axis, this.positive, this.frame, this.normal, this.sentSteps,
            this.mouseButton, value, this.hit
         );
      }
   }

   private record OperationPointDrag(
      int pointIndex,
      int mouseButton,
      BlockPos initialPoint,
      BlockPos sentTarget,
      SelectionPrism.GridPlane plane,
      Vec3 planeGrabOffset,
      SelectionPrism.GridLine line,
      double lineGrabBaseline,
      Vec3 axisBaselines,
      OperationPointDragConstraint constraint,
      long pressedAt
   ) {
      private OperationPointDrag withSentTarget(BlockPos target) {
         return new OperationPointDrag(
            this.pointIndex,
            this.mouseButton,
            this.initialPoint,
            target,
            this.plane,
            this.planeGrabOffset,
            this.line,
            this.lineGrabBaseline,
            this.axisBaselines,
            this.constraint,
            this.pressedAt
         );
      }

      private OperationPointDrag withConstraint(OperationPointDragConstraint value) {
         return new OperationPointDrag(
            this.pointIndex,
            this.mouseButton,
            this.initialPoint,
            this.sentTarget,
            this.plane,
            this.planeGrabOffset,
            this.line,
            this.lineGrabBaseline,
            this.axisBaselines,
            value,
            this.pressedAt
         );
      }

      private OperationPointDrag withPlaneFrame(
         SelectionPrism.GridPlane value,
         Vec3 grabOffset,
         Vec3 axisBaselines,
         OperationPointDragConstraint constraint
      ) {
         return new OperationPointDrag(
            this.pointIndex,
            this.mouseButton,
            this.initialPoint,
            this.sentTarget,
            value,
            grabOffset,
            this.line,
            this.lineGrabBaseline,
            axisBaselines,
            constraint,
            this.pressedAt
         );
      }

      private OperationPointDrag withLineFrame(
         SelectionPrism.GridLine value, double grabBaseline, OperationPointDragConstraint constraint
      ) {
         return new OperationPointDrag(
            this.pointIndex,
            this.mouseButton,
            this.initialPoint,
            this.sentTarget,
            this.plane,
            this.planeGrabOffset,
            value,
            grabBaseline,
            this.axisBaselines,
            constraint,
            this.pressedAt
         );
      }
   }

   private static Vec3 normalize(Vec3 vector) {
      return vector == null || vector.lengthSqr() < 1.0E-7 ? Vec3.ZERO : vector.normalize();
   }

   private record GeometryGizmoDrag(
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      Vec3 origin,
      Vec3 axisVector,
      int sentSteps,
      double baseValue,
      Vec3 center,
      Vec3 startRadial,
      Vec3 startTangent,
      AxisGizmo.Direction direction,
      int mouseButton
   ) {
      private GeometryGizmoDrag withSentSteps(int value) {
         return new GeometryGizmoDrag(
            this.operation,
            this.axis,
            this.origin,
            this.axisVector,
            value,
            this.baseValue,
            this.center,
            this.startRadial,
            this.startTangent,
            this.direction,
            this.mouseButton
         );
      }
   }

   private record WorkspaceGizmoDrag(
      int partId,
      boolean common,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      Vec3 origin,
      Vec3 axisVector,
      int sentSteps,
      Vec3 center,
      Vec3 startRadial,
      Vec3 startTangent,
      AxisGizmo.Direction direction,
      int mouseButton,
      java.util.List<io.github.fastformer.client.operation.ClientSelectionPart> baseline
   ) {
      private WorkspaceGizmoDrag withSentSteps(int value) {
         return new WorkspaceGizmoDrag(
            this.partId, this.common, this.operation, this.axis, this.origin, this.axisVector,
            value, this.center, this.startRadial, this.startTangent, this.direction, this.mouseButton,
            this.baseline
         );
      }
   }

   public enum ModifierReticleMode {
      NONE,
      EMBEDDED,
      HALF_GRID
   }
}
