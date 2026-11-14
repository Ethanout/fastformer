package io.github.fastformer.client.preview.selection;

import io.github.fastformer.client.preview.hud.SelectionHudModel;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.SmartSelectionAirOutline;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.cache.BuildingShellCache;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.hud.SelectionScreenProjection;
import io.github.fastformer.client.render.hud.ScreenPixelHysteresis;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.common.selection.smart.SmartSelectionTopology;
import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import java.util.HashMap;
import java.util.Map;
import io.github.fastformer.client.preview.frame.PreviewDrawContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import org.joml.Matrix4f;
import io.github.fastformer.client.interaction.InteractionComponents;
import io.github.fastformer.client.lifecycle.ClientBackgroundTasks;
import io.github.fastformer.client.render.cache.BuildingShellFaceBuffer;
import io.github.fastformer.client.render.hud.BottomHudLayout;
import io.github.fastformer.client.render.hud.HudFrameRenderer;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import org.slf4j.LoggerFactory;

/** Smart membership outlines and screen-space ownership marks share one immutable snapshot. */
final class SmartSelectionRenderer {
   private static final Map<Integer, Entry> CACHE = new HashMap<>();
   private record LabelKey(int partId, int piece) { }
   private static final Map<LabelKey, ScreenPixelHysteresis> LABELS = new HashMap<>();
   private static final Map<Integer, FramePixels> FRAMES = new HashMap<>();
   private static final Set<LabelKey> VISIBLE_LABELS = new HashSet<>();
   private static final Set<Integer> VISIBLE_FRAMES = new HashSet<>();
   private static SelectionScreenProjection projection;
   private static Object levelIdentity;
   private static SelectionHudModel hudFrame = SelectionHudModel.EMPTY;
   private static final List<SelectionHudModel.Label> FRAME_LABELS = new ArrayList<>();
   private static final List<SelectionHudModel.Corner> FRAME_CORNERS = new ArrayList<>();

   static void beginVisualFrame() { hudFrame = SelectionHudModel.EMPTY; }

   static void drawHud(GuiGraphics graphics, Font font,
      BottomHudLayout bottom) {
      HudFrameRenderer.drawSelection(graphics, font, bottom, hudFrame);
   }
   private SmartSelectionRenderer() { }

   static void beginFrame(WorldPreviewFrame frame, PreviewDrawContext graphics, SelectionPreviewModel model) {
      if (levelIdentity != graphics.level()) { clear(); levelIdentity = graphics.level(); }
      projection = new SelectionScreenProjection(new Matrix4f(frame.projection()).mul(frame.modelView()),
         frame.camera());
      var workspace = model.workspace();
      CACHE.entrySet().removeIf(entry -> {
         if (workspace.parts().stream().anyMatch(part -> part.id() == entry.getKey() && part.smart())) return false;
         entry.getValue().close(); return true;
      });
   }

   static void render(WorldPreviewFrame frame, PreviewDrawContext graphics, ClientSelectionPart part,
      Map<BlockPos, BlockSnapshot> blocks, float alpha, SelectionPreviewModel model) {
      Entry entry = CACHE.get(part.id());
      if (entry == null || entry.blocks != blocks) {
         if (entry != null) entry.close();
         entry = new Entry(blocks, graphics, model.workspace().topologies().get(part.id()));
         CACHE.put(part.id(), entry);
      }
      var mesh = entry.shell.mesh(entry.preview, null, entry.states, graphics.collision(),
         entry.states.keySet(), entry.states.keySet(), Map.of(), model.modifiers().sneaking());
      entry.faces.draw(frame.pose(), frame.camera(), mesh.faces(),
         PreviewRenderTypes.SMART_SELECTION_SURFACE, alpha);
      if (model.workspace().movingIds().contains(part.id())) {
         var buffers = frame.buffers();
         for (var type : new RenderType[] {
            PreviewRenderTypes.DYNAMIC_XRAY_LINES, PreviewRenderTypes.DYNAMIC_LINES}) {
            var vertices = buffers.getBuffer(type);
            ShapeShellRenderer.renderDynamicEdges(frame.pose(), vertices,
               frame.camera(), mesh.edges(), alpha);
            ShapeShellRenderer.renderDynamicEdges(frame.pose(), vertices,
               frame.camera(), entry.airOutline, alpha * 0.5F);
            buffers.endBatch(type);
         }
         return;
      }
      frame.buffers().endBatch(PreviewRenderTypes.GHOST_OUTLINE_LINES);
      frame.buffers().endBatch(PreviewRenderTypes.PENDING_XRAY_LINES);
      entry.edges.draw(frame.pose(), frame.camera(), mesh.edges(),
         PreviewRenderTypes.PENDING_XRAY_LINES, alpha);
      entry.airEdges.draw(frame.pose(), frame.camera(), entry.airOutline,
         PreviewRenderTypes.PENDING_XRAY_LINES, alpha * 0.5F);
      entry.edges.draw(frame.pose(), frame.camera(), mesh.edges(), PreviewRenderTypes.GHOST_OUTLINE_LINES, alpha);
      entry.airEdges.draw(frame.pose(), frame.camera(), entry.airOutline,
         PreviewRenderTypes.GHOST_OUTLINE_LINES, alpha * 0.5F);
   }

