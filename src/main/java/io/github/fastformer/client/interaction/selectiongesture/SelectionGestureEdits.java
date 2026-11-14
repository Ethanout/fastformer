package io.github.fastformer.client.interaction.selectiongesture;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.core.math.Axis;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Workspace-independent edit leases. The adapter freezes its real baseline and owner. */
public interface SelectionGestureEdits {
   record Targets(UUID owner, Map<Integer, Long> identities) {
      public Targets { identities = Map.copyOf(identities); }
   }
   interface Edit {
      Targets targets();
      boolean valid();
      boolean finish();
      void cancel();
   }
   interface FaceEdit extends Edit {
      AabbAdjustDecision readiness();
      void update(int axis, boolean positive, int steps);
   }
   interface GizmoEdit extends Edit {
      boolean repeatsWholeGroup();
      int repeatStride();
      String update(int direction, int steps, double radians, Vec3 axisVector, Vec3 pivot);
   }
   boolean submissionPending();
   AabbAdjustDecision faceReadiness(int partId);
   FaceEdit beginFace(int partId);
   GizmoEdit beginGizmo(int partId, boolean common, boolean control, AxisGizmo.Operation operation, Axis axis);
   void focus(int partId, boolean control);
   void select(int partId, boolean toggle);
   String failureKey(AabbAdjustDecision decision);
}
