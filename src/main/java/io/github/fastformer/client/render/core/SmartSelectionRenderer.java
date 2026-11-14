package io.github.fastformer.client.render.core;

import io.github.fastformer.client.preview.hud.SelectionHudModel;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.SmartSelectionAirOutline;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.cache.BuildingShellCache;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.hud.SelectionScreenProjection;
import io.github.fastformer.client.render.hud.ScreenPixelHysteresis;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.fastplace.selection.SmartSelectionTopology;
import io.github.fastformer.common.world.BlockSnapshot;
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
   private static SelectionHudModel hudFrame = SelectionHudModel.EMPTY;
   private static final java.util.List<SelectionHudModel.Label> FRAME_LABELS = new java.util.ArrayList<>();
   private static final java.util.List<SelectionHudModel.Corner> FRAME_CORNERS = new java.util.ArrayList<>();

   static void beginVisualFrame() { hudFrame = SelectionHudModel.EMPTY; }

   static void drawHud(GuiGraphics graphics, net.minecraft.client.gui.Font font,
      io.github.fastformer.client.render.hud.BottomHudLayout bottom) {
      io.github.fastformer.client.render.hud.HudFrameRenderer.drawSelection(graphics, font, bottom, hudFrame);
   }
   private SmartSelectionRenderer() { }

   static void beginFrame(RenderLevelStageEvent event, Minecraft minecraft) {
      if (levelIdentity != minecraft.level) { clear(); levelIdentity = minecraft.level; }
      projection = new SelectionScreenProjection(new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix()),
         event.getCamera().getPosition());
      var workspace = ClientSessionSystem.instance().operations().workspace();
      CACHE.entrySet().removeIf(entry -> {
         if (workspace.part(entry.getKey()).filter(ClientSelectionPart::smart).isPresent()) return false;
         entry.getValue().close(); return true;
      });
   }

   static void render(RenderLevelStageEvent event, Minecraft minecraft, ClientSelectionPart part,
      Map<BlockPos, BlockSnapshot> blocks, float alpha) {
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
      var capture = ClientSessionSystem.instance().inputSession().selectionGestureSnapshot().capture();
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

   static void captureHud(Minecraft minecraft) {
      int width = minecraft.getWindow().getGuiScaledWidth();
      int height = minecraft.getWindow().getGuiScaledHeight();
      var bottomTexts = new java.util.ArrayList<Component>();
      FRAME_LABELS.clear();
      FRAME_CORNERS.clear();
      VISIBLE_LABELS.clear();
      VISIBLE_FRAMES.clear();
      if (projection == null || minecraft.level != levelIdentity || !ClientSessionSystem.instance().operations().active()) {
         LABELS.clear();
         FRAMES.clear();
         return;
      }
      boolean alt = FastPlaceClientInput.modifierHeld();
      var workspace = ClientSessionSystem.instance().operations().workspace();
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
            var rect = projection.bounds(piece, width, height);
            if (rect == null) continue;
            whole = whole == null ? rect : whole.union(rect);
            if (!alt && rect.intersects(width, height) && shown++ < 24) {
               worldLabel(minecraft, width, height, new LabelKey(part.id(), index), Component.literal("#" + part.id()),
                  piece, 3, color);
            }
         }
         if (whole != null) {
            corners(minecraft, width, height, part.id(), whole, color);
            if (!alt) {
               worldLabel(minecraft, width, height, new LabelKey(part.id(), -1),
                  Component.translatable("fastformer.hud.smart_group", part.id(), entry.topology.pieces().size()),
                  entry.topology.bounds(), -12, color);
            }
         }
         if (!alt && selected && !ClientSessionSystem.instance().operations().selectionEdits().smartEditing()) {
            boolean group = ClientSessionSystem.instance().operations().interactionScene().groupGizmo() != null;
            if (!group || !groupLocated) locator(minecraft, width, height, part.id());
            groupLocated |= group;
         }
         if ((selected || part.smartEditable()) && entry.topology.distant()
            && (farthest == null || entry.topology.span() > farthest.topology.span())) { farthest = entry; farthestId = part.id(); }
      }
      if (farthest != null && !alt) bottomTexts.add(
         Component.translatable("fastformer.hud.smart_distant", farthestId, (int)Math.ceil(farthest.topology.span()))
            .withStyle(GeometryPalette.accent().style()));
      LABELS.keySet().retainAll(VISIBLE_LABELS);
      FRAMES.keySet().retainAll(VISIBLE_FRAMES);
      hudFrame = new SelectionHudModel(FRAME_LABELS, FRAME_CORNERS, bottomTexts);
   }

   private static void locator(Minecraft minecraft, int width, int height, int id) {
      var scene = ClientSessionSystem.instance().operations().interactionScene();
      var bounds = scene.groupGizmo() == null ? scene.bounds(id)
         : scene.groupGizmo().require(io.github.fastformer.client.interaction.InteractionComponents.WORLD_BOUNDS);
      if (bounds == null) return;
      Vec3 center = bounds.getCenter();
      var point = projection.point(center, width, height);
      String arrow = point.onScreen() ? "◇" : direction(point, width, height);
      int distance = (int)Math.ceil(projection.camera().distanceTo(center));
      Component text = scene.groupGizmo() == null ? Component.translatable("fastformer.hud.smart_gizmo", arrow, id, distance)
         : Component.translatable("fastformer.hud.smart_group_gizmo", arrow, distance);
      label(minecraft, width, height, new LabelKey(scene.groupGizmo() == null ? id : -1, -2), text,
         point.x() - minecraft.font.width(text) * 0.5, point.y() + (point.onScreen() ? 16 : 0),
         GeometryPalette.text().argb());
   }

   private static void worldLabel(Minecraft minecraft, int width, int height, LabelKey key, Component text,
      net.minecraft.world.phys.AABB bounds, double offsetY, int color) {
      Vec3 center = bounds.getCenter();
      var point = projection.project(new Vec3(center.x, bounds.maxY, center.z), width, height);
      if (point == null || !point.onScreen()) return;
      label(minecraft, width, height, key, text, point.x() - minecraft.font.width(text) * 0.5, point.y() + offsetY, color);
   }

   private static void label(Minecraft minecraft, int width, int height, LabelKey key, Component text,
      double x, double y, int color) {
      VISIBLE_LABELS.add(key);
      var pixels = LABELS.computeIfAbsent(key, ignored -> new ScreenPixelHysteresis());
      var point = pixels.update(Math.clamp(x, 4, Math.max(4, width - minecraft.font.width(text) - 4)),
         Math.clamp(y, 4, Math.max(4, height - minecraft.font.lineHeight - 4)), minecraft.getWindow().getGuiScale());
      FRAME_LABELS.add(new SelectionHudModel.Label(text, point.x(), point.y(), color));
   }

   private static String direction(SelectionScreenProjection.Point point, int width, int height) {
      double x = point.x() - width * 0.5, y = point.y() - height * 0.5;
      if (Math.abs(x) > Math.abs(y)) return x < 0 ? "←" : "→";
      return y < 0 ? "↑" : "↓";
   }

   private static void corners(Minecraft minecraft, int width, int height, int id, SelectionScreenProjection.Rect rect, int color) {
      if (!rect.intersects(width, height)) return;
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
         if (px < -length || px > width + length || py < -length || py > height + length) continue;
         int dx = px == rect.left() ? physicalLength : -physicalLength;
         int dy = py == rect.top() ? physicalLength : -physicalLength;
         FRAME_CORNERS.add(new SelectionHudModel.Corner(px, py, dx, dy, thickness, scale, color));
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
      FRAME_LABELS.clear();
      FRAME_CORNERS.clear();
      hudFrame = SelectionHudModel.EMPTY;
   }

   private static final class FramePixels {
      final ScreenPixelHysteresis minimum = new ScreenPixelHysteresis();
      final ScreenPixelHysteresis maximum = new ScreenPixelHysteresis();
      final ScreenPixelHysteresis length = new ScreenPixelHysteresis();
   }

   private static final class Entry {
      final Map<BlockPos, BlockSnapshot> blocks;
      final Map<BlockPos, BlockState> states = new HashMap<>();
      final SmartSelectionTopology topology;
      final net.minecraft.world.level.BlockGetter preview;
      final BuildingShellCache shell = BuildingShellCache.forImmutableSnapshots(true,
         io.github.fastformer.client.lifecycle.ClientBackgroundTasks.serial("preview-mesh", 16), failure -> org.slf4j.LoggerFactory.getLogger(SmartSelectionRenderer.class).warn("Smart selection mesh failed", failure));
      final BuildingShellEdgeBuffer edges = new BuildingShellEdgeBuffer();
      final io.github.fastformer.client.render.cache.BuildingShellFaceBuffer faces = new io.github.fastformer.client.render.cache.BuildingShellFaceBuffer();
      final BuildingShellEdgeBuffer airEdges = new BuildingShellEdgeBuffer();
      final java.util.List<ShapeShellMesh.StyledEdge> airOutline;
      Entry(Map<BlockPos, BlockSnapshot> blocks, Minecraft minecraft, ClientSelectionPart part) {
         this.blocks = blocks;
         blocks.forEach((position, snapshot) -> states.put(position, snapshot.state()));
         airOutline = SmartSelectionAirOutline.build(blocks.entrySet().stream().filter(entry -> entry.getValue().state().isAir())
            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
         topology = part.smartEditable() ? ClientSessionSystem.instance().operations().workspace().smartTopology(part)
            : SmartSelectionTopology.of(blocks.keySet());
         preview = PreviewBlockOcclusion.level(minecraft.level, states);
      }
      void close() { shell.clear(); edges.clear(); airEdges.clear(); faces.clear(); }
   }
}
