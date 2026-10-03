package io.github.fastformer.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.github.fastformer.client.render.theme.VisualThemes;
import io.github.fastformer.client.render.style.VisualStyleContext;
import io.github.fastformer.client.render.style.VisualStyleHooks;
import java.io.IOException;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class FastPlaceClientShaders {
   private static ShaderInstance pendingDashedLines;
   private static ShaderInstance haloLines;
   private static ShaderInstance haloDashedLines;
   private static ShaderInstance smartSelectionComposite;
   private static ShaderInstance pencilLines;
   private static ShaderInstance previewMaterial;
   private static ShaderInstance previewModel;

   private FastPlaceClientShaders() {
   }

   @SubscribeEvent
   public static void registerShaders(RegisterShadersEvent event) throws IOException {
      event.registerShader(new ShaderInstance(event.getResourceProvider(),
         ResourceLocation.fromNamespaceAndPath("fastformer", "preview_model"),
         DefaultVertexFormat.BLOCK), shader -> previewModel = shader);
      event.registerShader(new ShaderInstance(event.getResourceProvider(),
         ResourceLocation.fromNamespaceAndPath("fastformer", "pencil_lines"),
         DefaultVertexFormat.POSITION_COLOR_NORMAL), shader -> pencilLines = shader);
      event.registerShader(new ShaderInstance(event.getResourceProvider(),
         ResourceLocation.fromNamespaceAndPath("fastformer", "preview_material"),
         DefaultVertexFormat.POSITION_TEX), shader -> previewMaterial = shader);
      event.registerShader(
         new ShaderInstance(
            event.getResourceProvider(),
            ResourceLocation.fromNamespaceAndPath("fastformer", "pending_dashed_lines"),
            DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
         ),
         shader -> pendingDashedLines = shader
      );
      event.registerShader(new ShaderInstance(event.getResourceProvider(),
         ResourceLocation.fromNamespaceAndPath("fastformer", "halo_lines"),
         DefaultVertexFormat.POSITION_COLOR_NORMAL), shader -> haloLines = shader);
      event.registerShader(new ShaderInstance(event.getResourceProvider(),
         ResourceLocation.fromNamespaceAndPath("fastformer", "halo_dashed_lines"),
         DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), shader -> haloDashedLines = shader);
      event.registerShader(new ShaderInstance(event.getResourceProvider(),
         ResourceLocation.fromNamespaceAndPath("fastformer", "smart_selection_composite"),
         DefaultVertexFormat.POSITION_TEX), shader -> smartSelectionComposite = shader);
   }

   @Nullable
   public static ShaderInstance pendingDashedLines() {
      return styledLines(pendingDashedLines, true, false);
   }

   public static ShaderInstance pencilLines() {
      if (pencilLines == null) return net.minecraft.client.renderer.GameRenderer.getRendertypeLinesShader();
      return styledLines(pencilLines, false, false);
   }

   public static ShaderInstance occludedPencilLines() {
      return styledLines(pencilLines, false, true);
   }

   /** Dynamic marks use the static pencil pipeline on the current drawing sheet. */
   public static ShaderInstance dynamicPencilLines() {
      if (pencilLines == null) return net.minecraft.client.renderer.GameRenderer.getRendertypeLinesShader();
      return styledLines(pencilLines, true, false);
   }

   public static ShaderInstance occludedDynamicPencilLines() {
      return styledLines(pencilLines, true, true);
   }

   @Nullable
   public static ShaderInstance occludedPendingLines() {
      return styledLines(pendingDashedLines, true, true);
   }

   private static ShaderInstance styledLines(@Nullable ShaderInstance shader, boolean dynamic, boolean occluded) {
      configurePencil(shader, dynamic);
      if (occluded) configureOccludedDashes(shader);
      return VisualStyleHooks.shader(shader,
         occluded ? VisualStyleContext.Pass.OCCLUDED_LINE : VisualStyleContext.Pass.LINE, dynamic);
   }

   private static void configureOccludedDashes(@Nullable ShaderInstance shader) {
      if (shader == null) return;
      boolean humanist = io.github.fastformer.fastplace.geometry.GeometryPalette.humanist();
      shader.safeGetUniform("OccludedOpacity").set(Math.clamp(
         VisualThemes.value("occluded_line_opacity", humanist ? 0.3F : 0F), 0F, 1F));
      shader.safeGetUniform("OccludedDash").set(VisualThemes.value("occluded_dash_length", humanist ? 0.25F : 0F),
         VisualThemes.value("occluded_dash_gap", humanist ? 0.15F : 0F));
   }

   private static void configurePencil(@Nullable ShaderInstance shader, boolean dynamic) {
      if (shader == null) return;
      shader.safeGetUniform("OccludedDash").set(0F, 0F);
      shader.safeGetUniform("OccludedOpacity").set(1F);
      shader.safeGetUniform("PencilGrain").set(VisualThemes.value("pencil_grain", 0));
      shader.safeGetUniform("PencilNearWidth").set(VisualThemes.value("pencil_near_width", 1.35F));
      var curve = VisualThemes.curve("line_width_by_distance").points();
      shader.safeGetUniform("WidthCurveCount").set(curve.size());
      for (int i = 0; i < curve.size(); i++) {
         var point = curve.get(i);
         shader.safeGetUniform("WidthCurve" + i).set(point.distance(), point.value(), point.tangent(), 0F);
      }
      float frame = io.github.fastformer.client.render.geometry.BoilClock.sheet(dynamic);
      shader.safeGetUniform("BoilFrame").set(frame);
      var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
      shader.safeGetUniform("CameraRotation").set(new org.joml.Matrix4f().rotation(camera.rotation()));
      var position = camera.getPosition();
      shader.safeGetUniform("CameraPosition").set((float)position.x, (float)position.y, (float)position.z);
   }

   @Nullable
   public static ShaderInstance previewMaterial() { return previewMaterial; }

   @Nullable
   public static ShaderInstance previewModel() {
      return VisualStyleHooks.shader(previewModel, VisualStyleContext.Pass.MODEL, false);
   }

   /** White halo under solid pencil lines. */
   @Nullable
   public static ShaderInstance haloLines() {
      return haloLines(false);
   }

   public static ShaderInstance haloLines(boolean dynamic) {
      configurePencil(haloLines, dynamic);
      return VisualStyleHooks.shader(haloLines, VisualStyleContext.Pass.HALO, dynamic);
   }

   /** White halo under dashed pencil lines; shares the dash uniforms. */
   @Nullable
   public static ShaderInstance haloDashedLines() {
      configurePencil(haloDashedLines, true);
      return VisualStyleHooks.shader(haloDashedLines, VisualStyleContext.Pass.HALO, true);
   }

   @Nullable
   public static ShaderInstance smartSelectionComposite() {
      return smartSelectionComposite;
   }

   public static void setPendingDashOffset(float offset) {
      setDashUniforms(pendingDashedLines, offset);
      setDashUniforms(haloDashedLines, offset);
   }

   private static void setDashUniforms(@Nullable ShaderInstance shader, float offset) {
      if (shader != null && shader.getUniform("DashOffset") != null) {
         shader.getUniform("DashOffset").set(offset);
         if (shader.getUniform("DashLength") != null) shader.getUniform("DashLength").set((float)PreviewStyle.DASH_LENGTH);
         if (shader.getUniform("DashGapAlpha") != null) shader.getUniform("DashGapAlpha").set(PreviewStyle.DASH_GAP_ALPHA);
      }
   }
}
