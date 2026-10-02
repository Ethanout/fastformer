package io.github.fastformer.client.render.core;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.SmartSelectionAirOutline;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.cache.BuildingShellCache;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.hud.SelectionScreenProjection;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.fastplace.selection.SmartSelectionTopology;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Smart membership outlines and screen-space ownership marks share one immutable snapshot. */
final class SmartSelectionRenderer {
   private static final Map<Integer, Entry> CACHE = new HashMap<>();
   private static SelectionScreenProjection projection;
   private static Object levelIdentity;
   private SmartSelectionRenderer() { }

   static void beginFrame(RenderLevelStageEvent event, Minecraft minecraft) {
      if (levelIdentity != minecraft.level) { clear(); levelIdentity = minecraft.level; }
      projection = new SelectionScreenProjection(new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()),
         event.getCamera().getPosition());
      var workspace = ClientOperationController.workspace();
      CACHE.entrySet().removeIf(entry -> {
         if (workspace.part(entry.getKey()).filter(ClientSelectionPart::smart).isPresent()) return false;
         entry.getValue().close(); return true;
      });
   }

   static void render(RenderLevelStageEvent event, Minecraft minecraft, ClientSelectionPart part,
      Map<BlockPos, ClientBlockSnapshot> blocks, float alpha) {
      Entry entry = CACHE.get(part.id());
      if (entry == null || entry.blocks != blocks) {
         if (entry != null) entry.close();
         entry = new Entry(blocks, minecraft, part);
         CACHE.put(part.id(), entry);
      }
      var mesh = entry.shell.mesh(entry.preview, null, entry.states, CollisionContext.of(minecraft.player),
         entry.states.keySet(), entry.states.keySet(), Map.of(), minecraft.player.isShiftKeyDown());
      var capture = ClientOperationController.selectionGestures().capture();
      if (capture != null && capture.targets().containsKey(part.id())) {
         var buffers = minecraft.renderBuffers().bufferSource();
         for (var type : new net.minecraft.client.renderer.RenderType[] {
            PreviewRenderTypes.DYNAMIC_XRAY_LINES, PreviewRenderTypes.DYNAMIC_LINES}) {
            var vertices = buffers.getBuffer(type);
            io.github.fastformer.client.render.shell.ShapeShellRenderer.renderDynamicEdges(event.getPoseStack(), vertices,
               event.getCamera().getPosition(), mesh.edges(), alpha);
            io.github.fastformer.client.render.shell.ShapeShellRenderer.renderDynamicEdges(event.getPoseStack(), vertices,
               event.getCamera().getPosition(), entry.airOutline, alpha * 0.5F);
            buffers.endBatch(type);
         }
         return;
      }
      minecraft.renderBuffers().bufferSource().endBatch(PreviewRenderTypes.GHOST_OUTLINE_LINES);
      minecraft.renderBuffers().bufferSource().endBatch(PreviewRenderTypes.PENDING_XRAY_LINES);
      entry.edges.draw(event.getPoseStack(), event.getCamera().getPosition(), mesh.edges(),
         PreviewRenderTypes.PENDING_XRAY_LINES, alpha);
      entry.airEdges.draw(event.getPoseStack(), event.getCamera().getPosition(), entry.airOutline,
         PreviewRenderTypes.PENDING_XRAY_LINES, alpha * 0.5F);
      entry.edges.draw(event.getPoseStack(), event.getCamera().getPosition(), mesh.edges(), PreviewRenderTypes.GHOST_OUTLINE_LINES, alpha);
      entry.airEdges.draw(event.getPoseStack(), event.getCamera().getPosition(), entry.airOutline,
         PreviewRenderTypes.GHOST_OUTLINE_LINES, alpha * 0.5F);
   }

   static void hud(GuiGraphics graphics, Minecraft minecraft,
      io.github.fastformer.client.render.hud.BottomHudLayout bottomLayout) {
      if (projection == null || minecraft.level != levelIdentity || !ClientOperationController.active()) return;
      boolean alt = FastPlaceClientInput.modifierHeld();
      var workspace = ClientOperationController.workspace();
      Entry farthest = null;
      int farthestId = 0;
      boolean groupLocated = false;
      for (var part : workspace.parts()) {
         Entry entry = CACHE.get(part.id());
         if (!part.smart() || entry == null || !entry.topology.disconnected()) continue;
         boolean selected = workspace.selectedIds().contains(part.id());
         int color = GeometryPalette.text().argb(alt ? 55 : selected || part.smartEditable() ? 220 : 110);
         SelectionScreenProjection.Rect whole = null;
         int shown = 0;
         for (var piece : entry.topology.pieces()) {
            var rect = projection.bounds(piece, graphics.guiWidth(), graphics.guiHeight());
            if (rect == null) continue;
            whole = whole == null ? rect : whole.union(rect);
            if (!alt && shown++ < 24) graphics.drawString(minecraft.font, "#" + part.id(),
               (int)rect.left() + 3, (int)rect.top() + 3, color, true);
         }
         if (whole != null) {
            corners(graphics, whole, color);
            if (!alt) graphics.drawString(minecraft.font,
               Component.translatable("fastformer.hud.smart_group", part.id(), entry.topology.pieces().size()),
               (int)whole.left(), Math.max(4, (int)whole.top() - 12), color, true);
         }
         if (!alt && selected && !ClientOperationController.smartEditing()) {
            boolean group = ClientOperationController.interactionScene().groupGizmo() != null;
            if (!group || !groupLocated) locator(graphics, minecraft, part.id());
            groupLocated |= group;
         }
         if ((selected || part.smartEditable()) && entry.topology.distant()
            && (farthest == null || entry.topology.span() > farthest.topology.span())) { farthest = entry; farthestId = part.id(); }
      }
      if (farthest != null && !alt) bottomLayout.render(graphics, minecraft,
         Component.translatable("fastformer.hud.smart_distant", farthestId, (int)Math.ceil(farthest.topology.span()))
            .withStyle(GeometryPalette.accent().style()));
   }

   private static void locator(GuiGraphics graphics, Minecraft minecraft, int id) {
      var scene = ClientOperationController.interactionScene();
      var bounds = scene.groupGizmo() == null ? scene.bounds(id)
         : scene.groupGizmo().require(io.github.fastformer.client.interaction.InteractionComponents.WORLD_BOUNDS);
      if (bounds == null) return;
      Vec3 center = bounds.getCenter();
      var point = projection.point(center, graphics.guiWidth(), graphics.guiHeight());
      String arrow = point.onScreen() ? "◇" : direction(point, graphics.guiWidth(), graphics.guiHeight());
      int distance = (int)Math.ceil(projection.camera().distanceTo(center));
      Component text = scene.groupGizmo() == null ? Component.translatable("fastformer.hud.smart_gizmo", arrow, id, distance)
         : Component.translatable("fastformer.hud.smart_group_gizmo", arrow, distance);
      int x = (int)Math.clamp(point.x() - minecraft.font.width(text) * 0.5, 6, Math.max(6, graphics.guiWidth() - minecraft.font.width(text) - 6));
      graphics.drawString(minecraft.font, text, x, (int)point.y() + (point.onScreen() ? 16 : 0), GeometryPalette.text().argb(), true);
   }

   private static String direction(SelectionScreenProjection.Point point, int width, int height) {
      double x = point.x() - width * 0.5, y = point.y() - height * 0.5;
      if (Math.abs(x) > Math.abs(y)) return x < 0 ? "←" : "→";
      return y < 0 ? "↑" : "↓";
   }

   private static void corners(GuiGraphics graphics, SelectionScreenProjection.Rect rect, int color) {
      int left = (int)rect.left(), right = (int)rect.right(), top = (int)rect.top(), bottom = (int)rect.bottom();
      int length = Math.max(2, Math.min(12, Math.min(right - left, bottom - top) / 3));
      for (int x : new int[] {left, right}) for (int y : new int[] {top, bottom}) {
         int dx = x == left ? length : -length, dy = y == top ? length : -length;
         graphics.fill(Math.min(x, x + dx), y, Math.max(x, x + dx) + 1, y + 1, color);
         graphics.fill(x, Math.min(y, y + dy), x + 1, Math.max(y, y + dy) + 1, color);
      }
   }

   static void clear() { CACHE.values().forEach(Entry::close); CACHE.clear(); projection = null; }
   private static final class Entry {
      final Map<BlockPos, ClientBlockSnapshot> blocks;
      final Map<BlockPos, BlockState> states = new HashMap<>();
      final SmartSelectionTopology topology;
      final net.minecraft.world.level.BlockGetter preview;
      final BuildingShellCache shell = BuildingShellCache.forImmutableSnapshots(true,
         PreviewRenderResources.PREVIEW_MESH_EXECUTOR, failure -> org.slf4j.LoggerFactory.getLogger(SmartSelectionRenderer.class).warn("Smart selection mesh failed", failure));
      final BuildingShellEdgeBuffer edges = new BuildingShellEdgeBuffer();
      final BuildingShellEdgeBuffer airEdges = new BuildingShellEdgeBuffer();
      final java.util.List<ShapeShellMesh.StyledEdge> airOutline;
      Entry(Map<BlockPos, ClientBlockSnapshot> blocks, Minecraft minecraft, ClientSelectionPart part) {
         this.blocks = blocks;
         blocks.forEach((position, snapshot) -> states.put(position, snapshot.state()));
         airOutline = SmartSelectionAirOutline.build(blocks.entrySet().stream().filter(entry -> entry.getValue().state().isAir())
            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
         topology = part.smartEditable() ? ClientOperationController.workspace().smartTopology(part)
            : SmartSelectionTopology.of(blocks.keySet());
         preview = PreviewBlockOcclusion.level(minecraft.level, states);
      }
      void close() { shell.clear(); edges.clear(); airEdges.clear(); }
   }
}
