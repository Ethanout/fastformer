package io.github.fastformer.client.input;

import io.github.fastformer.common.preview.quickshape.BuildingPreviewEffectSnapshot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSession;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuickShapeUndoMailboxTest {
   private static final long LIMIT = 250_000_000L;

   private QuickShapeSubmissionSnapshot snapshot() {
      var state = new ClientPreviewState();
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var defaults = BuildingPreviewSnapshot.inactive();
      var draft = new BuildingPreviewSession(true, true, true, false, false, false,
         defaults.polygonVolumeShape(), List.of(new BlockPos(1, 2, 3)), BlockPos.ZERO, null);
      state.applyBuildingSession(new BuildingPreviewSessionPayload(1, draft, scope));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(1, defaults.parameters(), scope));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(1, new BuildingPreviewEffectSnapshot(null), scope));
      return state.buildingSubmission().orElseThrow();
   }

   private ClientInputSession session() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      return session;
   }

   private void press(ClientInputSession input, QuickShapeSubmissionSnapshot draft, long time) {
      input.postQuickShapeUndo(input.quickShapeUndo.press(draft, time, Vec3.ZERO, new Vec3(1, 0, 0)));
   }

   private void release(ClientInputSession input, long time) {
      input.postQuickShapeUndo(input.quickShapeUndo.release(0, time));
   }

   private int drain(ClientInputSession input, QuickShapeSubmissionSnapshot draft) {
      var sent = new java.util.concurrent.atomic.AtomicInteger();
      input.drainPhysicalEvents(() -> true, event -> assertTrue(input.cancel()), e -> fail(), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), event -> {
            if (input.quickShapeUndo.dispatch(event, input.routing, draft) != null) sent.incrementAndGet();
         });
      return sent.get();
   }

   @Test
   void twoClicksInOneTickRetainBothPairsAndDispatchOnce() {
      var input = session();
      var draft = snapshot();
      press(input, draft, 100);
      release(input, 110);
      press(input, draft, 200);
      release(input, 210);
      assertEquals(2, drain(input, draft));
      assertEquals(0, drain(input, draft));
      assertFalse(input.quickShapeUndo.captured());
   }

   @Test
   void longPressUsesPhysicalDuration() {
      var input = session();
      var draft = snapshot();
      press(input, draft, 100);
      release(input, 101 + LIMIT);
      assertEquals(0, drain(input, draft));
   }

   @Test
   void cancelBetweenPressAndReleaseDropsUndo() {
      var input = session();
      var draft = snapshot();
      press(input, draft, 100);
      input.postKeyboard(new KeyboardInputSnapshot(81, 0, 1, 0, 105, false, false));
      release(input, 110);
      assertEquals(0, drain(input, draft));
      assertFalse(input.quickShapeUndo.captured());
   }

   @Test
   void wrongButtonKeepsPhysicalCapture() {
      var input = session();
      var draft = snapshot();
      press(input, draft, 100);
      assertNull(input.quickShapeUndo.release(1, 105));
      release(input, 110);
      assertEquals(1, drain(input, draft));
   }

   @Test
   void oldReleaseCannotFinishNewDispatchedCapture() {
      var input = session();
      var draft = snapshot();
      var old = input.quickShapeUndo.press(draft, 100, Vec3.ZERO, new Vec3(1, 0, 0));
      var oldRelease = input.quickShapeUndo.release(0, 110);
      var current = input.quickShapeUndo.press(draft, 200, Vec3.ZERO, new Vec3(1, 0, 0));
      input.quickShapeUndo.dispatch(old, input.routing, draft);
      input.quickShapeUndo.dispatch(current, input.routing, draft);
      assertNull(input.quickShapeUndo.dispatch(oldRelease, input.routing, draft));
      assertSame(current, input.quickShapeUndo.dispatch(input.quickShapeUndo.release(0, 210), input.routing, draft));
   }

   @Test
   void replacementDraftRejectsCapturedUndo() {
      var input = session();
      press(input, snapshot(), 100);
      release(input, 110);
      assertEquals(0, drain(input, snapshot()));
   }

   @Test
   void lineReleaseOverrideCancelsTheQueuedPressWithoutUndo() {
      var input = session();
      var draft = snapshot();
      input.postLineUndo(input.quickShapeUndo.press(draft, 100, Vec3.ZERO, new Vec3(1, 0, 0)));
      input.finishLinePhysicalRelease(0);
      assertFalse(input.quickShapeUndo.physicalCaptured());
      assertEquals(0, drain(input, draft));
      assertFalse(input.quickShapeUndo.captured());
   }

   @Test
   void defaultLineReleaseRetainsThePhysicalShortPressRule() {
      var input = session();
      var draft = snapshot();
      input.postLineUndo(input.quickShapeUndo.press(draft, 100, Vec3.ZERO, new Vec3(1, 0, 0)));
      input.postLineUndo(input.quickShapeUndo.release(0, 100 + LIMIT));
      input.finishLinePhysicalRelease(0);
      assertEquals(1, drain(input, draft));
      assertFalse(input.quickShapeUndo.captured());
   }

   @Test
   void actualLineRulesHonorDefaultVanillaAndMissingReleasePolicies() throws Exception {
      var path = "/assets/fastformer/fastformer/bindings/default/quick_shape_line.json";
      io.github.fastformer.client.interaction.binding.data.BindingFile defaults;
      try (var stream = getClass().getResourceAsStream(path)) {
         assertNotNull(stream);
         defaults = io.github.fastformer.client.interaction.binding.data.BindingFile.parse(
            com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)));
      }
      var vanilla = io.github.fastformer.client.interaction.binding.data.UserOverrides.merge(defaults,
         com.google.gson.JsonParser.parseString("{\"bindings\":[{\"id\":\"fastformer:quick_shape/line/undo_release\",\"action\":\"fastformer:vanilla\"}]}").getAsJsonObject());
      var missing = new io.github.fastformer.client.interaction.binding.data.BindingFile(
         defaults.bindings().stream().filter(binding -> !binding.input().gesture().equals("release")).toList(),
         defaults.options(), List.of());
      assertEquals(1, runBoundLineClick(defaults));
      assertEquals(0, runBoundLineClick(vanilla));
      assertEquals(0, runBoundLineClick(missing));
   }

   private int runBoundLineClick(io.github.fastformer.client.interaction.binding.data.BindingFile file) {
      var input = session();
      var draft = snapshot();
      var actions = io.github.fastformer.client.interaction.binding.action.BuiltinBindingActions.create();
      var rules = file.bindings().stream().sorted(io.github.fastformer.client.interaction.binding.data.InteractionBinding.ORDER)
         .map(binding -> new io.github.fastformer.client.interaction.binding.BindingSystem.Rule(
            binding, actions.resolve(binding.action()).getOrThrow())).toList();
      var system = new io.github.fastformer.client.interaction.binding.BindingSystem(
         new io.github.fastformer.client.interaction.binding.context.ContextMatcher(
            io.github.fastformer.client.interaction.binding.context.ContextFields.defaults().freeze()), () -> rules, id -> true);
      var commands = new io.github.fastformer.client.interaction.binding.action.BindingCommands() {
         public boolean quickShapeLine(io.github.fastformer.client.interaction.binding.action.QuickShapeLineCommand command) {
            switch (command) {
               case BEGIN_UNDO -> input.postLineUndo(input.quickShapeUndo.press(draft, 100, Vec3.ZERO, new Vec3(1, 0, 0)));
               case END_UNDO -> input.postLineUndo(input.quickShapeUndo.release(0, 110));
               default -> fail("unexpected line command: " + command);
            }
            return true;
         }
         public boolean start(ResourceLocation profile) { return false; }
         public boolean point() { return false; }
         public boolean middleConfirm() { return false; }
         public boolean undoPoint() { return false; }
         public boolean cancel() { return false; }
         public boolean cycleMode() { return false; }
         public void replayPress(long time) { fail("line input replayed an idle press"); }
      };
      for (int action : new int[]{1, 0}) {
         var context = new io.github.fastformer.client.interaction.binding.context.QuickShapeLineContext(
            false, false, true, true, false, input.quickShapeUndo.physicalCaptured(), false, false);
         try {
            io.github.fastformer.client.interaction.binding.tool.QuickShapeLineBindingInput.mouse(
               true, context, action, 0, action == 1 ? 100 : 110,
               (name, gesture, facts, time) -> system.onSignalResult(name, gesture, facts, commands));
         } finally {
            if (action == 0) input.finishLinePhysicalRelease(0);
         }
      }
      int sent = drain(input, draft);
      assertFalse(input.quickShapeUndo.captured());
      return sent;
   }

   @Test
   void reloadingLineBindingsDropsCapturedAndAlreadyQueuedUndo() {
      var input = session();
      var draft = snapshot();
      input.postLineUndo(input.quickShapeUndo.press(draft, 100, Vec3.ZERO, new Vec3(1, 0, 0)));
      input.postLineUndo(input.quickShapeUndo.release(0, 110));
      input.cancelLineBindingInput();
      assertEquals(0, drain(input, draft));
      assertFalse(input.quickShapeUndo.captured());
   }
}
