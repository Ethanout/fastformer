package io.github.fastformer.client.render.type;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** Render pipelines used by client previews, outlines, and gizmos. */
public final class PreviewRenderTypes {
   public static final RenderType PENDING_LINES = lines(
      "fastformer_pending_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      RenderStateShard.RENDERTYPE_LINES_SHADER, 1.0, RenderStateShard.LEQUAL_DEPTH_TEST
   );
   public static final RenderType PENDING_DASHED_LINES = dashedLines(
      "fastformer_pending_dashed_lines", RenderStateShard.LEQUAL_DEPTH_TEST
   );
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
   public static final RenderType GHOST_OUTLINE_LINES = lines(
      "fastformer_ghost_outline_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      RenderStateShard.RENDERTYPE_LINES_SHADER, 1.0, RenderStateShard.LEQUAL_DEPTH_TEST,
      true
   );
   public static final RenderType GIZMO_LINES = lines(
      "fastformer_gizmo_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      RenderStateShard.RENDERTYPE_LINES_SHADER, 4.0, RenderStateShard.NO_DEPTH_TEST
   );
   public static final RenderType GIZMO_HOVER_LINES = lines(
      "fastformer_gizmo_hover_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      RenderStateShard.RENDERTYPE_LINES_SHADER, 4.0, RenderStateShard.NO_DEPTH_TEST
   );
   public static final RenderType PENDING_XRAY_LINES = lines(
      "fastformer_pending_xray_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
      RenderStateShard.RENDERTYPE_LINES_SHADER, 1.0, RenderStateShard.GREATER_DEPTH_TEST
   );
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

   private static RenderType dashedLines(String name, RenderStateShard.DepthTestStateShard depthTest) {
      return lines(
         name, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
         new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pendingDashedLines),
         1.0, depthTest
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
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(width)))
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
