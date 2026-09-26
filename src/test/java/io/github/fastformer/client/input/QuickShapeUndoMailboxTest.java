package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import io.github.fastformer.client.render.state.ClientPreviewState;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.preview.*;
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
      var defaults = BuildingPreviewPayload.inactive();
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
}
