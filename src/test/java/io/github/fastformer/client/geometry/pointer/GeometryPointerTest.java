package io.github.fastformer.client.geometry.pointer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryPointerTest {
   private final InteractionContext context = new InteractionContext(null, null, new Vec3(0.5,0.5,-5),
      new Vec3(0,0,1), new Vec3(0.5,0.5,-5), false, false, false);

   @Test void candidatesUseCapturedHitsAndWallStartKeepsWorldOcclusion() {
      var state = new AtomicReference<>(wall(List.of(BlockPos.ZERO), false));
      var ray = new AtomicReference<>(ray(5));
      var pointer = pointer(state, () -> context, ray);
      assertEquals(new BlockPos(0,0,5), pointer.candidateHit().point());
      assertEquals(BlockPos.ZERO, pointer.pointUnderCrosshair());
      ray.set(ray(-2));
      assertNull(pointer.pointUnderCrosshair());
      assertFalse(pointer.awaitingFirstPoint());
      state.set(wall(List.of(), false));
      assertTrue(pointer.awaitingFirstPoint());
      state.set(wall(List.of(BlockPos.ZERO), true));
      assertNull(pointer.pointUnderCrosshair());
   }

   @Test void missingContextOrVanillaOwnershipCannotCaptureTargets() {
      var state = new AtomicReference<>(wall(List.of(BlockPos.ZERO), false));
      var ray = new AtomicReference<>(ray(5));
      var missing = pointer(state, () -> null, ray);
      assertNull(missing.candidateHit());
      assertNull(missing.interactionHit());
      assertNull(missing.gizmoHit());
      assertNull(missing.gizmo());
      var vanilla = new InteractionContext(null, null, context.eye(), context.view(), context.camera(), false, false, true);
      var paused = pointer(state, () -> vanilla, ray);
      assertNull(paused.candidateHit());
      assertNull(paused.interactionHit());
      assertNull(paused.gizmoHit());
   }

   @Test void ownersKeepIndependentAcceptedSnapshots() {
      var firstState = new AtomicReference<>(wall(List.of(), false));
      var secondState = new AtomicReference<>(wall(List.of(BlockPos.ZERO), false));
      var ray = new AtomicReference<>(ray(5));
      var first = pointer(firstState, () -> context, ray);
      var second = pointer(secondState, () -> context, ray);
      assertTrue(first.awaitingFirstPoint());
      assertFalse(second.awaitingFirstPoint());
      firstState.set(GeometryPreviewPayload.inactive());
      first.clear();
      assertFalse(first.allows(GeometryAction.CANDIDATE_INPUT));
      assertNotNull(second.candidateHit());
   }

   private GeometryPointer pointer(AtomicReference<GeometryPreviewPayload> state,
      java.util.function.Supplier<InteractionContext> context, AtomicReference<LongRangeBlockRaycast.Result> ray) {
      return new GeometryPointer(state::get, () -> 1, () -> 1, context, ignored -> ray.get(), () -> null,
         (message, failure) -> fail(message, failure));
   }

   private static LongRangeBlockRaycast.Result ray(int z) {
      return new LongRangeBlockRaycast.Result(new BlockHitResult(new Vec3(0.5,0.5,z), Direction.NORTH, new BlockPos(0,0,z), false), 20, 20, null, 1, 0);
   }

   private static GeometryPreviewPayload wall(List<BlockPos> points, boolean closed) {
      var p = GeometryPreviewPayload.inactive();
      return new GeometryPreviewPayload(true, GeometryMode.WALL, points, null, null, closed, false,
         BlockPos.ZERO, p.polyhedronShapeVariant(), p.coneShapeVariant(), p.compoundShapeVariant(), p.polyhedronSizeMode(),
         p.fillMode(), p.conePlaneMode(), p.coneRadius(), p.coneScaleX(), p.coneScaleZ(), p.coneTopScaleOffset(), p.coneTopOffset(),
         p.coneRotationRadians(), p.coneGizmoLocal(), p.rotation(), p.polyhedronLocalScale(), p.polyhedronWorldScale(),
         p.polyhedronGizmoLocal(), p.selectedPointIndex());
   }
}
