package io.github.fastformer.client.render.style;

import java.util.Objects;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.bus.api.Event;

/** Client render-thread event on NeoForge.EVENT_BUS. Replacements must keep the pass's vertex format. */
public final class ConfigureVisualShaderEvent extends Event {
   private final VisualStyleContext context;
   private ShaderInstance shader;

   public ConfigureVisualShaderEvent(VisualStyleContext context, ShaderInstance shader) {
      this.context = context;
      this.shader = Objects.requireNonNull(shader);
   }

   public VisualStyleContext context() { return context; }
   public ShaderInstance shader() { return shader; }
   public void setShader(ShaderInstance shader) { this.shader = Objects.requireNonNull(shader); }
}
