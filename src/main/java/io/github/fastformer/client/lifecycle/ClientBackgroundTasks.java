package io.github.fastformer.client.lifecycle;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.async.IoExecutors;
import java.util.concurrent.ThreadPoolExecutor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Client process worker owner; world detach cancels requests without closing shared workers. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientBackgroundTasks {
   private static final IoExecutors WORKERS = new IoExecutors("fastformer-client",
      message -> LogUtils.getLogger().warn(message));

   private ClientBackgroundTasks() {}

   public static ThreadPoolExecutor serial(String name, int capacity) {
      return WORKERS.serial(name, capacity);
   }

   @SubscribeEvent
   public static void onShutdown(GameShuttingDownEvent event) { WORKERS.close(); }
}
