package io.github.fastformer.client.input;

import io.github.fastformer.client.preview.frame.ModifierReticleMode;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ModifierGestureState;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.ui.GeometryRadialScreen;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.protocol.c2s.geometry.CycleStageModePayload;
import io.github.fastformer.protocol.c2s.settings.ModifierStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import io.github.fastformer.client.input.policy.ModifierReticlePolicy;

/** Sends modifier state and stage-cycle commands after local policy accepts them. */
final class ModifierCommandDispatcher {
   private ModifierCommandDispatcher() { }

   static void press(Minecraft minecraft) {
      if (ServerGateway.supports(minecraft, ModifierStatePayload.TYPE)) {
         ServerGateway.send(new ModifierStatePayload(true));
      }
   }

   static void release(Minecraft minecraft) {
      if (ServerGateway.supports(minecraft, ModifierStatePayload.TYPE)) {
         ServerGateway.send(new ModifierStatePayload(false));
      }
   }

   static void cycle(Minecraft minecraft) {
      if (ClientSessionSystem.instance().operations().selectionEdits().cycleDraftMode()) return;
      if (minecraft.player != null && !PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && !ClientSessionSystem.instance().previews().building().active() && !ClientSessionSystem.instance().previews().geometry().active()
         && !QuickReplaceMode.active()) {
         ClientSessionSystem.instance().operations().selectionEdits().cycleIdleSelectionTool();
         return;
      }
      if (!ServerGateway.supports(minecraft, CycleStageModePayload.TYPE)) return;
      BlockPos candidate = ClientSessionSystem.instance().quickShapePointer().lineCandidate();
      ServerGateway.send(
         candidate != null ? new CycleStageModePayload(true, candidate) : CycleStageModePayload.INSTANCE
      );
   }

   static void handleAltTransition(Minecraft minecraft, boolean down) {
      handleAltTransition(minecraft, down, System.nanoTime());
   }

