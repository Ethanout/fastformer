package io.github.fastformer.fastplace;

import io.github.fastformer.common.geometry.session.GeometrySession;

import io.github.fastformer.core.math.Axis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.server.geometry.workflow.GeometryActionContext;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.preview.GeometryPreviewGuides;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.ControlPointRole;
import io.github.fastformer.common.geometry.controlpoint.ControlPointShape;
import io.github.fastformer.common.geometry.generation.shape.ConePrismGeometry;
import io.github.fastformer.common.geometry.generation.shape.ConePrismParameters;
import io.github.fastformer.common.selection.session.*;
import io.github.fastformer.common.geometry.workflow.plan.ConePrismWorkflow;
import io.github.fastformer.server.geometry.workflow.ConePrismWorkflowEditor;
import io.github.fastformer.common.geometry.workflow.*;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ConePrismGizmoTest {
   private static final Vec3 CENTER = new Vec3(0.5, 0.5, 0.5);

   @Test
   void localGizmoTransformsTheWholeShapeWithoutEditingTheTopFace() {
      GeometrySession session = radiusConeSession();
      ConePrismWorkflowEditor workflow = new ConePrismWorkflowEditor();
      GeometryActionContext context = new GeometryActionContext(null, false);
      List<Vec3> originalPoints = session.pointLocations();
      double originalHeight = height(session);

      assertTrue(workflow.onGizmoDrag(session, context, AxisGizmo.Operation.MOVE, Axis.X, 2));
      Vec3 translation = session.pointLocations().getFirst().subtract(originalPoints.getFirst());
      assertEquals(1.0, translation.length(), 1.0E-9);
      for (int index = 0; index < originalPoints.size(); index++) {
         assertEquals(translation, session.pointLocations().get(index).subtract(originalPoints.get(index)));
      }
      assertEquals(Vec3.ZERO, session.cone().coneTopOffset());
      assertEquals(originalHeight, height(session), 1.0E-9);

      assertTrue(workflow.onGizmoDrag(session, context, AxisGizmo.Operation.SCALE, Axis.X, 2));
      assertTrue(session.cone().coneScaleX() > 1.0);
      assertEquals(0.0, session.cone().coneTopScaleOffset());

      assertTrue(workflow.onGizmoDrag(session, context, AxisGizmo.Operation.ROTATE, Axis.Y, 256));
      assertEquals(Math.PI * 0.5, session.cone().coneRotationRadians(), 1.0E-9);
   }

   @Test
   void gizmoIsCenteredOnTheWholeBody() {
      GeometrySession session = radiusConeSession();
      ConePrismWorkflow workflow = new ConePrismWorkflow();
      var plan = workflow.previewPlan(
         GeometryWorkflowView.from(session), session.points(), null, (net.minecraft.core.BlockPos)null, Vec3.ZERO
      );

      assertNotNull(plan.gizmo());
      assertEquals(new Vec3(0.5, 3.0, 0.5), plan.gizmo().center());
   }

   @Test
   void rotationChangesTheSectionFrameAndTopOffsetDirectionTogether() {
      ConePrismGeometry unrotated = ConePrismGeometry.from(parameters(0.0));
      ConePrismGeometry rotated = ConePrismGeometry.from(parameters(Math.PI * 0.5));

      assertTrue(unrotated != null && rotated != null);
      assertEquals(1.0, rotated.base().axisU().dot(unrotated.base().axisV()), 1.0E-9);
      assertEquals(1.0, rotated.topCenter().subtract(rotated.base().center()).dot(rotated.base().axisU()), 1.0E-9);
      assertEquals(0.0, rotated.topCenter().subtract(rotated.base().center()).dot(rotated.base().axisV()), 1.0E-9);
   }

   @Test
   void precisionFacePreviewKeepsBlockCentersAndCollapsesSpecialLocationsToPoints() {
      var points = GeometryPreviewGuides.conePoints(
         new ConePrismParameters(
            List.of(CENTER, new Vec3(4.0, 0.5, 0.5)),
            Optional.empty(),
            0,
            ConePlaneMode.RADIUS,
            4.0,
            1.0,
            1.0,
            0.0,
            Vec3.ZERO,
            0.0
         ),
         List.of(ControlPointRole.BASE_CENTER, ControlPointRole.RADIUS),
         2,
         true
      );

      assertEquals(ControlPointShape.BLOCK, points.get(0).shape());
      assertEquals(ControlPointShape.POINT, points.get(1).shape());
      assertEquals(new Vec3(0.5, 0.5, 0.5), points.get(0).shape().visualHalfExtents(points.get(0).center()));
      assertEquals(new Vec3(0.5, 0.5, 0.5), points.get(1).shape().visualHalfExtents(points.get(1).center()));
   }

   private static GeometrySession radiusConeSession() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.CONE_PRISM);
      session.addPoint(CENTER);
      session.cone().setConeRadius(4.0);
      session.addPoint(CENTER.add(4.0, 0.0, 0.0));
      session.addPoint(CENTER.add(0.0, 5.0, 0.0));
      return session;
   }

   private static double height(GeometrySession session) {
      return session.pointLocations().get(session.cone().coneFacePointCount()).y - CENTER.y;
   }

   private static ConePrismParameters parameters(double rotation) {
      return new ConePrismParameters(
         List.of(CENTER, CENTER.add(4.0, 0.0, 0.0)),
         Optional.of(CENTER.add(0.0, 5.0, 0.0)),
         0,
         ConePlaneMode.RADIUS,
         4.0,
         1.0,
         1.0,
         0.0,
         new Vec3(1.0, 0.0, 0.0),
         rotation
      );
   }
}