   static void captureHud(PreviewDrawContext graphics, SelectionPreviewModel model) {
      int width = graphics.guiWidth();
      int height = graphics.guiHeight();
      var bottomTexts = new ArrayList<Component>();
      FRAME_LABELS.clear();
      FRAME_CORNERS.clear();
      VISIBLE_LABELS.clear();
      VISIBLE_FRAMES.clear();
      if (projection == null || graphics.level() != levelIdentity || !model.input().workspace()) {
         LABELS.clear();
         FRAMES.clear();
         return;
      }
      boolean alt = model.modifiers().modifier();
      var workspace = model.workspace();
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
               worldLabel(graphics, width, height, new LabelKey(part.id(), index), Component.literal("#" + part.id()),
                  piece, 3, color);
            }
         }
         if (whole != null) {
            corners(graphics, width, height, part.id(), whole, color);
            if (!alt) {
               worldLabel(graphics, width, height, new LabelKey(part.id(), -1),
                  Component.translatable("fastformer.hud.smart_group", part.id(), entry.topology.pieces().size()),
                  entry.topology.bounds(), -12, color);
            }
         }
         if (!alt && selected && !model.workspace().smartEditing()) {
            boolean group = model.workspace().scene().groupGizmo() != null;
            if (!group || !groupLocated) locator(graphics, width, height, part.id(), model);
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

   private static void locator(PreviewDrawContext graphics, int width, int height, int id, SelectionPreviewModel model) {
      var scene = model.workspace().scene();
      var bounds = scene.groupGizmo() == null ? scene.bounds(id)
         : scene.groupGizmo().require(InteractionComponents.WORLD_BOUNDS);
      if (bounds == null) return;
      Vec3 center = bounds.getCenter();
      var point = projection.point(center, width, height);
      String arrow = point.onScreen() ? "◇" : direction(point, width, height);
      int distance = (int)Math.ceil(projection.camera().distanceTo(center));
      Component text = scene.groupGizmo() == null ? Component.translatable("fastformer.hud.smart_gizmo", arrow, id, distance)
         : Component.translatable("fastformer.hud.smart_group_gizmo", arrow, distance);
      label(graphics, width, height, new LabelKey(scene.groupGizmo() == null ? id : -1, -2), text,
         point.x() - graphics.font().width(text) * 0.5, point.y() + (point.onScreen() ? 16 : 0),
         GeometryPalette.text().argb());
   }

   private static void worldLabel(PreviewDrawContext graphics, int width, int height, LabelKey key, Component text,
      AABB bounds, double offsetY, int color) {
      Vec3 center = bounds.getCenter();
      var point = projection.project(new Vec3(center.x, bounds.maxY, center.z), width, height);
      if (point == null || !point.onScreen()) return;
      label(graphics, width, height, key, text, point.x() - graphics.font().width(text) * 0.5, point.y() + offsetY, color);
   }

   private static void label(PreviewDrawContext graphics, int width, int height, LabelKey key, Component text,
      double x, double y, int color) {
      VISIBLE_LABELS.add(key);
      var pixels = LABELS.computeIfAbsent(key, ignored -> new ScreenPixelHysteresis());
      var point = pixels.update(Math.clamp(x, 4, Math.max(4, width - graphics.font().width(text) - 4)),
         Math.clamp(y, 4, Math.max(4, height - graphics.font().lineHeight - 4)), graphics.guiScale());
      FRAME_LABELS.add(new SelectionHudModel.Label(text, point.x(), point.y(), color));
   }

   private static String direction(SelectionScreenProjection.Point point, int width, int height) {
      double x = point.x() - width * 0.5, y = point.y() - height * 0.5;
      if (Math.abs(x) > Math.abs(y)) return x < 0 ? "←" : "→";
      return y < 0 ? "↑" : "↓";
   }

   private static void corners(PreviewDrawContext graphics, int width, int height, int id, SelectionScreenProjection.Rect rect, int color) {
      if (!rect.intersects(width, height)) return;
      VISIBLE_FRAMES.add(id);
      double scale = graphics.guiScale();
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
      final BlockGetter preview;
      final BuildingShellCache shell = BuildingShellCache.forImmutableSnapshots(true,
         ClientBackgroundTasks.serial("preview-mesh", 16), failure -> LoggerFactory.getLogger(SmartSelectionRenderer.class).warn("Smart selection mesh failed", failure));
      final BuildingShellEdgeBuffer edges = new BuildingShellEdgeBuffer();
      final BuildingShellFaceBuffer faces = new BuildingShellFaceBuffer();
      final BuildingShellEdgeBuffer airEdges = new BuildingShellEdgeBuffer();
      final List<ShapeShellMesh.StyledEdge> airOutline;
      Entry(Map<BlockPos, BlockSnapshot> blocks, PreviewDrawContext graphics, SmartSelectionTopology capturedTopology) {
         this.blocks = blocks;
         blocks.forEach((position, snapshot) -> states.put(position, snapshot.state()));
         airOutline = SmartSelectionAirOutline.build(blocks.entrySet().stream().filter(entry -> entry.getValue().state().isAir())
            .map(Map.Entry::getKey).collect(Collectors.toSet()));
         topology = capturedTopology != null ? capturedTopology : SmartSelectionTopology.of(blocks.keySet());
         preview = PreviewBlockOcclusion.level(graphics.level(), states);
      }
      void close() { shell.clear(); edges.clear(); airEdges.clear(); faces.clear(); }
   }
}
