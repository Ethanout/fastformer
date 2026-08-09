package io.github.fastformer.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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

   private FastPlaceClientShaders() {
   }

   @SubscribeEvent
   public static void registerShaders(RegisterShadersEvent event) throws IOException {
      event.registerShader(
         new ShaderInstance(
            event.getResourceProvider(),
            ResourceLocation.fromNamespaceAndPath("fastformer", "pending_dashed_lines"),
            DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
         ),
         shader -> pendingDashedLines = shader
      );
   }

   @Nullable
   public static ShaderInstance pendingDashedLines() {
      return pendingDashedLines;
   }

   public static void setPendingDashOffset(float offset) {
      ShaderInstance shader = pendingDashedLines;
      if (shader != null && shader.getUniform("DashOffset") != null) {
         shader.getUniform("DashOffset").set(offset);
      }
   }
}
