package io.github.fastformer.workspace.transform;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceSymmetry;
import io.github.fastformer.workspace.preview.Composition;
import io.github.fastformer.workspace.preview.CompositionBatch;
import io.github.fastformer.workspace.preview.CompositionBudget;
import io.github.fastformer.workspace.preview.WorkspaceSelectionBounds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** One atomic shortcut applied to the selected group around its whole selection centre. */
public final class SelectionShortcutCalculator {
   private SelectionShortcutCalculator() {
   }

   public static AxisGizmo.Axis viewAxis(Vec3 look) {
      double x = Math.abs(look.x);
      double y = Math.abs(look.y);
      double z = Math.abs(look.z);
      if (y > x && y > z) return AxisGizmo.Axis.Y;
      return x > z ? AxisGizmo.Axis.X : AxisGizmo.Axis.Z;
   }

   public static SelectionTransformCalculator.Result calculate(List<ClientSelectionPart> selected, WorkspaceSymmetry operation) {
      if (selected.isEmpty()) return new SelectionTransformCalculator.Result(selected, null);
      var bounds = WorkspaceSelectionBounds.wholeBox(selected);
      if (bounds == null) return new SelectionTransformCalculator.Result(selected, null);
      Vec3 pivot = bounds.getCenter();
      List<ClientSelectionPart> updates = new ArrayList<>();
      CompositionBatch batch = new CompositionBatch(CompositionBudget.INTERACTION);
      for (ClientSelectionPart part : selected) {
         var transform = part.transform();
         var symmetry = transform.symmetry().then(operation, pivot.subtract(transform.translation()));
         ClientSelectionPart updated = part.withTransform(transform.withSymmetry(symmetry)).fixed();
         Composition<ClientBlockSnapshot> composition = batch.compose(updated);
         if (composition instanceof Composition.OverBudget<?> over) {
            return new SelectionTransformCalculator.Result(List.of(), over.limit() == Composition.Limit.WORK
               ? "fastformer.message.workspace_work_too_large" : "fastformer.message.workspace_result_too_large");
         }
         updates.add(updated);
      }
      return new SelectionTransformCalculator.Result(updates, null);
   }
}
