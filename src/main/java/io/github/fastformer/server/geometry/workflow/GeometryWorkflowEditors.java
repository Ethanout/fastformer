package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflows;
import io.github.fastformer.core.registry.Registry;
import java.util.Comparator;
import java.util.List;

/** Server draft command registrations are checked against the shared planner catalog. */
public final class GeometryWorkflowEditors {
   private static final Registry<GeometryMode, GeometryWorkflowEditor<?>> EDITORS = Registry.create("geometry editors");
   static {
      register(new WallWorkflowEditor());
      register(new PolyhedronWorkflowEditor());
      register(new ConePrismWorkflowEditor());
      register(new CompoundWorkflowEditor());
      register(new ConvexPolyhedronWorkflowEditor());
      for (var workflow : GeometryWorkflows.registered()) get(workflow.mode());
      EDITORS.freeze();
   }
   private GeometryWorkflowEditors() { }
   private static void register(GeometryWorkflowEditor<?> editor) { EDITORS.register(editor.mode(), editor); }
   public static GeometryWorkflowEditor<?> get(GeometryMode mode) {
      return EDITORS.get(mode).orElseThrow(() -> new IllegalArgumentException("No geometry editor registered for " + mode));
   }
   public static List<GeometryWorkflowEditor<?>> registered() {
      return EDITORS.ordered(Comparator.comparingInt(editor -> editor.mode().ordinal())).toList();
   }
}
