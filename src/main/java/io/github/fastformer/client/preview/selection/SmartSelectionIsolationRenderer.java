package io.github.fastformer.client.preview.selection;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import io.github.fastformer.client.render.SmartSelectionCellMesh;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.cache.BuildingShellFaceBuffer;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.common.world.BlockSnapshot;
import java.util.HashMap;
import java.util.Map;
import io.github.fastformer.client.preview.frame.PreviewDrawContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.render.PreviewStyle;
import java.util.List;

/** Selection cells remain visible through occluders without changing chunk meshes. */
final class SmartSelectionIsolationRenderer {
   private static final Map<Integer, Cells> CACHE = new HashMap<>();
   private static final SmartSelectionComposite COMPOSITE = new SmartSelectionComposite();
   private static TextureTarget target;
   private static Object level;
   private static Object shaderIdentity;
   private SmartSelectionIsolationRenderer() { }

   static void render(WorldPreviewFrame frame, PreviewDrawContext graphics, SelectionPreviewModel model) {
      if (!model.isolation().active()) {
         if (target != null) {
            clear();
            graphics.mainTarget().bindWrite(true);
         }
         return;
      }
      if (level != graphics.level() || shaderIdentity != FastPlaceClientShaders.smartSelectionComposite()) {
         clear();
         level = graphics.level();
         shaderIdentity = FastPlaceClientShaders.smartSelectionComposite();
      }
      var workspace = model.workspace();
      CACHE.entrySet().removeIf(entry -> {
         if (workspace.parts().stream().anyMatch(part -> part.id() == entry.getKey() && part.smart())) return false;
         entry.getValue().close();
         return true;
      });
      var main = graphics.mainTarget();
      frame.buffers().endBatch();
      var modelView = RenderSystem.getModelViewStack();
      modelView.pushMatrix();
      modelView.mul(frame.modelView());
      RenderSystem.applyModelViewMatrix();
      try {
         if (target == null) target = new TextureTarget(main.width, main.height, true, graphics.mac());
         else if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height, graphics.mac());
         target.setClearColor(0, 0, 0, 0);
         target.clear(graphics.mac());
         target.bindWrite(true);
         RenderSystem.setShaderColor(1, 1, 1, 1);
         for (var part : workspace.parts()) {
            if (!part.smart()) continue;
            var blocks = model.workspace().blocks().getOrDefault(part.id(), Map.of());
            Cells cells = CACHE.get(part.id());
            if (cells == null || cells.blocks != blocks || cells.editable != part.smartEditable()) {
               if (cells != null) cells.close();
               cells = new Cells(blocks, part.smartEditable());
               CACHE.put(part.id(), cells);
            }
            cells.faces.draw(frame.pose(), frame.camera(), cells.mesh.faces(),
               PreviewRenderTypes.SMART_SELECTION_CELLS, 1.0F);
            cells.edges.draw(frame.pose(), frame.camera(), cells.mesh.edges(),
               PreviewRenderTypes.GHOST_OUTLINE_LINES, 1.0F);
         }
         renderTarget(frame, graphics, model);
         main.bindWrite(true);
         COMPOSITE.draw(target, main, frame.projection());
      } finally {
         modelView.popMatrix();
         RenderSystem.applyModelViewMatrix();
         main.bindWrite(true);
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
         RenderSystem.setShaderColor(1, 1, 1, 1);
      }
   }

   private static void renderTarget(WorldPreviewFrame frame, PreviewDrawContext graphics, SelectionPreviewModel model) {
      BlockPos position = model.isolation().target();
      if (position == null) return;
      var color = GeometryPalette.ochre();
      var boxes = List.of(new AABB(position).inflate(PreviewStyle.OUTLINE_INFLATE));
      var tint = new ShapeShellMesh.Color(color.red(), color.green(), color.blue());
      var mesh = ShapeShellMesh.build(List.of(new ShapeShellMesh.Part(boxes, tint, tint, true)));
      var buffers = frame.buffers();
      // The selection target contains cell depth, without the world's occluders.
      RenderSystem.enableDepthTest();
      ShapeShellRenderer.renderEdges(frame.pose(), buffers.getBuffer(PreviewRenderTypes.SMART_SELECTION_TARGET_LINES),
         frame.camera(), mesh.edges(), 1.0F);
      buffers.endBatch(PreviewRenderTypes.SMART_SELECTION_TARGET_LINES);
   }

   static void clear() {
      CACHE.values().forEach(Cells::close);
      CACHE.clear();
      if (target != null) { target.destroyBuffers(); target = null; }
      COMPOSITE.clear();
      level = null;
      shaderIdentity = null;
   }

   private static final class Cells {
      final Map<BlockPos, BlockSnapshot> blocks;
      final boolean editable;
      final ShapeShellMesh.Mesh mesh;
      final BuildingShellFaceBuffer faces = new BuildingShellFaceBuffer();
      final BuildingShellEdgeBuffer edges = new BuildingShellEdgeBuffer();
      Cells(Map<BlockPos, BlockSnapshot> blocks, boolean editable) {
         this.blocks = blocks;
         this.editable = editable;
         mesh = SmartSelectionCellMesh.build(blocks.keySet(), editable);
      }
      void close() { faces.clear(); edges.clear(); }
   }
}
