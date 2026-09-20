package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelectionPointMailboxTest {
   private ClientInputSession input;
   private ClientOperationWorkspace workspace;

   @BeforeEach
   void prepare() {
      ClientOperationController.clearWorkspace();
      input = FastPlaceClientInput.inputSession();
      input.reset();
      input.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      workspace = ClientOperationController.workspace();
      workspace.addParts(List.of(part()));
   }

   @AfterEach
   void clear() {
      input.reset();
      ClientOperationController.clearWorkspace();
   }

   @Test
   void freezesMutablePointAndPhysicalTime() {
      var point = new BlockPos.MutableBlockPos(1, 2, 3);
      var press = snapshot(point);
      point.set(9, 9, 9);
      assertEquals(new BlockPos(1, 2, 3), press.point());
      assertEquals(1234, press.occurredAtNanos());
      assertTrue(press.control());
   }

   @Test
   void middleOwnsThePointRouteWithoutAWorldHit() throws ReflectiveOperationException {
      var route = FastPlaceClientInput.class.getDeclaredMethod("selectionPressRoute",
         net.minecraft.client.Minecraft.class, OperationInteractionIntent.class, int.class);
      route.setAccessible(true);
      assertEquals("POINT", route.invoke(null, null, null, MouseButtonInputSemantics.MIDDLE_BUTTON).toString());
   }

   @Test
   void changingActiveSelectionRejectsTheOldTarget() {
      var press = snapshot(BlockPos.ZERO);
      workspace.addParts(List.of(part()));
      assertFalse(press.matches(owner(), workspace));
      assertEquals(2, workspace.activeId());
   }

   @Test
   void reusedNumericSlotDoesNotAcceptTheOldCommand() {
      var press = snapshot(BlockPos.ZERO);
      assertTrue(workspace.removeSelectedParts());
      workspace.addParts(List.of(part()));
      assertEquals(press.partId(), workspace.activeId());
      assertFalse(press.matches(owner(), workspace));
   }

   @Test
   void earlierPointEditDoesNotInvalidateTheSameSelectionInstance() {
      var press = snapshot(BlockPos.ZERO);
      assertTrue(workspace.beginEdit());
      var current = workspace.part(workspace.activeId()).orElseThrow();
      workspace.updatePart(current.withSelection(current.selection().withCuboidPoint(0, new BlockPos(-2, 0, 0))));
      assertTrue(workspace.finishEdit());
      assertTrue(press.matches(owner(), workspace));
      assertFalse(press.matches(UUID.randomUUID(), workspace));
   }

   @ParameterizedTest
   @ValueSource(ints = {0, 1, 2})
   void dispatchWithoutWorldCannotBypassSourceValidationAndReleasesCapture(int button) {
      var before = workspace.part(workspace.activeId()).orElseThrow();
      post(snapshot(button, new BlockPos(5, 5, 5)));
      drain();
      assertSame(before, workspace.part(workspace.activeId()).orElseThrow());
      assertFalse(input.selectionPointer.active());
      assertNull(workspace.activeEditToken());
   }

   @Test
   void cancellationDiscardsQueuedPointAndItsRelease() {
      var before = workspace.part(workspace.activeId()).orElseThrow();
      post(snapshot(new BlockPos(5, 5, 5)));
      assertTrue(input.cancel());
      input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(), event -> fail());
      assertSame(before, workspace.part(workspace.activeId()).orElseThrow());
      assertFalse(input.selectionPointer.active());
   }

   @ParameterizedTest
   @ValueSource(ints = {0, 1, 2})
   void missingHitStillSettlesTheOwnedButtonWithoutAnEdit(int button) {
      post(snapshot(button, null));
      assertTrue(input.blocksDraftLoad());
      drain();
      assertFalse(input.selectionPointer.active());
      assertNull(workspace.activeEditToken());
   }

   private UUID owner() { return ClientOperationController.interactionScene().owner(); }

   private SelectionPointPress snapshot(BlockPos point) {
      return snapshot(0, point);
   }

   private SelectionPointPress snapshot(int button, BlockPos point) {
      int id = workspace.activeId();
      return new SelectionPointPress(owner(), id, workspace.interactionId(id), button, true, point, 1234);
   }

   private void post(SelectionPointPress press) {
      input.captureSelectionPress(press);
      input.postSelectionPointer(input.selectionPointer.release(press.button(), 2000));
   }

   private void drain() {
      input.drainPhysicalEvents(() -> true, key -> fail(), scroll -> fail(),
         event -> SelectionInputDispatcher.dispatch(null, input, event));
   }

   private static ClientSelectionPart part() {
      return new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, new BlockPos(2, 2, 2)),
         Map.of(), WorkspaceTransform.IDENTITY, false);
   }
}
