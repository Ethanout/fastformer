package io.github.fastformer.client.render.type;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import io.github.fastformer.client.render.PreviewStyle;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** Render pipelines used by client previews, outlines, and gizmos. */
public final class PreviewRenderTypes {
   private static final double PREVIEW_LINE_WIDTH = PreviewStyle.LINE_WIDTH;
   public static final RenderType PREVIEW_MODEL_TARGET = RenderType.create(
      "fastformer_preview_model_target", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS,
      2097152, false, false,
      RenderType.CompositeState.builder()
         .setShaderState(new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::previewModel))
         .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
         .setLightmapState(RenderStateShard.LIGHTMAP)
         // Alpha overlays (grass sides) must preserve the model layer below them.
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
         .createCompositeState(false)
   );
   /** Block meshes already contain directional shading; entity shaders would apply it twice. */
   public static final RenderType WORKSPACE_BLOCKS = RenderType.create(
      "fastformer_workspace_blocks", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS,
      2097152, false, true,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
         .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
         .setLightmapState(RenderStateShard.LIGHTMAP)
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .createCompositeState(false)
   );
   public static final RenderType PENDING_LINES = haloed("fastformer_pending_lines", lines(
      "fastformer_pending_lines_ink", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pencilLines), PREVIEW_LINE_WIDTH, RenderStateShard.LEQUAL_DEPTH_TEST
   ));
   public static final RenderType PENDING_DASHED_LINES = haloed("fastformer_pending_dashed_lines", dashedLines(
      "fastformer_pending_dashed_lines_ink", RenderStateShard.LEQUAL_DEPTH_TEST
   ));
   public static final RenderType PENDING_DASHED_XRAY_LINES = dashedLines(
      "fastformer_pending_dashed_xray_lines", RenderStateShard.GREATER_DEPTH_TEST
   );
   public static final RenderType GHOST_FACES = RenderType.create(
      "fastformer_ghost_faces",
      DefaultVertexFormat.POSITION_COLOR,
      VertexFormat.Mode.QUADS,
      1536,
      false,
      true,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
         .setLayeringState(RenderType.POLYGON_OFFSET_LAYERING)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   public static final RenderType SMART_SELECTION_CELLS = RenderType.create(
      "fastformer_smart_selection_cells", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS,
      1536, false, false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   public static final RenderType GHOST_OUTLINE_LINES = haloed("fastformer_ghost_outline_lines", lines(
      "fastformer_ghost_outline_lines_ink", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pencilLines), PREVIEW_LINE_WIDTH, RenderStateShard.LEQUAL_DEPTH_TEST,
      true
   ));
   public static final RenderType GIZMO_LINES = lines(
      "fastformer_gizmo_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pencilLines), PreviewStyle.GIZMO_LINE_WIDTH, RenderStateShard.NO_DEPTH_TEST
   );
   public static final RenderType GIZMO_HOVER_LINES = lines(
      "fastformer_gizmo_hover_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pencilLines), PreviewStyle.GIZMO_HOVER_WIDTH, RenderStateShard.NO_DEPTH_TEST
   );
   public static final RenderType SMART_SELECTION_TARGET_LINES = haloed("fastformer_smart_selection_target_lines", lines(
      "fastformer_smart_selection_target_lines_ink", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pencilLines), PREVIEW_LINE_WIDTH, RenderStateShard.LEQUAL_DEPTH_TEST
   ));
   public static final RenderType PENDING_XRAY_LINES = lines(
      "fastformer_pending_xray_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::occludedPencilLines), PREVIEW_LINE_WIDTH, RenderStateShard.GREATER_DEPTH_TEST
   );
   // Same pipeline as GHOST_OUTLINE_LINES; only the drawing sheet differs.
   public static final RenderType DYNAMIC_LINES = new HaloLineRenderType("fastformer_dynamic_lines", lines(
      "fastformer_dynamic_lines_ink", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::dynamicPencilLines), PREVIEW_LINE_WIDTH,
      RenderStateShard.LEQUAL_DEPTH_TEST, true), true);
   public static final RenderType DYNAMIC_XRAY_LINES = lines(
      "fastformer_dynamic_xray_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::occludedDynamicPencilLines), PREVIEW_LINE_WIDTH,
      RenderStateShard.GREATER_DEPTH_TEST);
   public static final RenderType OCCLUDED_CONTROL_POINTS = RenderType.create(
      "fastformer_occluded_control_points",
      DefaultVertexFormat.POSITION_COLOR,
      VertexFormat.Mode.TRIANGLE_STRIP,
      1536,
      false,
      true,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.GREATER_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   public static final RenderType GIZMO_SOLIDS = RenderType.create(
      "fastformer_gizmo_solids",
      DefaultVertexFormat.POSITION_COLOR,
      VertexFormat.Mode.QUADS,
      1536,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );

   private PreviewRenderTypes() {
   }

   /** Visible marks get a white halo; x-ray and gizmo lines stay bare. */
   private static RenderType haloed(String name, RenderType ink) {
      return new HaloLineRenderType(name, ink);
   }

   private static RenderType dashedLines(String name, RenderStateShard.DepthTestStateShard depthTest) {
      return lines(
         name, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
         new RenderStateShard.ShaderStateShard(depthTest == RenderStateShard.GREATER_DEPTH_TEST
            ? FastPlaceClientShaders::occludedPendingLines : FastPlaceClientShaders::pendingDashedLines),
         PREVIEW_LINE_WIDTH, depthTest
      );
   }

   private static RenderType lines(
      String name,
      VertexFormat format,
      RenderStateShard.ShaderStateShard shader,
      double width,
      RenderStateShard.DepthTestStateShard depthTest
   ) {
      return lines(name, format, shader, width, depthTest, false);
   }

   private static RenderType lines(
      String name,
      VertexFormat format,
      RenderStateShard.ShaderStateShard shader,
      double width,
      RenderStateShard.DepthTestStateShard depthTest,
      boolean polygonOffset
   ) {
      RenderType.CompositeState.CompositeStateBuilder state = RenderType.CompositeState.builder()
         .setShaderState(shader)
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(width)) {
            @Override
            public void setupRenderState() {
               String key = width == PreviewStyle.GIZMO_HOVER_WIDTH ? "gizmo_hover_width"
                  : width == PreviewStyle.GIZMO_LINE_WIDTH ? "gizmo_width" : "line_width";
               com.mojang.blaze3d.systems.RenderSystem.lineWidth(Math.max(0.5F,
                  io.github.fastformer.client.render.theme.VisualThemes.value(key, (float)width))
                  * io.github.fastformer.client.render.style.VisualStyleContext.guiScaleFactor());
            }
         })
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(depthTest)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL);
      if (polygonOffset) {
         state.setLayeringState(RenderType.POLYGON_OFFSET_LAYERING);
      }
      return RenderType.create(name, format, VertexFormat.Mode.LINES, 1536, false, false, state.createCompositeState(false));
   }
}
