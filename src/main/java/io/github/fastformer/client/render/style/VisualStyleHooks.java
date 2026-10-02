package io.github.fastformer.client.render.style;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.common.NeoForge;

/** Extension entry points shared by built-in styles and client add-ons. */
public final class VisualStyleHooks {
   private VisualStyleHooks() { }

   public static ShaderInstance shader(ShaderInstance shader, VisualStyleContext.Pass pass, boolean dynamic) {
      if (shader == null) return null;
      VisualStyleContext context = VisualStyleContext.capture(pass, dynamic);
      applyContext(shader, context);
      ConfigureVisualShaderEvent event = new ConfigureVisualShaderEvent(context, shader);
      NeoForge.EVENT_BUS.post(event);
      if (event.shader() != shader) applyContext(event.shader(), context);
      return event.shader();
   }

   private static void applyContext(ShaderInstance shader, VisualStyleContext context) {
      shader.safeGetUniform("FFTime").set((float)(context.timeSeconds() % 3600));
      shader.safeGetUniform("FFDynamic").set(context.dynamic() ? 1F : 0F);
      shader.safeGetUniform("FFOccluded").set(context.pass() == VisualStyleContext.Pass.OCCLUDED_LINE ? 1F : 0F);
      shader.safeGetUniform("FFGuiScale").set(context.guiScale());
      shader.safeGetUniform("FFViewport").set((float)context.framebufferWidth(), (float)context.framebufferHeight());
   }

   /** Call after an add-on changes geometry rules. Shader-only animation does not need a rebuild. */
   public static void invalidateGeometry() {
      Minecraft.getInstance().execute(io.github.fastformer.client.render.core.FastPlaceClientPreviewCore::onThemeChanged);
   }
}
