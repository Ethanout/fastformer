package io.github.fastformer.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.render.interaction.OperationPointerKind;
import io.github.fastformer.client.render.interaction.OperationPointerTarget;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.input.drag.DeferredDragClick;
import io.github.fastformer.client.input.drag.DragAxisFrame;
import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.GizmoDragCalculator;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.input.drag.OperationPointDragCalculator;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.client.ui.GeometryRadialScreen;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.transform.PixelPerfectAngles;
import io.github.fastformer.client.operation.transform.RepeatDragQuantizer;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.network.payload.geometry.CycleStageModePayload;
import io.github.fastformer.network.payload.geometry.ClosePathPayload;
import io.github.fastformer.network.payload.settings.ModifierStatePayload;
import io.github.fastformer.network.payload.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.geometry.GeometryPointPayload;
import io.github.fastformer.network.payload.operation.OperationExtendPayload;
import io.github.fastformer.network.payload.operation.OperationTransformPayload;
import io.github.fastformer.network.payload.placement.QuitFastPlacePayload;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import io.github.fastformer.network.payload.operation.OperationPointDragPayload;
import io.github.fastformer.network.payload.operation.OperationInsertPointPayload;
import io.github.fastformer.network.payload.operation.OperationRemovePointPayload;
import io.github.fastformer.network.payload.operation.OperationSelectPointPayload;
import io.github.fastformer.network.payload.geometry.ScrollCandidatePayload;
import io.github.fastformer.network.payload.placement.UndoFastPlacePayload;
import io.github.fastformer.network.payload.world.WorldRedoPayload;
import io.github.fastformer.network.payload.world.WorldUndoPayload;
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
import net.minecraft.network.chat.Component;
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
   private static final PhysicalPressGate BUILDING_RIGHT_PRESS = new PhysicalPressGate();
   private static final PointerGestureState POINTER_GESTURE = new PointerGestureState();
   private static final ClientInputStateMachine INPUT_STATE = new ClientInputStateMachine();
   private static long pointerGestureToken;
   private static long clickGestureToken;
   private static OperationDrag operationDrag;
   private static OperationPointDrag operationPointDrag;
   private static int operationClickCapturedButton = -1;
   private static GeometryGizmoDrag geometryGizmoDrag;
   private static WorkspaceGizmoDrag workspaceGizmoDrag;
   private static WorkspaceFaceDrag workspaceFaceDrag;
   private static boolean undoPressCaptured;
   private static int geometryClickCapturedButton = -1;
   private static final ModifierGestureState MODIFIER_STATE = new ModifierGestureState();
   private static boolean radialChordDown;
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

   public static void endWorldSession() {
      INPUT_STATE.reset();
      cancelOperationGesture(Minecraft.getInstance());
      resetPathDoubleClick();
      resetOperationPointClicks();
      BUILDING_RIGHT_PRESS.release();
      MODIFIER_STATE.reset();
      radialChordDown = false;
      QuickReplaceMode.clear();
   }

   public static boolean beginPlacementRequest(long requestId) {
      synchronizeInputState();
      if (!INPUT_STATE.submit(requestId)) {
         return false;
      }
      cancelOperationGesture(Minecraft.getInstance());
      return true;
   }

   public static void acknowledgePlacementRequest(
      io.github.fastformer.network.payload.placement.PlacementActionAckPayload payload
   ) {
      INPUT_STATE.acknowledge(payload.requestId(), observedInputState());
   }

   public static void abortPlacementRequest(long requestId) {
      INPUT_STATE.abortSubmission(requestId, observedInputState());
   }

   public static boolean beginWorkspaceRequest(java.util.UUID transferId) {
      synchronizeInputState();
      if (!INPUT_STATE.submit(transferId)) {
         return false;
      }
      cancelOperationGesture(Minecraft.getInstance());
      return true;
   }

   public static void acknowledgeWorkspaceRequest(java.util.UUID transferId) {
      INPUT_STATE.acknowledge(transferId, observedInputState());
   }

   public static void abortWorkspaceRequest(java.util.UUID transferId) {
      INPUT_STATE.abortSubmission(transferId, observedInputState());
   }

   @SubscribeEvent
   public static void onKey(Key event) {
      Minecraft minecraft = Minecraft.getInstance();
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.KEY);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      boolean altKey = event.getKey() == 342 || event.getKey() == 346;
      boolean ctrlKey = event.getKey() == 341 || event.getKey() == 345;
      boolean controlDown = physicalCtrlDown(minecraft, false);
      boolean cancelKey = event.getAction() == 1 && event.getKey() == 81;
      if (event.getAction() == 1 && event.getKey() == 257
         && (ClientOperationController.reconnectRestorePending()
            || FastPlaceClientPreview.reconnectPreviewRestorePending())
         && minecraft.player != null && minecraft.screen == null) {
         ClientOperationController.confirmReconnectRestore();
         FastPlaceClientPreview.confirmReconnectPreviewRestore();
         return;
      }
      if (cancelKey
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id())) {
         boolean pendingRestore = ClientOperationController.reconnectRestorePending();
         boolean pendingPreviewRestore = FastPlaceClientPreview.reconnectPreviewRestorePending();
         boolean cancelled = INPUT_STATE.cancel();
         if (pendingRestore) ClientOperationController.dismissReconnectRestore();
         if (pendingPreviewRestore) FastPlaceClientPreview.dismissReconnectPreviewRestore();
         if (!cancelled) return;
         cancelOperationGesture(minecraft);
         ClientOperationController.clearWorkspace();
         PacketDistributor.sendToServer(QuitFastPlacePayload.INSTANCE, new CustomPacketPayload[0]);
         return;
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         return;
      }
      if (minecraft.player != null && minecraft.screen == null && event.getAction() == 1) {
         if (event.getKey() == 82 && !controlDown && !physicalAltDown(minecraft, false)
            && inputRoute == ClientInputStateMachine.Dispatch.VANILLA) {
            QuickReplaceMode.toggle(minecraft);
            return;
         }
         if (controlDown && event.getKey() == 67 && ClientOperationController.active()) {
            if (!ClientOperationController.copySelected()) {
               showOperationFeedback(minecraft, "fastformer.message.operation_copy_failed");
            }
            return;
         }
         if (controlDown && event.getKey() == 86
            && INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE) != ClientInputStateMachine.Dispatch.BLOCKED) {
            if (!ClientOperationController.paste(minecraft)) {
               showOperationFeedback(minecraft, "fastformer.message.operation_paste_failed");
            }
            return;
         }
         if (controlDown && event.getKey() == 65 && ClientOperationController.active()) {
            ClientOperationController.workspace().selectAll();
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
         if (operationSession) {
            cancelOperationGesture(minecraft);
         }
         if (event.getKey() == 90
            && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldUndoPayload.TYPE.id())) {
            PacketDistributor.sendToServer(WorldUndoPayload.INSTANCE, new CustomPacketPayload[0]);
         } else if (event.getKey() == 89
            && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldRedoPayload.TYPE.id())) {
            PacketDistributor.sendToServer(WorldRedoPayload.INSTANCE, new CustomPacketPayload[0]);
         }
      }
      if (minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null) {
         if (event.getAction() == 1
            && (event.getKey() == 257 || event.getKey() == 335)
            && (buildingSession || operationSession || geometrySession)
            && ClientPlacementRouter.canConfirm(minecraft)) {
            if (InteractionContext.nearVanillaBlock(minecraft)) {
               return;
            }
            // A confirmation ends the current input gesture.  If the mouse
            // button release arrives after the server has already consumed
            // this confirmation, it must not become a stale session undo.
            UNDO_PRESS.cancel();
            undoPressCaptured = false;
            if (MODIFIER_STATE.held()) {
               MODIFIER_STATE.consume();
            }
            if (operationSession) {
               if (ClientOperationController.active()) {
                  if (!ClientOperationController.submitWorkspace(minecraft)) {
                     showOperationFeedback(minecraft, "fastformer.message.operation_submit_failed");
                  }
               } else if (ClientOperationController.operationAdjustmentStarted()) {
                  ClientPlacementRouter.applyOperation(minecraft, physicalCtrlDown(minecraft, false));
               }
               return;
            }
            ClientPlacementRouter.confirm(minecraft);
         }
      }
   }

   private static void showOperationFeedback(Minecraft minecraft, String key) {
      if (minecraft.player != null) {
         minecraft.player.displayClientMessage(Component.translatable(key), true);
      }
   }

   private static void handleAltTransition(Minecraft minecraft, boolean down) {
      if (down == MODIFIER_STATE.held()) {
         return;
      }
      // Alt has no FastFormer meaning while the client is idle. Do not update
      // controller state in that case: a later click must start from a clean
      // interaction state rather than inheriting a modifier that was pressed
      // during ordinary Minecraft play.
      if (minecraft.screen != null || !modifierSubmodeAvailable()) {
         return;
      }
      if (INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.KEY) == ClientInputStateMachine.Dispatch.OPERATION) {
         ClientOperationController.setAltMode(down);
      }
      if (down) {
         boolean routed = minecraft.screen == null
            && modifierSubmodeAvailable()
            && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id());
         MODIFIER_STATE.press(System.nanoTime(), modifierCycleAvailable(), routed);
         if (routed) {
            PacketDistributor.sendToServer(new ModifierStatePayload(true), new CustomPacketPayload[0]);
         }
         return;
      }

      ModifierGestureState.Release release = MODIFIER_STATE.release(System.nanoTime(), MODIFIER_SHORT_PRESS_NANOS);
      if (release.routed() && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id())) {
         PacketDistributor.sendToServer(new ModifierStatePayload(false), new CustomPacketPayload[0]);
      }
      if (release.routed()
         && release.cycleEligible()
         && release.shortPress()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), CycleStageModePayload.TYPE.id())) {
         BlockPos candidate = FastPlaceClientPreview.lineModeCandidate();
         PacketDistributor.sendToServer(
            candidate != null ? new CycleStageModePayload(true, candidate) : CycleStageModePayload.INSTANCE, new CustomPacketPayload[0]
         );
      }
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
         MODIFIER_STATE.consume();
      }
   }

   @SubscribeEvent
   public static void onInteraction(InteractionKeyMappingTriggered event) {
      Minecraft minecraft = Minecraft.getInstance();
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.INTERACTION);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      // A placement or recovery task owns the world operation. Late attack/use
      // events must not reopen a selection or start a second gesture.
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (INPUT_STATE.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION)
         && QuickReplaceMode.canReplace(minecraft) && event.isUseItem()
         && minecraft.screen == null && minecraft.getConnection() != null
         && ClientPlacementRouter.quickReplace(minecraft)) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if ((modifierHeld() || physicalAltDown(minecraft, false)) && (event.isAttack() || event.isUseItem())) {
         return;
      }
      if (event.isUseItem()
         && buildingSession
         && !InteractionContext.nearVanillaBlock(minecraft)
         && !BUILDING_RIGHT_PRESS.consume()) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty()
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !buildingSession
         && !geometrySession
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         OperationInputSemantics.LeftDecision leftDecision = operationLeftDecision(pointerIntent());
         if (leftMousePressAlreadyHandled()) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (leftDecision.yieldToVanilla()) {
            return;
         }
         if (leftDecision.action() == OperationInputSemantics.LeftAction.GIZMO_DRAG) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (leftDecision.action() == OperationInputSemantics.LeftAction.SELECTION_UNDO
            || leftDecision.action() == OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO) {
            if (!undoPressCaptured) {
               UNDO_PRESS.press(System.nanoTime());
               undoPressCaptured = true;
            }
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (operationSession && modifierHeld()) {
            boolean handled = operationClickCapturedButton != 0
               && ClientOperationController.operationSelectionReady()
               && beginOperationGizmoDrag(minecraft, 0);
            if (handled) {
               MODIFIER_STATE.consume();
            }
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (ClientOperationController.operationCuboid()) {
            if (!modifierHeld() && !InteractionContext.nearVanillaBlock(minecraft)) {
               OperationPointerTarget target = FastPlaceClientPreview.operationPointerTarget();
               if (target.kind() == OperationPointerKind.WORLD) {
                  PacketDistributor.sendToServer(
                     new OperationPointPayload(OperationPointPayload.Role.FIRST), new CustomPacketPayload[0]
                  );
                  operationClickCapturedButton = 0;
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
               if (target.kind() == OperationPointerKind.FACE) {
                  if (operationClickCapturedButton != 0 && beginOperationFaceAdjustment(minecraft, -1, 0)) {
                     operationClickCapturedButton = 0;
                  }
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
            }
         }
         if (ClientOperationController.operationPrism()
            && (operationClickCapturedButton == 0 || beginOperationPointDrag(minecraft, 0))) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.nearVanillaBlock(minecraft)
            && ClientOperationController.operationPrism()
            && (operationClickCapturedButton == 0 || sendOperationEdgeInsertion(minecraft))) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.nearVanillaBlock(minecraft)
            && ClientOperationController.operationPrism()
            && FastPlaceClientPreview.operationCandidatePoint() != null
            && (operationClickCapturedButton == 0 || sendNextOperationPrismPoint(minecraft))) {
            operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!operationSession
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
         if (operationSession && !InteractionContext.nearVanillaBlock(minecraft)) {
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
         && inputRoute == ClientInputStateMachine.Dispatch.VANILLA
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
         && geometrySession) {
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
         && (buildingSession || operationSession || geometrySession)) {
         if (InteractionContext.nearVanillaBlock(minecraft)) {
            return;
         }
         if (geometrySession
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
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.POINTER);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
         return;
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setCanceled(true);
         return;
      }
      if (event.getButton() == 1) {
         if (event.getAction() == 1) {
            BUILDING_RIGHT_PRESS.press();
         } else if (event.getAction() == 0) {
            BUILDING_RIGHT_PRESS.release();
         }
      }
      if (event.getAction() == 1) {
         if (INPUT_STATE.accepts(clickGestureToken)
            && (POINTER_GESTURE.kind() != PointerGestureState.Kind.NONE || undoPressCaptured)) {
            cancelOperationGesture(minecraft);
         }
         clickGestureToken = INPUT_STATE.beginGesture(event.getButton());
      } else if (event.getAction() == 0) {
         if (!INPUT_STATE.finishGesture(event.getButton(), clickGestureToken)) {
            if (!INPUT_STATE.accepts(clickGestureToken)) {
               clickGestureToken = 0L;
            }
            return;
         }
         clickGestureToken = 0L;
      }
      boolean altMouseChord = physicalAltDown(minecraft, false) || MODIFIER_STATE.held();
      OperationInteractionIntent pointerIntent = FastPlaceClientPreview.operationInteractionIntent().orElse(null);
      // Alt is an explicit "new selection" chord. It must win over gizmos,
      // existing-part selection and vanilla placement, even when the ray hits
      // an existing workspace component.
      if (altMouseChord && event.getAction() == 1
         && INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION)
            == ClientInputStateMachine.Dispatch.OPERATION
         && (event.getButton() == 0 || event.getButton() == 1 || event.getButton() == 2)) {
         ClientOperationController.setAltMode(true);
         MODIFIER_STATE.consume();
         BlockHitResult hit = longRangeBlockHit(minecraft);
         if (hit != null && ClientOperationController.handleAltCreateClick(event.getButton(), hit.getBlockPos())) {
            MODIFIER_STATE.consume();
            operationClickCapturedButton = event.getButton();
            event.setCanceled(true);
            return;
         }
         event.setCanceled(true);
         return;
      }
      if (MODIFIER_STATE.held() && event.getAction() == 1) {
         MODIFIER_STATE.consume();
      }
      if (event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1 || event.getButton() == 2)
         && !ClientOperationController.active()
         && inputRoute == ClientInputStateMachine.Dispatch.VANILLA
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && !InteractionContext.nearVanillaBlock(minecraft)
         && longRangeBlockHit(minecraft) != null) {
         if (event.getButton() == 1) {
            BUILDING_RIGHT_PRESS.consume();
         }
         if (ClientPlacementRouter.startPlacement(minecraft, event.getButton() == 2)) {
            event.setCanceled(true);
            return;
         }
      }
      if (modifierHeld()
         && event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1)
         && operationDrag == null
         && workspaceGizmoDrag == null
         && workspaceFaceDrag == null) {
         if (ClientOperationController.active()
            && adjustWorkspaceSelectionAtCrosshair(minecraft, event.getButton())) {
            MODIFIER_STATE.consume();
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
         && (buildingSession || geometrySession || operationSession)
         && (NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())
            || NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id()))) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            boolean operationAdjust = operationSession
               && ClientOperationController.operationSelectionReady()
               && operationAdjustModifierHeld();
            boolean operationAlt = operationSession && modifierHeld();
            OperationInputSemantics.LeftDecision leftDecision = operationLeftDecision(pointerIntent);
            if (leftDecision.yieldToVanilla()) {
               return;
            }
            OperationInputSemantics.LeftAction operationLeft = leftDecision.action();
            if (handleWorkspacePointerClick(minecraft, pointerIntent, 0, -1)) {
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (ClientOperationController.active()
               && adjustWorkspaceSelectionAtCrosshair(minecraft, 0)) {
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (ClientOperationController.active()) {
               // A confirmed client workspace owns the interaction surface;
               // never fall through to the legacy server session handlers.
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (operationLeft == OperationInputSemantics.LeftAction.GIZMO_DRAG) {
               beginOperationGizmoDrag(minecraft, 0);
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (ClientOperationController.operationPrism() && beginOperationPointDrag(minecraft, 0)) {
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
                  MODIFIER_STATE.consume();
               }
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (operationAdjust) {
               boolean handled = ClientOperationController.operationCuboid()
                  ? beginOperationGizmoDrag(minecraft, 0)
                  : beginOperationGizmoDrag(minecraft, 0)
                     || sendOperationPointSelection(minecraft)
                     || beginOperationFaceAdjustment(minecraft, -1, 0);
               if (operationClickCapturedButton != 0 && handled) {
                  MODIFIER_STATE.consume();
               }
               operationClickCapturedButton = 0;
               consumed = true;
            } else if (ClientOperationController.operationCuboid() && !InteractionContext.nearVanillaBlock(minecraft)) {
               OperationPointerTarget target = FastPlaceClientPreview.operationPointerTarget();
               if (target.kind() == OperationPointerKind.WORLD) {
                  PacketDistributor.sendToServer(
                     new OperationPointPayload(OperationPointPayload.Role.FIRST), new CustomPacketPayload[0]
                  );
                  operationClickCapturedButton = 0;
                  consumed = true;
               } else if (target.kind() == OperationPointerKind.FACE) {
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
            } else if (geometrySession && beginGeometryGizmoDrag(minecraft, 0)) {
               UNDO_PRESS.cancel();
               undoPressCaptured = false;
               consumed = true;
            } else if (geometrySession && beginGeometryInteraction(minecraft, 0)) {
               UNDO_PRESS.cancel();
               undoPressCaptured = false;
               consumed = true;
            } else if (operationSession) {
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
      } else if (event.getButton() == 1 && geometrySession) {
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
      } else if (event.getButton() == 1 && operationSession) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            if (handleWorkspacePointerClick(minecraft, pointerIntent, 1, 1)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (ClientOperationController.active()
               && adjustWorkspaceSelectionAtCrosshair(minecraft, 1)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (ClientOperationController.active()) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (ClientOperationController.operationSelectionConfirmed()) {
               if (InteractionContext.nearVanillaBlock(minecraft)) {
                  return;
               }
               beginOperationGizmoDrag(minecraft, 1);
               operationClickCapturedButton = 1;
               consumed = true;
            } else {
            boolean operationAdjust = ClientOperationController.operationSelectionReady()
               && operationAdjustModifierHeld();
            boolean operationAlt = modifierHeld();
            boolean operationPointTarget = ClientOperationController.operationPrism()
               && FastPlaceClientPreview.operationPointUnderCrosshairIndex() >= 0;
            if (InteractionContext.nearVanillaBlock(minecraft) && !operationAdjust && !operationAlt && !operationPointTarget) {
               return;
            }
            if (operationAlt) {
               boolean handled = beginOperationGizmoDrag(minecraft, 1);
               if (handled) {
                  MODIFIER_STATE.consume();
               }
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (operationAdjust) {
               if (beginOperationGizmoDrag(minecraft, 1)
                  || sendOperationPointSelection(minecraft)
                  || beginOperationFaceAdjustment(minecraft, 1, 1)) {
                  MODIFIER_STATE.consume();
               }
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (ClientOperationController.operationPrism() && beginOperationPointDrag(minecraft, 1)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (!InteractionContext.nearVanillaBlock(minecraft)
               && ClientOperationController.operationPrism()
               && sendOperationEdgeInsertion(minecraft)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (!InteractionContext.nearVanillaBlock(minecraft)
               && ClientOperationController.operationPrism()
               && sendNextOperationPrismPoint(minecraft)) {
               operationClickCapturedButton = 1;
               consumed = true;
            } else if (ClientOperationController.operationCuboid()) {
               OperationPointerTarget target = FastPlaceClientPreview.operationPointerTarget();
               if (target.kind() == OperationPointerKind.WORLD) {
                  PacketDistributor.sendToServer(
                     new OperationPointPayload(OperationPointPayload.Role.SECOND), new CustomPacketPayload[0]
                  );
                  operationClickCapturedButton = 1;
                  consumed = true;
               } else if (target.kind() == OperationPointerKind.FACE) {
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
      } else if (event.getButton() == 1 && buildingSession) {
         if (event.getAction() == 1 && !InteractionContext.nearVanillaBlock(minecraft) && tryClosePathGesture(minecraft, false)) {
            BUILDING_RIGHT_PRESS.consume();
            event.setCanceled(true);
         }
      } else if (event.getButton() == 2
         && operationSession
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
         && buildingSession
         && FastPlaceClientPreview.middleConfirmEnabled()
         && ClientPlacementRouter.quickShape(minecraft)) {
         event.setCanceled(true);
      } else if (event.getButton() == 2
         && event.getAction() == 1
         && operationSession
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
      ClientSessionManager.instance().observePlayer(minecraft);
      InteractionContext.tick(minecraft);
      if (minecraft.player == null || minecraft.getConnection() == null) {
         INPUT_STATE.reset();
         QuickReplaceMode.clear();
         cancelWorkspaceEditIfPresent();
         BUILDING_RIGHT_PRESS.release();
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
        cancelPointerGesture();
        return;
      }
      ClientOperationController.onClientTick();
      FastPlaceClientPreview.onClientTick();
      synchronizeInputState();
      if (INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.KEY)
         != ClientInputStateMachine.Dispatch.BLOCKED) {
         pollModifierKeys(minecraft);
      }
      if (INPUT_STATE.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION)
         && QuickReplaceMode.active() && minecraft.screen == null && minecraft.options.keyUse.isDown()) {
         ClientPlacementRouter.quickReplace(minecraft);
      }
      if (!minecraft.options.keyUse.isDown()) {
         BUILDING_RIGHT_PRESS.release();
      }
      boolean operationActive = FastPlaceClientPreview.operationActive();
      if (operationSessionWasActive && !operationActive) {
         minecraft.options.keyAttack.setDown(false);
      }
      operationSessionWasActive = operationActive;
      if (!activeSession()) {
         cancelWorkspaceEditIfPresent();
         operationDrag = null;
         operationPointDrag = null;
         resetOperationPointClicks();
         UNDO_PRESS.cancel();
         undoPressCaptured = false;
         geometryClickCapturedButton = -1;
         operationClickCapturedButton = -1;
         workspaceGizmoDrag = null;
         workspaceFaceDrag = null;
         cancelPointerGesture();
      }
      if (operationDrag == null && operationPointDrag == null
         && geometryGizmoDrag == null && workspaceGizmoDrag == null && workspaceFaceDrag == null) {
         return;
      }

      if (operationDrag != null
         && !ownsPointerGesture(PointerGestureState.Kind.OPERATION_FACE)
         && !ownsPointerGesture(PointerGestureState.Kind.OPERATION_GIZMO)) {
         operationDrag = null;
      }
      if (operationPointDrag != null
         && !ownsPointerGesture(PointerGestureState.Kind.OPERATION_POINT)) {
         operationPointDrag = null;
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
      if (operationDrag.faceHit() != null && ClientOperationController.operationCuboid()) {
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
         int clippedDelta = ClientInputMath.clampDragSteps(delta, MAX_DRAG_STEPS_PER_PACKET);
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
      if (drag == null || !FastPlaceClientPreview.operationActive() || ClientOperationController.operationSelectionConfirmed()) {
         operationPointDrag = null;
         return;
      }
      if (!ownsPointerGesture(PointerGestureState.Kind.OPERATION_POINT)) {
         operationPointDrag = null;
         return;
      }
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      OperationPointDragConstraint constraint = OperationPointDragCalculator.selectConstraint(drag, eye, view);
      if (constraint != drag.constraint()) {
         drag = OperationPointDragCalculator.rebase(drag, constraint, eye, view);
         operationPointDrag = drag;
      }
      BlockPos desired = constraint == OperationPointDragConstraint.LINE
         ? OperationPointDragCalculator.lineTarget(drag, eye, view)
         : OperationPointDragCalculator.planeTarget(drag, eye, view);
      if (desired == null) {
         return;
      }
      BlockPos target = OperationPointDragCalculator.nextTarget(drag.sentTarget(), desired, drag, constraint);
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

   @SubscribeEvent
   public static void onMouseScroll(MouseScrollingEvent event) {
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = INPUT_STATE.dispatch(ClientInputStateMachine.InputKind.SCROLL);
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setCanceled(true);
         return;
      }
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null
         && minecraft.screen == null
         && inputRoute == ClientInputStateMachine.Dispatch.OPERATION
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
         && inputRoute != ClientInputStateMachine.Dispatch.VANILLA
         && FastPlaceClientPreview.usesScrollContext()
         && event.getScrollDeltaY() != 0.0
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ScrollCandidatePayload.TYPE.id())) {
         if (modifierInputRoute(ModifierInput.SCROLL) == ModifierInputRoute.VANILLA) {
            MODIFIER_STATE.consume();
            return;
         }
         if (InteractionContext.nearVanillaBlock(minecraft)) {
            return;
          }
          PacketDistributor.sendToServer(new ScrollCandidatePayload(event.getScrollDeltaY() > 0.0 ? 1 : -1), new CustomPacketPayload[0]);
          FastPlaceClientPreview.noteScrollFeedback();
          if (MODIFIER_STATE.held()) {
             MODIFIER_STATE.consume();
          }
         event.setCanceled(true);
      }
   }

   private static ModifierInputRoute modifierInputRoute(ModifierInput input) {
      if (ClientOperationController.operationSelectionConfirmed()) {
         return ModifierInputRoute.SESSION;
      }
      return MODIFIER_STATE.held() && input == ModifierInput.SCROLL
         ? ModifierInputRoute.VANILLA
         : ModifierInputRoute.SESSION;
   }

   private static boolean beginOperationDrag(Minecraft minecraft, int mouseButton, int shortPressSteps) {
      OperationSelectionVolume selection = FastPlaceClientPreview.operationSelection();
      if (selection == null
         || shortPressSteps == 0
         || !ClientOperationController.operationSelectionReady()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         return false;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      OperationGeometry.RayHit hit = ClientOperationController.operationCuboid()
         ? FastPlaceClientPreview.operationFaceHit()
         : selection.raycast(eye, minecraft.player.getViewVector(1.0F), OPERATION_REACH);
      if (hit == null) {
         return false;
      }

      Vec3 normal = hit.normal();
      int axis = hit.axis();
      boolean positive = normal.dot(selection.axis(axis)) > 0.0;
      if (!ClientOperationController.operationCuboid() && (axis != 2 || !positive)) {
         return false;
      }
      boolean reverseInside = ClientOperationController.operationCuboid() && selection.contains(eye);
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
      pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.OPERATION_FACE);
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
      if (ClientOperationController.operationSelectionReady()) {
         return beginConfirmedOperationGizmoDrag(minecraft, gizmo, hit, mouseButton);
      }
      int axis = ClientInputMath.geometryAxisIndex(handle.axis());
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
         ClientInputMath.axisComponent(gizmo.center(), handle.axis()),
         DeferredDragClick.none()
      );
      pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      return true;
   }

   /** Handles the shared workspace hit targets for either mouse button. */
   private static boolean handleWorkspacePointerClick(
      Minecraft minecraft, OperationInteractionIntent pointerIntent, int mouseButton, int shortPressSteps
   ) {
      if (pointerIntent instanceof OperationInteractionIntent.Gizmo gizmo) {
         beginWorkspaceGizmoDrag(minecraft, gizmo, mouseButton);
         return true;
      }
      if (pointerIntent instanceof OperationInteractionIntent.Face face) {
         selectOrBeginWorkspaceFace(minecraft, face, mouseButton, shortPressSteps);
         return true;
      }
      if (pointerIntent instanceof OperationInteractionIntent.Part part && part.partId() > 0) {
         if (physicalCtrlDown(minecraft, false)) {
            ClientOperationController.workspace().toggleSelected(part.partId());
         } else {
            ClientOperationController.workspace().selectOnly(part.partId());
         }
         return true;
      }
      return false;
   }

   private static boolean beginWorkspaceGizmoDrag(
      Minecraft minecraft, OperationInteractionIntent.Gizmo target, int mouseButton
   ) {
      if (ClientOperationController.workspaceSubmissionPending()) {
         return false;
      }
      AxisGizmo.Handle handle = target.hit().handle();
      var workspace = ClientOperationController.workspace();
      if (workspace.selectedParts().stream().anyMatch(part ->
         !WorkspacePreviewComposer.canResolveForRendering(part.blocks(), part.transform()))) {
         return false;
      }
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
      var editToken = workspace.activeEditToken();
      Vec3 axis = target.gizmo().axisVector(handle.axis());
      if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
         axis = axis.scale(-1.0);
      }
      Vec3 radial = handle.drawsRing() ? target.hit().point().subtract(target.gizmo().center()) : Vec3.ZERO;
      if (handle.drawsRing() && radial.lengthSqr() < 1.0E-7) {
         ClientOperationController.cancelTransformGesture(editToken);
         return false;
      }
      workspaceGizmoDrag = new WorkspaceGizmoDrag(
         target.partId(), target.common(), handle.operation(), handle.axis(),
         target.hit().point(), axis, 0, target.gizmo().center(),
         handle.drawsRing() ? radial.normalize() : Vec3.ZERO,
         handle.drawsRing() ? GizmoDragCalculator.rotationTangent(axis, radial.normalize()) : Vec3.ZERO,
         handle.direction(), mouseButton, java.util.List.copyOf(workspace.selectedParts()), editToken
      );
      pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
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
      var editToken = workspace.activeEditToken();
      OperationGeometry.RayHit hit = target.hit();
      int axis = hit.axis();
      Vec3 worldAxis = ClientInputMath.worldAxis(axis);
      boolean positive = hit.normal().dot(worldAxis) > 0.0;
      workspaceFaceDrag = new WorkspaceFaceDrag(
         part, axis, positive, DragAxisFrame.start(hit.point(), false), hit.normal(), 0,
         mouseButton, DeferredDragClick.start(System.nanoTime(), shortPressSteps), hit, editToken
      );
      pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.WORKSPACE_FACE);
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
            gizmo.center(), radial.normalize(), GizmoDragCalculator.rotationTangent(gizmo.axisVector(handle.axis()), radial.normalize()),
             handle.direction(), mouseButton
          );
          pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      } else {
         Vec3 axis = gizmo.axisVector(handle.axis());
         if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
            axis = axis.scale(-1.0);
         }
          geometryGizmoDrag = new GeometryGizmoDrag(
             handle.operation(), handle.axis(), hit.point(), axis, 0, baseValue,
             Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, handle.direction(), mouseButton
          );
          pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      }
      FastPlaceClientPreview.noteGizmoFeedback(handle.axis(), handle.operation(), 0, baseValue);
      return true;
   }

   private static boolean beginOperationPointDrag(Minecraft minecraft, int mouseButton) {
      if (!ClientOperationController.operationPrism()
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
         OperationPointDragCalculator.axisOffset(center, eye, view, 0),
         OperationPointDragCalculator.axisOffset(center, eye, view, 1),
         OperationPointDragCalculator.axisOffset(center, eye, view, 2)
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
      pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.OPERATION_POINT);
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

   private static boolean sendNextOperationPrismPoint(Minecraft minecraft) {
      if (!ClientOperationController.operationPrism()
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
      if (!ClientOperationController.operationPrism()) {
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
         MODIFIER_STATE.reset();
         ClientOperationController.setAltMode(false);
         radialChordDown = false;
         return;
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
            GizmoDragCalculator.rotationTangent(gizmo.axisVector(handle.axis()), radial.normalize()),
              handle.direction(),
              mouseButton
           );
           pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.BUILDING_GEOMETRY);
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
      pointerGestureToken = POINTER_GESTURE.begin(PointerGestureState.Kind.BUILDING_GEOMETRY);
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
      if (geometryGizmoDrag != null
         && !ownsPointerGesture(PointerGestureState.Kind.BUILDING_GEOMETRY)
         && !ownsPointerGesture(PointerGestureState.Kind.OPERATION_GIZMO)) {
         geometryGizmoDrag = null;
         return;
      }
      boolean operationTransform = ClientOperationController.operationSelectionReady();
      if (!FastPlaceClientPreview.geometryActive() && !operationTransform) {
         geometryGizmoDrag = null;
         return;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      int totalSteps = geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
         ? GizmoDragCalculator.geometryRotationSteps(geometryGizmoDrag, eye, view)
         : operationTransform
            ? GizmoDragCalculator.operationEndpointSteps(geometryGizmoDrag, eye, view)
            : GizmoDragCalculator.geometryEndpointSteps(geometryGizmoDrag, eye, view);
      if (operationTransform && geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE) {
         totalSteps = operationRotationSteps(minecraft, totalSteps);
      }
      int delta = totalSteps - geometryGizmoDrag.sentSteps();
      if (delta != 0 && (operationTransform
         ? NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())
         : NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id()))) {
         int clippedDelta = ClientInputMath.clampDragSteps(delta, MAX_DRAG_STEPS_PER_PACKET);
         int operationTotal = geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
            ? Math.clamp(totalSteps, -128, 128)
            : Math.clamp(totalSteps, 0, 128);
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               ClientInputMath.geometryOperationIndex(geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(geometryGizmoDrag.axis()),
               gizmoDirection(geometryGizmoDrag),
               operationTotal,
               false
            )
            : new GeometryGizmoDragPayload(
               ClientInputMath.geometryOperationIndex(geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(geometryGizmoDrag.axis()),
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
      if (!ownsPointerGesture(PointerGestureState.Kind.WORKSPACE_GIZMO)) {
         workspaceGizmoDrag = null;
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
         int rawSteps = GizmoDragCalculator.geometryRotationSteps(geometry, eye, view);
         rotationRadians = operationRotationRadians(minecraft, rawSteps);
         totalSteps = GizmoDragCalculator.rotationSteps(rotationRadians);
      } else {
         double rawOffset = GizmoDragCalculator.operationEndpointOffset(geometry, eye, view);
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
         drag.editToken(), drag.baseline(), drag.common(), drag.operation(), drag.axis(), direction, totalSteps, rotationRadians
      );
      workspaceGizmoDrag = drag.withSentSteps(totalSteps);
      if (MODIFIER_STATE.held()) {
         MODIFIER_STATE.consume();
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
         .map(WorkspacePreviewComposer::resolve)
         .filter(values -> !values.isEmpty())
         .map(values -> io.github.fastformer.client.operation.selection.OccupiedBlockBounds.from(values.keySet()).orElseThrow())
         .reduce(io.github.fastformer.client.operation.selection.OccupiedBlockBounds::union)
         .map(bounds -> bounds.width(drag.axis()))
         .orElse(1);
   }

   private static void updateWorkspaceFaceDrag(Minecraft minecraft) {
      WorkspaceFaceDrag drag = workspaceFaceDrag;
      if (drag == null || minecraft.player == null) {
         return;
      }
      if (!ownsPointerGesture(PointerGestureState.Kind.WORKSPACE_FACE)) {
         workspaceFaceDrag = null;
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
            drag.editToken(), drag.baseline(), drag.axis(), drag.positive(), totalSteps
         );
         drag = drag.withSentSteps(totalSteps);
      }
      workspaceFaceDrag = drag;
   }

   public static boolean precisionHudActive() {
      return modifierReticleMode() != ModifierReticleMode.NONE;
   }

   public static boolean modifierHeld() {
      return MODIFIER_STATE.held();
   }

   public static boolean controlHeld() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player != null && physicalCtrlDown(minecraft, false);
   }

   public static boolean operationAdjustModifierHeld() {
      return MODIFIER_STATE.held();
   }

   private enum ModifierInput {
      SCROLL
   }

   private enum ModifierInputRoute {
      SESSION,
      VANILLA
   }

   public static ModifierReticleMode modifierReticleMode() {
      if (!MODIFIER_STATE.held() || radialChordDown || !modifierSubmodeAvailable()) {
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
      if (ClientOperationController.operationSelectionReady() && geometryGizmoDrag != null) {
         return geometryGizmoDrag.axis();
      }
      return operationDrag == null || operationDrag.gizmoKey() == null
         ? null
         : operationDrag.gizmoKey().axis();
   }

   public static AxisGizmo.Operation operationGizmoDragOperation() {
      if (ClientOperationController.operationSelectionReady() && geometryGizmoDrag != null) {
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
      if (ClientOperationController.operationSelectionReady() && geometryGizmoDrag != null) {
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
      if (ClientOperationController.operationSelectionReady() && geometryGizmoDrag != null) {
         return geometryGizmoDrag.sentSteps();
      }
      return operationDrag == null || operationDrag.gizmoKey() == null ? 0 : operationDrag.sentSteps();
   }

   public static double operationGizmoDragBaseValue() {
      if (ClientOperationController.operationSelectionReady() && geometryGizmoDrag != null) {
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

   private static int operationRotationSteps(Minecraft minecraft, int rawSteps) {
      return GizmoDragCalculator.rotationSteps(operationRotationRadians(minecraft, rawSteps));
   }

   private static double operationRotationRadians(Minecraft minecraft, int rawSteps) {
      double radians = rawSteps * Math.PI * 2.0 / 1024.0;
      if (physicalCtrlDown(minecraft, false)) {
         return PixelPerfectAngles.free(radians);
      }
      if (MODIFIER_STATE.held()) {
         MODIFIER_STATE.consume();
         return PixelPerfectAngles.snap(radians);
      }
      return PixelPerfectAngles.defaultSnap(radians);
   }

   private static void finishGeometryGizmoDrag(Minecraft minecraft) {
      boolean operationTransform = ClientOperationController.operationSelectionReady();
      if (geometryGizmoDrag != null && (operationTransform
         ? NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())
         : NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id()))) {
         FastPlaceClientPreview.noteGizmoFeedback(
            geometryGizmoDrag.axis(), geometryGizmoDrag.operation(), geometryGizmoDrag.sentSteps(), geometryGizmoDrag.baseValue()
         );
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               ClientInputMath.geometryOperationIndex(geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(geometryGizmoDrag.axis()),
               gizmoDirection(geometryGizmoDrag),
               geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
                  ? Math.clamp(geometryGizmoDrag.sentSteps(), -128, 128)
                  : Math.clamp(geometryGizmoDrag.sentSteps(), 0, 128),
               true
            )
            : new GeometryGizmoDragPayload(
               ClientInputMath.geometryOperationIndex(geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(geometryGizmoDrag.axis()),
               0,
               true
            );
         PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]);
      }
      geometryGizmoDrag = null;
      finishPointerGesture();
   }

   private static void finishWorkspaceGizmoDrag() {
      if (workspaceGizmoDrag != null) {
         ClientOperationController.finishTransformGesture(workspaceGizmoDrag.editToken());
         if (MODIFIER_STATE.held()) {
            MODIFIER_STATE.consume();
         }
      }
      workspaceGizmoDrag = null;
      finishPointerGesture();
   }

   private static void finishWorkspaceFaceDrag() {
      WorkspaceFaceDrag drag = workspaceFaceDrag;
      if (drag != null) {
         ClientOperationController.finishTransformGesture(drag.editToken());
      }
      workspaceFaceDrag = null;
      finishPointerGesture();
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

   /** Refreshes the routing phase before every event, including same-tick task transitions. */
   private static void synchronizeInputState() {
      long generation = INPUT_STATE.generation();
      INPUT_STATE.observe(observedInputState());
      if (INPUT_STATE.generation() != generation) {
         cancelOperationGesture(Minecraft.getInstance());
      }
   }

   private static ClientInputStateMachine.State observedInputState() {
      if (FastPlaceClientPreview.activity() == io.github.fastformer.fastplace.FastPlaceActivity.RESTORE_TASK) {
         return ClientInputStateMachine.State.RESTORING;
      }
      if (FastPlaceClientPreview.taskActive()) {
         return ClientInputStateMachine.State.PLACING;
      }
      if (FastPlaceClientPreview.operationActive()) {
         return ClientOperationController.active() || ClientOperationController.operationSelectionConfirmed()
            ? ClientInputStateMachine.State.ADJUSTING : ClientInputStateMachine.State.SELECTING;
      }
      if (FastPlaceClientPreview.geometryActive()) {
         return ClientInputStateMachine.State.GEOMETRY;
      }
      return FastPlaceClientPreview.active()
         ? ClientInputStateMachine.State.BUILDING : ClientInputStateMachine.State.IDLE;
   }

   private static OperationInteractionIntent pointerIntent() {
      return FastPlaceClientPreview.operationInteractionIntent().orElse(null);
   }

   private static OperationInputSemantics.LeftDecision operationLeftDecision(
      OperationInteractionIntent pointerIntent
   ) {
      boolean operationActive = FastPlaceClientPreview.operationActive();
      boolean operationAdjust = operationActive
         && ClientOperationController.operationSelectionReady()
         && operationAdjustModifierHeld();
      boolean operationAlt = operationActive && modifierHeld();
      boolean interactionTargetHit = FastPlaceClientPreview.operationGizmoHit() != null
         || pointerIntent instanceof OperationInteractionIntent.Face
         || pointerIntent instanceof OperationInteractionIntent.Part;
      return OperationInputSemantics.decideLeftPress(new OperationInputSemantics.LeftPressSnapshot(
         ClientOperationController.operationPrism()
            ? io.github.fastformer.fastplace.OperationSelectionMode.PRISM
            : io.github.fastformer.fastplace.OperationSelectionMode.CUBOID,
         operationActive && (ClientOperationController.operationSelectionReady() || ClientOperationController.active()),
         operationActive && (ClientOperationController.operationAdjustmentStarted() || ClientOperationController.active()),
         operationActive && FastPlaceClientPreview.operationGizmoHit() != null,
         InteractionContext.nearVanillaBlock(Minecraft.getInstance()),
         operationAdjust || operationAlt,
         interactionTargetHit
      ));
   }

   private static boolean leftMousePressAlreadyHandled() {
      return operationClickCapturedButton == 0
         || geometryClickCapturedButton == 0
         || undoPressCaptured
         || operationDrag != null && operationDrag.mouseButton() == 0
         || operationPointDrag != null && operationPointDrag.mouseButton() == 0
         || workspaceGizmoDrag != null && workspaceGizmoDrag.mouseButton() == 0
         || workspaceFaceDrag != null && workspaceFaceDrag.mouseButton() == 0
         || geometryGizmoDrag != null && geometryGizmoDrag.mouseButton() == 0;
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
      finishPointerGesture();
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
      finishPointerGesture();
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
      BlockHitResult hit = LongRangeBlockRaycast.clipForPlacement(
         minecraft.level, minecraft.player,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)
      ).hit();
      return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit : null;
   }

   private static void cancelOperationGesture(Minecraft minecraft) {
      if (workspaceGizmoDrag != null) {
         ClientOperationController.cancelTransformGesture(workspaceGizmoDrag.editToken());
      } else if (workspaceFaceDrag != null) {
         ClientOperationController.cancelTransformGesture(workspaceFaceDrag.editToken());
      }
      workspaceGizmoDrag = null;
      workspaceFaceDrag = null;
      operationDrag = null;
      operationPointDrag = null;
      operationClickCapturedButton = -1;
      geometryGizmoDrag = null;
      geometryClickCapturedButton = -1;
      UNDO_PRESS.cancel();
      undoPressCaptured = false;
      cancelPointerGesture();
      minecraft.options.keyAttack.setDown(false);
      minecraft.options.keyUse.setDown(false);
   }

   private static void cancelWorkspaceEditIfPresent() {
      if (workspaceGizmoDrag != null) {
         ClientOperationController.cancelTransformGesture(workspaceGizmoDrag.editToken());
      } else if (workspaceFaceDrag != null) {
         ClientOperationController.cancelTransformGesture(workspaceFaceDrag.editToken());
      }
      workspaceGizmoDrag = null;
      workspaceFaceDrag = null;
      cancelPointerGesture();
   }

   private static boolean ownsPointerGesture(PointerGestureState.Kind kind) {
      return POINTER_GESTURE.owns(pointerGestureToken, kind);
   }

   private static void finishPointerGesture() {
      POINTER_GESTURE.finish(pointerGestureToken);
      pointerGestureToken = 0L;
   }

   private static void cancelPointerGesture() {
      POINTER_GESTURE.cancel();
      pointerGestureToken = 0L;
   }

}
