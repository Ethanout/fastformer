package io.github.fastformer.client.render.core;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.fastformer.client.interaction.SmartSelectionEditView;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import io.github.fastformer.client.render.SmartSelectionCellMesh;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.cache.BuildingShellFaceBuffer;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Selection cells remain visible through occluders without changing chunk meshes. */
final class SmartSelectionIsolationRenderer {
   private static final Map<Integer, Cells> CACHE = new HashMap<>();
   private static final SmartSelectionComposite COMPOSITE = new SmartSelectionComposite();
   private static TextureTarget target;
   private static Object level;
   private static Object shaderIdentity;
   private SmartSelectionIsolationRenderer() { }

   static void render(RenderLevelStageEvent event, Minecraft minecraft) {
      if (!SmartSelectionEditView.active(minecraft)) {
         if (target != null) {
            clear();
            minecraft.getMainRenderTarget().bindWrite(true);
         }
         return;
      }
      if (level != minecraft.level || shaderIdentity != FastPlaceClientShaders.smartSelectionComposite()) {
         clear();
         level = minecraft.level;
         shaderIdentity = FastPlaceClientShaders.smartSelectionComposite();
      }
      var workspace = ClientOperationController.workspace();
      CACHE.entrySet().removeIf(entry -> {
         if (workspace.part(entry.getKey()).filter(part -> part.smart()).isPresent()) return false;
         entry.getValue().close();
         return true;
      });
      var main = minecraft.getMainRenderTarget();
      minecraft.renderBuffers().bufferSource().endBatch();
      var modelView = RenderSystem.getModelViewStack();
      modelView.pushMatrix();
      modelView.mul(event.getModelViewMatrix());
      RenderSystem.applyModelViewMatrix();
      try {
         if (target == null) target = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
         else if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height, Minecraft.ON_OSX);
         target.setClearColor(0, 0, 0, 0);
         target.clear(Minecraft.ON_OSX);
         target.bindWrite(true);
         RenderSystem.setShaderColor(1, 1, 1, 1);
         for (var part : workspace.parts()) {
            if (!part.smart()) continue;
            var blocks = WorkspaceInteractionResolver.resolveVisiblePartBlocks(part);
            Cells cells = CACHE.get(part.id());
            if (cells == null || cells.blocks != blocks || cells.editable != part.smartEditable()) {
               if (cells != null) cells.close();
               cells = new Cells(blocks, part.smartEditable());
               CACHE.put(part.id(), cells);
            }
            cells.faces.draw(event.getPoseStack(), event.getCamera().getPosition(), cells.mesh.faces(),
               PreviewRenderTypes.SMART_SELECTION_CELLS, 1.0F);
            cells.edges.draw(event.getPoseStack(), event.getCamera().getPosition(), cells.mesh.edges(),
               PreviewRenderTypes.GHOST_OUTLINE_LINES, 1.0F);
         }
         renderTarget(event, minecraft);
         main.bindWrite(true);
         COMPOSITE.draw(target, main, event.getProjectionMatrix());
      } finally {
         modelView.popMatrix();
         RenderSystem.applyModelViewMatrix();
         main.bindWrite(true);
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
         RenderSystem.setShaderColor(1, 1, 1, 1);
      }
   }

   private static void renderTarget(RenderLevelStageEvent event, Minecraft minecraft) {
      var picked = SmartSelectionEditView.target(minecraft, true);
      if (picked == null) return;
      BlockPos position = picked.pick().position();
      var color = GeometryPalette.ochre();
      var boxes = java.util.List.of(new AABB(position).inflate(io.github.fastformer.client.render.PreviewStyle.OUTLINE_INFLATE));
      var tint = new ShapeShellMesh.Color(color.red(), color.green(), color.blue());
      var mesh = ShapeShellMesh.build(java.util.List.of(new ShapeShellMesh.Part(boxes, tint, tint, true)));
      var buffers = minecraft.renderBuffers().bufferSource();
      // The selection target contains cell depth, without the world's occluders.
      RenderSystem.enableDepthTest();
      ShapeShellRenderer.renderEdges(event.getPoseStack(), buffers.getBuffer(PreviewRenderTypes.SMART_SELECTION_TARGET_LINES),
         event.getCamera().getPosition(), mesh.edges(), 1.0F);
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
      final Map<BlockPos, ClientBlockSnapshot> blocks;
      final boolean editable;
      final ShapeShellMesh.Mesh mesh;
      final BuildingShellFaceBuffer faces = new BuildingShellFaceBuffer();
      final BuildingShellEdgeBuffer edges = new BuildingShellEdgeBuffer();
      Cells(Map<BlockPos, ClientBlockSnapshot> blocks, boolean editable) {
         this.blocks = blocks;
         this.editable = editable;
         mesh = SmartSelectionCellMesh.build(blocks.keySet(), editable);
      }
      void close() { faces.clear(); edges.clear(); }
   }
}
