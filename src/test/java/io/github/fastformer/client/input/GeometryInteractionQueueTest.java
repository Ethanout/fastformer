package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.geometry.GeometryPointPayload;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryInteractionQueueTest {
   private final OperationCallbackScope scope = new OperationCallbackScope(UUID.randomUUID(),
      ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());

   private GeometryPreviewPayload preview(GeometryMode mode) {
      var p = GeometryPreviewPayload.inactive();
      return new GeometryPreviewPayload(true, mode, List.of(BlockPos.ZERO), null, null, false, false,
         BlockPos.ZERO, p.polyhedronShapeVariant(), p.coneShapeVariant(), p.compoundShapeVariant(),
         p.polyhedronSizeMode(), p.fillMode(), p.conePlaneMode(), p.coneRadius(), p.coneScaleX(),
         p.coneScaleZ(), p.coneTopScaleOffset(), p.coneTopOffset(), p.coneRotationRadians(),
         p.coneGizmoLocal(), p.rotation(), p.polyhedronLocalScale(), p.polyhedronWorldScale(),
         p.polyhedronGizmoLocal(), p.selectedPointIndex()).withRevision(7).withCallbackScope(scope);
   }

   private GeometryInputController.InteractionPress press(PointerGesture gesture) {
      return new GeometryInputController.InteractionPress(GeometryMode.WALL,
         GeometryInteractionPayload.clearSelection(1L, 7, scope, gesture, Vec3.ZERO, new Vec3(0, 0, 1)));
   }

   private GeometryInputController.PathPress path(long requestId, long occurredAtNanos) {
      return new GeometryInputController.PathPress(GeometryMode.WALL,
         new GeometryPointPayload(requestId, 7, scope, new Vec3(0, 10, 0), new Vec3(0, -1, 0)),
         new PathClosePress(false, true, new BlockPos(2, 0, 2), true, occurredAtNanos), true, true);
   }

   @Test
   void queuedDoubleClickUsesPhysicalTimeAndRetainsTheSecondRequestIdentity() {
      var session = new ClientInputSession();
      var first = path(1, 1_000_000_000L);
      var second = path(2, 1_200_000_000L);
      session.postGeometryPointer(first);
      session.postGeometryPointer(second);
      var sent = new ArrayList<Object>();
      drain(session, e -> fail(), event -> sent.add(((GeometryInputController.PathPress) event)
         .resolve(session.pathClose, true, true)));
      assertSame(first.point(), sent.getFirst());
      var close = assertInstanceOf(GeometryInteractionPayload.class, sent.getLast());
      assertEquals(GeometryInteractionAction.CLOSE_PATH, close.action());
      assertEquals(PointerGesture.RIGHT_DOUBLE_CLICK, close.gesture());
      assertEquals(2, close.requestId());
      assertEquals(7, close.revision());
      assertEquals(scope, close.callbackScope());
   }

   @Test
   void slowPhysicalClicksStayAsPointsEvenWhenDispatchedInOneTick() {
      var gesture = new PathCloseGesture();
      var first = path(1, 1_000_000_000L);
      var second = path(2, 1_400_000_000L);
      assertSame(first.point(), first.resolve(gesture, true, true));
      assertSame(second.point(), second.resolve(gesture, true, true));
      assertEquals(new Vec3(0, 10, 0), second.point().eye());
      assertEquals(new Vec3(0, -1, 0), second.point().view());
      assertFalse(second.matches(preview(GeometryMode.WALL).withRevision(8)));
   }

   @Test
   void missingCloseChannelResetsTheClickPair() {
      var gesture = new PathCloseGesture();
      path(1, 1_000_000_000L).resolve(gesture, true, true);
      var second = path(2, 1_100_000_000L);
      assertSame(second.point(), second.resolve(gesture, true, false));
      var third = path(3, 1_200_000_000L);
      assertSame(third.point(), third.resolve(gesture, true, true));
   }

   private void drain(ClientInputSession session, Consumer<KeyboardInputSnapshot> keys,
      Consumer<GeometryInputController.PointerPress> interactions) {
      session.drainPhysicalEvents(() -> true, keys, e -> fail(), e -> fail(), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail(), interactions);
   }

   @Test
   void dispatchRequiresTheCapturedActivePreviewIdentity() {
      var press = press(PointerGesture.LEFT_CLICK);
      var current = preview(GeometryMode.WALL);
      assertTrue(press.matches(current));
      assertFalse(press.matches(current.withRevision(8)));
      assertFalse(press.matches(current.withCallbackScope(OperationCallbackScope.unscoped())));
      assertFalse(press.matches(preview(GeometryMode.POLYHEDRON)));
      assertFalse(press.matches(GeometryPreviewPayload.inactive().withRevision(7).withCallbackScope(scope)));
   }

   @Test
   void releaseAndNextPressDoNotChangeQueuedTargetsOrKeyboardOrder() {
      var session = new ClientInputSession();
      var first = press(PointerGesture.LEFT_CLICK);
      var second = press(PointerGesture.RIGHT_CLICK);
      session.captureGeometryPointerButton(0);
      session.postGeometryPointer(first);
      assertFalse(session.releaseGeometryPointerButton(1));
      assertTrue(session.releaseGeometryPointerButton(0));
      session.postKeyboard(new KeyboardInputSnapshot(257, 0, 1, 0, 50, false, false));
      session.captureGeometryPointerButton(1);
      session.postGeometryPointer(second);
      assertTrue(session.releaseGeometryPointerButton(1));
      var events = new ArrayList<Object>();
      drain(session, e -> events.add("enter"), events::add);
      assertEquals(List.of(first, "enter", second), events);
      assertFalse(session.hasGeometryPointerButtons());
   }

   @Test
   void interactionPostedDuringDispatchWaitsForTheNextTick() {
      var session = new ClientInputSession();
      var first = press(PointerGesture.LEFT_CLICK);
      var next = press(PointerGesture.RIGHT_CLICK);
      session.postGeometryPointer(first);
      var events = new ArrayList<GeometryInputController.PointerPress>();
      drain(session, e -> fail(), event -> {
         events.add(event);
         session.postGeometryPointer(next);
      });
      assertEquals(List.of(first), events);
      assertTrue(session.hasQueuedPhysicalInput());
      drain(session, e -> fail(), events::add);
      assertEquals(List.of(first, next), events);
      assertFalse(session.hasQueuedPhysicalInput());
   }

   @Test
   void cancellationDiscardsPendingInteractionAndPhysicalOwnership() {
      var session = new ClientInputSession();
      session.postKeyboard(new KeyboardInputSnapshot(81, 0, 1, 0, 50, false, false));
      session.captureGeometryPointerButton(0);
      session.postGeometryPointer(press(PointerGesture.LEFT_CLICK));
      drain(session, e -> session.discardPhysicalEvents(), e -> fail());
      assertFalse(session.hasQueuedPhysicalInput());
      assertFalse(session.hasGeometryPointerButtons());
   }

   @Test
   void environmentLossDiscardsInteractionAndReleasesButtons() {
      var session = new ClientInputSession();
      session.captureGeometryPointerButton(1);
      session.postGeometryPointer(press(PointerGesture.RIGHT_CLICK));
      session.drainPhysicalEvents(() -> false, e -> fail(), e -> fail(), e -> fail(), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail());
      assertFalse(session.hasQueuedPhysicalInput());
      assertFalse(session.hasGeometryPointerButtons());
   }
}
