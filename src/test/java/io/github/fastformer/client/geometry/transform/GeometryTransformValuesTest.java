package io.github.fastformer.client.geometry.transform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.model.TransformFrame;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryTransformValuesTest {
   @Test void inputAndHudShareBaselineAndIntermediateBreaksResetIt() {
      var state = new AtomicReference<>(polyhedron(Vec3.ZERO, new Vec3(1,1,1), true));
      var version = new AtomicLong(1);
      var values = new GeometryTransformValues(state::get, version::get);
      assertEquals(0, values.value(null, Axis.X, AxisGizmo.Operation.MOVE));
      state.set(polyhedron(new Vec3(3,4,5), new Vec3(2,3,4), true));
      assertEquals(3, values.value(null, Axis.X, AxisGizmo.Operation.MOVE));
      assertEquals(4, values.value(null, Axis.Z, AxisGizmo.Operation.SCALE));
      version.addAndGet(2);
      assertEquals(0, values.value(null, Axis.X, AxisGizmo.Operation.MOVE));
      assertEquals(1, values.value(null, Axis.Z, AxisGizmo.Operation.SCALE));
      state.set(polyhedron(new Vec3(8,4,5), new Vec3(2,3,4), true));
      assertEquals(5, values.value(null, Axis.X, AxisGizmo.Operation.MOVE));
      values.clear();
      assertEquals(0, values.value(null, Axis.X, AxisGizmo.Operation.MOVE));
   }

   @Test void movementValueFollowsLocalGizmoAxisAndOwnersStayIndependent() {
      var state = new AtomicReference<>(polyhedron(Vec3.ZERO, new Vec3(1,1,1), true));
      var first = new GeometryTransformValues(state::get, () -> 1);
      var second = new GeometryTransformValues(state::get, () -> 1);
      first.value(null, Axis.X, AxisGizmo.Operation.MOVE);
      state.set(polyhedron(new Vec3(0,0,6), new Vec3(1,1,1), true));
      var frame = TransformFrame.local(new Vec3(0,0,6), new Vec3(0,0,1), new Vec3(0,1,0), new Vec3(-1,0,0));
      var gizmo = AxisGizmo.inFrame(frame, 2, 0.1, new AxisGizmo.Operation[]{AxisGizmo.Operation.MOVE});
      var plan = GeometryPreviewPlan.builder(List.of(), null).gizmo(gizmo).build();
      assertEquals(6, first.value(plan, Axis.X, AxisGizmo.Operation.MOVE));
      assertEquals(0, second.value(plan, Axis.X, AxisGizmo.Operation.MOVE));
      state.set(polyhedron(Vec3.ZERO, new Vec3(1,1,1), false));
      assertEquals(1, first.value(null, Axis.X, AxisGizmo.Operation.SCALE));
   }

   private static GeometryPreviewPayload polyhedron(Vec3 point, Vec3 scale, boolean closed) {
      var p = GeometryPreviewPayload.inactive();
      return new GeometryPreviewPayload(true, GeometryMode.POLYHEDRON, List.of(BlockPos.containing(point)), List.of(point), null,
         closed, false, BlockPos.ZERO, p.polyhedronShapeVariant(), p.coneShapeVariant(), p.compoundShapeVariant(), p.polyhedronSizeMode(),
         p.fillMode(), p.conePlaneMode(), p.coneRadius(), p.coneScaleX(), p.coneScaleZ(), p.coneTopScaleOffset(), p.coneTopOffset(),
         p.coneRotationRadians(), p.coneGizmoLocal(), p.rotation(), scale, scale, p.polyhedronGizmoLocal(), p.selectedPointIndex());
   }
}