   static void handleAltTransition(Minecraft minecraft, boolean down, long occurredAtNanos) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (down == inputOwner.modifier.held()) {
         return;
      }
      if (!down) {
         releaseModifierState(minecraft, true, occurredAtNanos);
         return;
      }
      if (minecraft.screen != null || !modifierSubmodeAvailable(minecraft) && !modifierCycleAvailable(minecraft)) {
         return;
      }
      if (inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.KEY) == ClientInputStateMachine.Dispatch.OPERATION) {
         ClientSessionSystem.instance().operations().setAltMode(true);
      }
      boolean routed = minecraft.screen == null
         && modifierSubmodeAvailable(minecraft)
         && ServerGateway.supports(minecraft, ModifierStatePayload.TYPE);
      inputOwner.modifier.press(occurredAtNanos, modifierCycleAvailable(minecraft), routed);
      if (inputOwner.entryConsumedAlt) inputOwner.modifier.consume();
      if (routed) ModifierCommandDispatcher.press(minecraft);
   }

   static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle) {
      releaseModifierState(minecraft, allowStageCycle, System.nanoTime());
   }

   static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle, long occurredAtNanos) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      ModifierGestureState.Release release = inputOwner.modifier.release(occurredAtNanos, MODIFIER_SHORT_PRESS_NANOS);
      inputOwner.entryConsumedAlt = false;
      ClientSessionSystem.instance().operations().setAltMode(false);
      if (release.routed()) ModifierCommandDispatcher.release(minecraft);
      if (allowStageCycle && release.cyclesMode() && minecraft.screen == null) {
         ModifierCommandDispatcher.cycle(minecraft);
      }
   }

   static boolean modifierSessionActive() {
      return ClientSessionSystem.instance().previews().building().active()
         || ClientSessionSystem.instance().operationActive()
         || ClientSessionSystem.instance().previews().geometry().active()
            && (ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.MODE_CYCLE)
               || ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.SUBMODE));
   }

   static boolean modifierCycleAvailable(Minecraft minecraft) {
      return ClientSessionSystem.instance().previews().geometry().active()
         ? ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.MODE_CYCLE)
         : modifierSessionActive() || idleModeCycleAvailable(minecraft);
   }

   static boolean modifierSubmodeAvailable(Minecraft minecraft) {
      return ClientSessionSystem.instance().previews().geometry().active()
         ? ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.SUBMODE)
         : ClientSessionSystem.instance().previews().building().active()
            || ClientSessionSystem.instance().operationActive()
            || ClientSessionSystem.instance().quickShapePointer().raycastSubmodeAvailable(
               ClientSessionSystem.instance().previews().geometry().active() || ClientSessionSystem.instance().previews().operation().active())
            || idleModeCycleAvailable(minecraft);
   }

   static boolean idleModeCycleAvailable(Minecraft minecraft) {
      return minecraft.player != null
         && minecraft.player.isCreative() && ClientSessionSystem.instance().previews().building().enabled()
         && !QuickReplaceMode.active();
   }

   static void handleRadialKeyTransition(Minecraft minecraft, boolean down) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (down == inputOwner.radialChordDown) {
         return;
      }
      inputOwner.radialChordDown = down;
      if (down && tryOpenGeometryRadial(minecraft)) {
         inputOwner.modifier.consume();
      }
   }

   static void resetModifierState(Minecraft minecraft) {
      if (ClientSessionSystem.instance().inputSession().modifier.held()) {
         releaseModifierState(minecraft, false);
      }
   }

   static ModifierInputRoute modifierInputRoute(ModifierInput input) {
      if (ClientSessionSystem.instance().operations().operationSelectionConfirmed()) {
         return ModifierInputRoute.SESSION;
      }
      return ClientSessionSystem.instance().inputSession().modifier.held() && input == ModifierInput.SCROLL
         ? ModifierInputRoute.VANILLA
         : ModifierInputRoute.SESSION;
   }

   static void pollModifierKeys(Minecraft minecraft) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (minecraft.player == null || minecraft.getConnection() == null) {
         inputOwner.modifier.reset();
         ClientSessionSystem.instance().operations().setAltMode(false);
         inputOwner.radialChordDown = false;
         return;
      }
      handleAltTransition(minecraft, physicalAltDown(minecraft, false));
      handleRadialKeyTransition(minecraft, physicalCtrlDown(minecraft, false) && physicalAltDown(minecraft, false));
   }

   static boolean physicalAltDown(Minecraft minecraft, boolean eventPressed) {
      long window = minecraft.getWindow().getWindow();
      return eventPressed || InputConstants.isKeyDown(window, 342) || InputConstants.isKeyDown(window, 346);
   }

   static boolean physicalCtrlDown(Minecraft minecraft, boolean eventPressed) {
      long window = minecraft.getWindow().getWindow();
      return eventPressed || InputConstants.isKeyDown(window, 341) || InputConstants.isKeyDown(window, 345);
   }

   static boolean tryOpenGeometryRadial(Minecraft minecraft) {
      if (minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !ClientSessionSystem.instance().previews().building().active()
         && !ClientSessionSystem.instance().operationActive()
         && (!ClientSessionSystem.instance().previews().geometry().active() || ClientSessionSystem.instance().geometryPointer().awaitingFirstPoint())) {
         minecraft.setScreen(new GeometryRadialScreen());
         return true;
      }
      return false;
   }

   static boolean modifierHeld() {
      return ClientSessionSystem.instance().inputSession().modifier.held();
   }

   static boolean controlHeld() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player != null && physicalCtrlDown(minecraft, false);
   }

   static boolean operationAdjustModifierHeld() {
      return ClientSessionSystem.instance().inputSession().modifier.held();
   }

   static ModifierReticleMode modifierReticleMode() {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (!inputOwner.modifier.held() || inputOwner.radialChordDown
         || !modifierSubmodeAvailable(Minecraft.getInstance())) {
         return ModifierReticleMode.NONE;
      }
      var minecraft = Minecraft.getInstance();
      var sessions = ClientSessionSystem.instance();
      var player = minecraft.player;
      return ModifierReticlePolicy.resolve(sessions.previews().building(),
         sessions.previews().geometry(), sessions.previews().operation().active(), sessions.modeView().idleActionsAllowed(),
         player != null && player.isCreative(), QuickReplaceMode.active(), player != null && minecraft.getCameraEntity() == player,
         player != null && PlaceableItems.isPlaceable(player.getMainHandItem()));
   }

   private static final long MODIFIER_SHORT_PRESS_NANOS = 250_000_000L;
   enum ModifierInput { SCROLL }
   enum ModifierInputRoute { SESSION, VANILLA }
}
