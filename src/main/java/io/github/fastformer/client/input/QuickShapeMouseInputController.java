package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.quickshape.input.QuickShapePointerPress;
import io.github.fastformer.client.quickshape.input.QuickShapeUndoGesture;
import io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload;
import io.github.fastformer.protocol.c2s.geometry.ScrollCandidatePayload;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.QuickShapeLineCommand;
import io.github.fastformer.client.interaction.binding.context.QuickShapeLineContext;
import io.github.fastformer.client.interaction.binding.tool.QuickShapeLineBindingInput;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Owns quick-shape mouse confirmation and path-close decisions. */
final class QuickShapeMouseInputController {
   private static final BindingCommands LINE_PROBE = new BindingCommands() {
      public boolean quickShapeLine(QuickShapeLineCommand command) { return true; }
      public boolean start(net.minecraft.resources.ResourceLocation profile) { return false; }
      public boolean point() { return false; }
      public boolean middleConfirm() { return false; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { return false; }
      public boolean cycleMode() { return false; }
      public void replayPress(long time) { }
   };
   private QuickShapeMouseInputController() {
   }

   static QuickShapeLineBindingInput.Result captureLine(Minecraft minecraft, ClientInputSession session,
      int action, int button, long time, boolean alt) {
      session.bindings.synchronizeLineDefinitions(minecraft, session);
      var draft = ClientSessionSystem.instance().previews().buildingSubmission().orElse(null);
      if (!ownsLine(session, draft)) return new QuickShapeLineBindingInput.Result(false, false);
      var path = action == MouseButtonInputSemantics.PRESS && (button == 1 || button == 2)
         ? PathCloseInputDispatcher.capture(false, time) : null;
      boolean overlaps = path != null && io.github.fastformer.common.quickshape.QuickShapeOverlap.overlaps(
         draft.data().points(), path.candidate(), draft.data().faceMode(), draft.data().polygonClosed());
      var context = new QuickShapeLineContext(InteractionContext.vanillaOwnsPointer(minecraft), overlaps,
         NetworkRegistry.hasChannel(minecraft.getConnection(), QuickShapePointerPayload.TYPE.id()),
         ClientSessionSystem.instance().previews().building().middleConfirmEnabled(), session.quickShapeButtonCaptured(button),
         session.quickShapeUndo.physicalCaptured(), false, false);
      var commands = lineCommands(minecraft, session, draft, path, button, time, alt);
      try {
         var result = QuickShapeLineBindingInput.mouse(true, context, action, button, time, (name, gesture, facts, now) ->
            session.bindings.onLineSignal(minecraft, session, name, gesture, facts, commands));
         if (action == MouseButtonInputSemantics.PRESS) session.lineInput.pressed(button, result);
         return result;
      } finally {
         if (action == MouseButtonInputSemantics.RELEASE) session.finishLinePhysicalRelease(button);
      }
   }

   /** Uses the physical press policy or a side-effect-free probe; never repeats its command. */
   static QuickShapeLineBindingInput.Result interactionLine(Minecraft minecraft, ClientInputSession session, int button) {
      session.bindings.synchronizeLineDefinitions(minecraft, session);
      var draft = ClientSessionSystem.instance().previews().buildingSubmission().orElse(null);
      if (!ownsLine(session, draft) || button < 0 || button > 2)
         return new QuickShapeLineBindingInput.Result(false, false);
      boolean mappedMouse = switch (button) {
         case 0 -> minecraft.options.keyAttack.matchesMouse(button);
         case 1 -> minecraft.options.keyUse.matchesMouse(button);
         default -> minecraft.options.keyPickItem.matchesMouse(button);
      };
      var captured = mappedMouse ? session.lineInput.interaction(button) : null;
      if (captured != null) return captured;
      var candidate = button == 1 || button == 2 ? ClientSessionSystem.instance().pathCloseTarget().candidate() : null;
      boolean overlaps = candidate != null && io.github.fastformer.common.quickshape.QuickShapeOverlap.overlaps(
         draft.data().points(), candidate, draft.data().faceMode(), draft.data().polygonClosed());
      var context = new QuickShapeLineContext(InteractionContext.vanillaOwnsPointer(minecraft), overlaps,
         NetworkRegistry.hasChannel(minecraft.getConnection(), QuickShapePointerPayload.TYPE.id()),
         ClientSessionSystem.instance().previews().building().middleConfirmEnabled(), false, false, false, false);
      return QuickShapeLineBindingInput.mouse(true, context, MouseButtonInputSemantics.PRESS, button, 0,
         (name, gesture, facts, now) -> session.bindings.onLineSignal(minecraft, session, name, gesture, facts, LINE_PROBE));
   }

   static QuickShapeLineBindingInput.Result scrollLine(Minecraft minecraft, ClientInputSession session, double delta, long time) {
      session.bindings.synchronizeLineDefinitions(minecraft, session);
      var draft = ClientSessionSystem.instance().previews().buildingSubmission().orElse(null);
      if (!ownsLine(session, draft)) return new QuickShapeLineBindingInput.Result(false, false);
      boolean ready = minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null;
      var context = new QuickShapeLineContext(ready && InteractionContext.vanillaOwnsPointer(minecraft), false, false, false, false, false,
         ready && NetworkRegistry.hasChannel(minecraft.getConnection(), ScrollCandidatePayload.TYPE.id())
            && ClientSessionSystem.instance().usesScrollContext(), session.modifier.held());
      var commands = lineCommands(minecraft, session, draft, null, -1, time, false);
      return QuickShapeLineBindingInput.scroll(true, context, delta, time, (name, gesture, facts, now) ->
         session.bindings.onLineSignal(minecraft, session, name, gesture, facts, commands));
   }

   private static boolean ownsLine(ClientInputSession session, io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot draft) {
      return draft != null && session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.BUILDING
         && QuickShapeStage.fromPointCount(draft.data().points().size()) == QuickShapeStage.LINE;
   }

   private static BindingCommands lineCommands(Minecraft minecraft, ClientInputSession session,
      io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot draft, io.github.fastformer.client.input.gesture.PathClosePress path,
      int button, long time, boolean alt) {
      var eye = minecraft.player == null ? null : minecraft.player.getEyePosition();
      var view = minecraft.player == null ? null : minecraft.player.getViewVector(1);
      boolean modifierHeld = session.modifier.held();
      return new BindingCommands() {
         public boolean quickShapeLine(QuickShapeLineCommand command) {
            switch (command) {
               case POINT, MIDDLE -> {
                  if (!session.captureQuickShapeButton(button)) return true;
                  if (path != null && path.candidate() != null && eye != null)
                      session.postLinePointer(new QuickShapePointerPress(draft, path, eye, view, alt, command == QuickShapeLineCommand.MIDDLE));
               }
               case BEGIN_UNDO -> {
                  if (eye != null) {
                     var press = session.quickShapeUndo.press(draft, time, eye, view);
                      if (press != null) session.postLineUndo(press);
                  }
               }
               case END_UNDO -> {
                  var release = session.quickShapeUndo.release(button, time);
                   if (release != null) session.postLineUndo(release);
               }
               case RELEASE_POINTER -> session.releaseQuickShapeButton(button);
               case SCROLL_UP, SCROLL_DOWN -> {
                   session.postLineScroll(new ScrollInputSnapshot(command == QuickShapeLineCommand.SCROLL_UP ? 1 : -1));
                  if (modifierHeld) session.modifier.consume();
               }
               case YIELD_SCROLL -> session.modifier.consume();
            }
            return true;
         }
         public boolean start(net.minecraft.resources.ResourceLocation profile) { return false; }
         public boolean point() { return false; }
         public boolean middleConfirm() { return false; }
         public boolean undoPoint() { return false; }
         public boolean cancel() { return false; }
         public boolean cycleMode() { return false; }
         public void replayPress(long time) { }
      };
   }

   static boolean captureUndo(Minecraft minecraft, ClientInputSession session, int action, int button,
      long occurredAtNanos) {
      if (ownsLine(session, ClientSessionSystem.instance().previews().buildingSubmission().orElse(null))) return false;
      if (action == MouseButtonInputSemantics.RELEASE) {
         var release = session.quickShapeUndo.release(button, occurredAtNanos);
         if (release == null) return false;
         session.postQuickShapeUndo(release);
         return true;
      }
      if (action != MouseButtonInputSemantics.PRESS || button != MouseButtonInputSemantics.LEFT_BUTTON
         || session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.BUILDING
         || InteractionContext.vanillaOwnsPointer(minecraft)
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), QuickShapePointerPayload.TYPE.id())) return false;
      var draft = ClientSessionSystem.instance().previews().buildingSubmission().orElse(null);
      if (draft == null) return true;
      var press = session.quickShapeUndo.press(draft, occurredAtNanos,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1));
      if (press != null) session.postQuickShapeUndo(press);
      return true;
   }

   static void dispatchUndo(Minecraft minecraft, ClientInputSession session, QuickShapeUndoGesture.Event event) {
      if (event instanceof QuickShapeUndoGesture.Event.Press) session.modifier.consume();
      var press = session.quickShapeUndo.dispatch(event, session.routing,
         ClientSessionSystem.instance().previews().buildingSubmission().orElse(null));
      if (press == null) return;
      ClientPlacementRouter.quickShapePointer(minecraft, press.draft().revision(), press.draft().scope(),
         QuickShapePointerPayload.Action.UNDO, press.draft().data().points().getFirst(), press.eye(), press.view(), false);
   }

   static boolean capture(Minecraft minecraft, ClientInputSession session, int action, int button,
      long occurredAtNanos, boolean alt) {
      if (ownsLine(session, ClientSessionSystem.instance().previews().buildingSubmission().orElse(null))) return false;
      if (action == MouseButtonInputSemantics.RELEASE) return session.releaseQuickShapeButton(button);
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.BUILDING) return false;
      boolean middle = button == MouseButtonInputSemantics.MIDDLE_BUTTON;
      if (!middle && button != MouseButtonInputSemantics.RIGHT_BUTTON) return false;
      var draft = ClientSessionSystem.instance().previews().buildingSubmission().orElse(null);
      var path = PathCloseInputDispatcher.capture(false, occurredAtNanos);
      boolean overlap = draft != null && io.github.fastformer.common.quickshape.QuickShapeOverlap.overlaps(
         draft.data().points(), path.candidate(), draft.data().faceMode(), draft.data().polygonClosed());
      if (!middle && !overlap && InteractionContext.vanillaOwnsPointer(minecraft)) return false;
      if (middle && ClientSessionSystem.instance().quickShapePointer().middleIgnored()) {
         if (action == MouseButtonInputSemantics.PRESS) session.captureQuickShapeButton(button);
         return true;
      }
      if (action != MouseButtonInputSemantics.PRESS
         || middle && !ClientSessionSystem.instance().previews().building().middleConfirmEnabled()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), QuickShapePointerPayload.TYPE.id())) return false;
      if (!session.captureQuickShapeButton(button)) return true;
      if (draft == null || path.candidate() == null) return true;
      session.postQuickShapePointer(new QuickShapePointerPress(draft, path,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), alt, middle));
      return true;
   }

   static void dispatch(Minecraft minecraft, ClientInputSession session, QuickShapePointerPress press) {
      if (!press.matches(session.routing, ClientSessionSystem.instance().previews().buildingSubmission().orElse(null))) return;
      session.modifier.consume();
      QuickShapePointerPayload.Action action = press.middle() ? QuickShapePointerPayload.Action.MIDDLE
         : io.github.fastformer.common.quickshape.QuickShapeOverlap.overlaps(press.draft().data().points(), press.path().candidate(),
            press.draft().data().faceMode(), press.draft().data().polygonClosed())
            ? QuickShapePointerPayload.Action.SUBMIT_CURRENT
            : press.path().closes(session.pathClose) ? QuickShapePointerPayload.Action.CLOSE : QuickShapePointerPayload.Action.POINT;
      ClientPlacementRouter.quickShapePointer(minecraft, press.draft().revision(), press.draft().scope(), action,
         press.path().candidate(), press.eye(), press.view(), press.modifierHeld());
   }
}
