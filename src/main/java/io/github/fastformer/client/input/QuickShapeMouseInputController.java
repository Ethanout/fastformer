package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.quickshape.input.QuickShapePointerPress;
import io.github.fastformer.client.quickshape.input.QuickShapeUndoGesture;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.placement.QuickShapePointerPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Owns quick-shape mouse confirmation and path-close decisions. */
final class QuickShapeMouseInputController {
   private QuickShapeMouseInputController() {
   }

   static boolean captureUndo(Minecraft minecraft, ClientInputSession session, int action, int button,
      long occurredAtNanos) {
      if (action == MouseButtonInputSemantics.RELEASE) {
         var release = session.quickShapeUndo.release(button, occurredAtNanos);
         if (release == null) return false;
         session.postQuickShapeUndo(release);
         return true;
      }
      if (action != MouseButtonInputSemantics.PRESS || button != MouseButtonInputSemantics.LEFT_BUTTON
         || session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.BUILDING
         || InteractionContext.nearVanillaBlock(minecraft)
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), QuickShapePointerPayload.TYPE.id())) return false;
      var draft = FastPlaceClientPreview.buildingSubmission().orElse(null);
      if (draft == null) return true;
      var press = session.quickShapeUndo.press(draft, occurredAtNanos,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1));
      if (press != null) session.postQuickShapeUndo(press);
      return true;
   }

   static void dispatchUndo(Minecraft minecraft, ClientInputSession session, QuickShapeUndoGesture.Event event) {
      if (event instanceof QuickShapeUndoGesture.Event.Press) session.modifier.consume();
      var press = session.quickShapeUndo.dispatch(event, session.routing,
         FastPlaceClientPreview.buildingSubmission().orElse(null));
      if (press == null) return;
      ClientPlacementRouter.quickShapePointer(minecraft, press.draft().revision(), press.draft().scope(),
         QuickShapePointerPayload.Action.UNDO, press.draft().data().points().getFirst(), press.eye(), press.view(), false);
   }

   static boolean capture(Minecraft minecraft, ClientInputSession session, int action, int button,
      long occurredAtNanos, boolean alt) {
      if (action == MouseButtonInputSemantics.RELEASE) return session.releaseQuickShapeButton(button);
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.BUILDING) return false;
      boolean middle = button == MouseButtonInputSemantics.MIDDLE_BUTTON;
      if (!middle && button != MouseButtonInputSemantics.RIGHT_BUTTON) return false;
      if (!middle && InteractionContext.nearVanillaBlock(minecraft)) return false;
      if (middle && FastPlaceClientPreview.buildingMiddleClickIgnored()) {
         if (action == MouseButtonInputSemantics.PRESS) session.captureQuickShapeButton(button);
         return true;
      }
      if (action != MouseButtonInputSemantics.PRESS
         || middle && !FastPlaceClientPreview.middleConfirmEnabled()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), QuickShapePointerPayload.TYPE.id())) return false;
      var draft = FastPlaceClientPreview.buildingSubmission().orElse(null);
      var path = PathCloseInputDispatcher.capture(false, occurredAtNanos);
      if (!session.captureQuickShapeButton(button)) return true;
      if (draft == null || path.candidate() == null) return true;
      session.postQuickShapePointer(new QuickShapePointerPress(draft, path,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), alt, middle));
      return true;
   }

   static void dispatch(Minecraft minecraft, ClientInputSession session, QuickShapePointerPress press) {
      if (!press.matches(session.routing, FastPlaceClientPreview.buildingSubmission().orElse(null))) return;
      session.modifier.consume();
      QuickShapePointerPayload.Action action = press.middle() ? QuickShapePointerPayload.Action.MIDDLE
         : press.path().closes(session.pathClose) ? QuickShapePointerPayload.Action.CLOSE : QuickShapePointerPayload.Action.POINT;
      ClientPlacementRouter.quickShapePointer(minecraft, press.draft().revision(), press.draft().scope(), action,
         press.path().candidate(), press.eye(), press.view(), press.modifierHeld());
   }
}
