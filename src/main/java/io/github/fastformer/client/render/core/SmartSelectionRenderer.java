package io.github.fastformer.client.render.core;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.SmartSelectionAirOutline;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.cache.BuildingShellCache;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.hud.SelectionScreenProjection;
import io.github.fastformer.client.render.hud.ScreenPixelHysteresis;
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
   private record LabelKey(int partId, int piece) { }
   private static final Map<LabelKey, ScreenPixelHysteresis> LABELS = new HashMap<>();
   private static final Map<Integer, FramePixels> FRAMES = new HashMap<>();
   private static final java.util.Set<LabelKey> VISIBLE_LABELS = new java.util.HashSet<>();
   private static final java.util.Set<Integer> VISIBLE_FRAMES = new java.util.HashSet<>();
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
      entry.faces.draw(event.getPoseStack(), event.getCamera().getPosition(), mesh.faces(),
         PreviewRenderTypes.SMART_SELECTION_SURFACE, alpha);
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
      VISIBLE_LABELS.clear();
      VISIBLE_FRAMES.clear();
      if (projection == null || minecraft.level != levelIdentity || !ClientOperationController.active()) {
         LABELS.clear();
         FRAMES.clear();
         return;
      }
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
         int pieceIndex = 0;
         for (var piece : entry.topology.pieces()) {
            int index = pieceIndex++;
            var rect = projection.bounds(piece, graphics.guiWidth(), graphics.guiHeight());
            if (rect == null) continue;
            whole = whole == null ? rect : whole.union(rect);
            if (!alt && rect.intersects(graphics.guiWidth(), graphics.guiHeight()) && shown++ < 24) {
               worldLabel(graphics, minecraft, new LabelKey(part.id(), index), Component.literal("#" + part.id()),
                  piece, 3, color);
            }
         }
         if (whole != null) {
            corners(graphics, minecraft, part.id(), whole, color);
            if (!alt) {
               worldLabel(graphics, minecraft, new LabelKey(part.id(), -1),
                  Component.translatable("fastformer.hud.smart_group", part.id(), entry.topology.pieces().size()),
                  entry.topology.bounds(), -12, color);
            }
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
      LABELS.keySet().retainAll(VISIBLE_LABELS);
      FRAMES.keySet().retainAll(VISIBLE_FRAMES);
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
      label(graphics, minecraft, new LabelKey(scene.groupGizmo() == null ? id : -1, -2), text,
         point.x() - minecraft.font.width(text) * 0.5, point.y() + (point.onScreen() ? 16 : 0),
         GeometryPalette.text().argb());
   }

   private static void worldLabel(GuiGraphics graphics, Minecraft minecraft, LabelKey key, Component text,
      net.minecraft.world.phys.AABB bounds, double offsetY, int color) {
      Vec3 center = bounds.getCenter();
      var point = projection.project(new Vec3(center.x, bounds.maxY, center.z), graphics.guiWidth(), graphics.guiHeight());
      if (point == null || !point.onScreen()) return;
      label(graphics, minecraft, key, text, point.x() - minecraft.font.width(text) * 0.5, point.y() + offsetY, color);
   }

   private static void label(GuiGraphics graphics, Minecraft minecraft, LabelKey key, Component text,
      double x, double y, int color) {
      VISIBLE_LABELS.add(key);
      var pixels = LABELS.computeIfAbsent(key, ignored -> new ScreenPixelHysteresis());
      var point = pixels.update(Math.clamp(x, 4, Math.max(4, graphics.guiWidth() - minecraft.font.width(text) - 4)),
         Math.clamp(y, 4, Math.max(4, graphics.guiHeight() - minecraft.font.lineHeight - 4)), minecraft.getWindow().getGuiScale());
      graphics.pose().pushPose();
      graphics.pose().translate(point.x(), point.y(), 0);
      graphics.drawString(minecraft.font, text, 0, 0, color, true);
      graphics.pose().popPose();
   }

   private static String direction(SelectionScreenProjection.Point point, int width, int height) {
      double x = point.x() - width * 0.5, y = point.y() - height * 0.5;
      if (Math.abs(x) > Math.abs(y)) return x < 0 ? "←" : "→";
      return y < 0 ? "↑" : "↓";
   }

   private static void corners(GuiGraphics graphics, Minecraft minecraft, int id, SelectionScreenProjection.Rect rect, int color) {
      if (!rect.intersects(graphics.guiWidth(), graphics.guiHeight())) return;
      VISIBLE_FRAMES.add(id);
      double scale = minecraft.getWindow().getGuiScale();
      var pixels = FRAMES.computeIfAbsent(id, ignored -> new FramePixels());
      var minimum = pixels.minimum.update(rect.left(), rect.top(), scale);
      var maximum = pixels.maximum.update(rect.right(), rect.bottom(), scale);
      rect = new SelectionScreenProjection.Rect(minimum.x(), minimum.y(), maximum.x(), maximum.y());
      double targetLength = Math.max(2, Math.min(12, Math.min(rect.right() - rect.left(), rect.bottom() - rect.top()) / 3));
      double length = pixels.length.update(targetLength, targetLength, scale).x();
      int physicalLength = (int)Math.round(length * scale);
      int thickness = Math.max(1, (int)Math.round(scale));
      for (double px : new double[] {rect.left(), rect.right()}) for (double py : new double[] {rect.top(), rect.bottom()}) {
         if (px < -length || px > graphics.guiWidth() + length || py < -length || py > graphics.guiHeight() + length) continue;
         int dx = px == rect.left() ? physicalLength : -physicalLength;
         int dy = py == rect.top() ? physicalLength : -physicalLength;
         graphics.pose().pushPose();
         graphics.pose().translate(px, py, 0);
         graphics.pose().scale((float)(1 / scale), (float)(1 / scale), 1);
         graphics.fill(Math.min(0, dx), 0, Math.max(0, dx) + thickness, thickness, color);
         graphics.fill(0, Math.min(0, dy), thickness, Math.max(0, dy) + thickness, color);
         graphics.pose().popPose();
      }
   }

   static void clear() {
      CACHE.values().forEach(Entry::close);
      CACHE.clear();
      LABELS.clear();
      FRAMES.clear();
      VISIBLE_LABELS.clear();
      VISIBLE_FRAMES.clear();
      projection = null;
   }

   private static final class FramePixels {
      final ScreenPixelHysteresis minimum = new ScreenPixelHysteresis();
      final ScreenPixelHysteresis maximum = new ScreenPixelHysteresis();
      final ScreenPixelHysteresis length = new ScreenPixelHysteresis();
   }

   private static final class Entry {
      final Map<BlockPos, ClientBlockSnapshot> blocks;
      final Map<BlockPos, BlockState> states = new HashMap<>();
      final SmartSelectionTopology topology;
      final net.minecraft.world.level.BlockGetter preview;
      final BuildingShellCache shell = BuildingShellCache.forImmutableSnapshots(true,
         PreviewRenderResources.PREVIEW_MESH_EXECUTOR, failure -> org.slf4j.LoggerFactory.getLogger(SmartSelectionRenderer.class).warn("Smart selection mesh failed", failure));
      final BuildingShellEdgeBuffer edges = new BuildingShellEdgeBuffer();
      final io.github.fastformer.client.render.cache.BuildingShellFaceBuffer faces = new io.github.fastformer.client.render.cache.BuildingShellFaceBuffer();
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
      void close() { shell.clear(); edges.clear(); airEdges.clear(); faces.clear(); }
   }
}
