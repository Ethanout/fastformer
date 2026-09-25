package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.render.state.ClientPreviewState;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.preview.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuickShapePointerMailboxTest {
   private QuickShapePointerPress press(long revision) {
      var state = new ClientPreviewState();
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var defaults = BuildingPreviewPayload.inactive();
      var draft = new BuildingPreviewSession(true, true, true, false, false, false,
         defaults.polygonVolumeShape(), List.of(new BlockPos(1, 2, 3)), BlockPos.ZERO, null);
      state.applyBuildingSession(new BuildingPreviewSessionPayload(revision, draft, scope));
      state.applyBuildingParameters(new BuildingPreviewParametersPayload(revision, defaults.parameters(), scope));
      state.applyBuildingEffect(new BuildingPreviewEffectPayload(revision, new BuildingPreviewEffectSnapshot(null), scope));
      return new QuickShapePointerPress(state.buildingSubmission().orElseThrow(),
         new PathClosePress(false, true, new BlockPos(4, 2, 3), false, 100), Vec3.ZERO, new Vec3(1, 0, 0), false, false);
   }

   @Test
   void mouseKeyboardAndScrollKeepTheirPostingOrder() {
      var input = new ClientInputSession();
      var pointer = press(1);
      input.postQuickShapePointer(pointer);
      input.postKeyboard(new KeyboardInputSnapshot(257, 0, 1, 0, 200, false, false));
      input.postScroll(new ScrollInputSnapshot(1));
      var delivered = new ArrayList<Object>();
      input.drainPhysicalEvents(() -> true, delivered::add, delivered::add, delivered::add,
         delivered::add, delivered::add, delivered::add);
      assertEquals(3, delivered.size());
      assertSame(pointer, delivered.getFirst());
      assertInstanceOf(KeyboardInputSnapshot.class, delivered.get(1));
      assertInstanceOf(ScrollInputSnapshot.class, delivered.get(2));
      input.drainPhysicalEvents(() -> true, e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail());
   }

   @Test
   void cancelBeforePointerDiscardsIt() {
      var input = new ClientInputSession();
      input.routing.observe(ClientInputStateMachine.State.BUILDING);
      input.postKeyboard(new KeyboardInputSnapshot(81, 0, 1, 0, 50, false, false));
      input.postQuickShapePointer(press(1));
      input.drainPhysicalEvents(() -> true, e -> assertTrue(input.cancel()), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), e -> fail());
      assertFalse(input.hasQueuedPhysicalInput());
   }

   @Test
   void staleDraftAndSubmissionWaitingRejectPointer() {
      var input = new ClientInputSession();
      input.routing.observe(ClientInputStateMachine.State.BUILDING);
      var pointer = press(1);
      assertTrue(pointer.matches(input.routing, pointer.draft()));
      assertFalse(pointer.matches(input.routing, press(2).draft()));
      assertTrue(input.routing.submit(1));
      assertFalse(pointer.matches(input.routing, pointer.draft()));
   }

   @Test
   void inputPostedDuringDispatchWaitsForNextTick() {
      var input = new ClientInputSession();
      var pointer = press(1);
      input.postQuickShapePointer(pointer);
      var delivered = new ArrayList<Object>();
      input.drainPhysicalEvents(() -> true, e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> {
         delivered.add(e);
         input.postQuickShapePointer(pointer);
      });
      assertEquals(List.of(pointer), delivered);
      input.drainPhysicalEvents(() -> true, e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail(), delivered::add);
      assertEquals(List.of(pointer, pointer), delivered);
   }
}
