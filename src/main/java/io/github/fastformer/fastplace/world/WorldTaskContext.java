package io.github.fastformer.fastplace.world;

import io.github.fastformer.fastplace.FastPlaceMessages;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Per-tick view of a world-owned task. The durable task stores only its owner
 * UUID and dimension; a ServerPlayer is looked up opportunistically for UI.
 */
public record WorldTaskContext(MinecraftServer server, UUID owner, Runnable resume) {
   public WorldTaskContext(MinecraftServer server, UUID owner) {
      this(server, owner, () -> {});
   }

   public WorldTaskContext withResume(Runnable resume) {
      return new WorldTaskContext(server, owner, resume);
   }

   /** Queues service rather than running it recursively inside an I/O completion. */
   public void enqueueResume() {
      if (server != null) {
         server.tell(new TickTask(server.getTickCount(), resume));
      }
   }

   public void resumeAfter(CompletableFuture<?> ready, BooleanSupplier stillWaiting) {
      if (server != null) {
         resumeAfter(ready, command -> server.tell(new TickTask(server.getTickCount(), command)), stillWaiting, resume);
      }
   }

   static void resumeAfter(
      CompletableFuture<?> ready, Executor mainThread, BooleanSupplier stillWaiting, Runnable resume
   ) {
      ready.whenComplete((ignored, failure) -> mainThread.execute(() -> {
         if (stillWaiting.getAsBoolean()) {
            resume.run();
         }
      }));
   }

   public ServerLevel level(ResourceKey<Level> dimension) {
      return this.server == null ? null : this.server.getLevel(dimension);
   }

   public ServerPlayer onlinePlayer() {
      return this.server == null ? null : this.server.getPlayerList().getPlayer(this.owner);
   }

   public void actionBar(Component message) {
      ServerPlayer player = this.onlinePlayer();
      if (player != null) {
         FastPlaceMessages.actionBar(player, message);
      } else {
         WorldTaskFeature.deferActionBar(this.owner, message);
      }
   }

   public void chat(Component message) {
      ServerPlayer player = this.onlinePlayer();
      if (player != null) {
         FastPlaceMessages.chat(player, message);
      } else {
         WorldTaskFeature.deferChat(this.owner, message);
      }
   }
}
