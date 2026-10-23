package io.github.fastformer.client.input;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.network.payload.placement.SmartFillCommandPayload;
import io.github.fastformer.network.payload.preview.SmartFillPreviewPayload;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Input and preview data belong to the player environment that requested the fill. */
public final class ClientSmartFillSession {
   private sealed interface Phase permits Waiting, Preview, Confirming { UUID id(); long started(); }
   private record Waiting(UUID id, long started) implements Phase { }
   private record Preview(UUID id, long started, Map<BlockPos, ClientBlockSnapshot> blocks) implements Phase { }
   private record Confirming(UUID id, long started) implements Phase { }
   private Phase phase;

   public boolean active() { return phase != null; }
   public boolean ready() { return phase instanceof Preview; }
   public Map<BlockPos, ClientBlockSnapshot> blocks() { return phase instanceof Preview preview ? preview.blocks() : Map.of(); }
   public String statusKey() {
      return phase instanceof Preview ? "fastformer.fill.preview" : phase instanceof Confirming
         ? "fastformer.fill.submitting" : "fastformer.fill.searching";
   }

   boolean canStart(Minecraft minecraft) {
      return minecraft.player != null && minecraft.screen == null && minecraft.player.isCreative()
         && FastPlaceClientPreview.enabled() && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), SmartFillCommandPayload.TYPE.id())
         && NetworkRegistry.hasChannel(minecraft.getConnection(), SmartFillPreviewPayload.TYPE.id());
   }

   boolean handleKey(Minecraft minecraft, KeyboardInputSnapshot input, boolean idle) {
      if (minecraft.screen != null || minecraft.player == null) return false;
      boolean fillKey = !input.controlDown() && minecraft.options.keySwapOffhand.matches(input.key(), input.scanCode());
      boolean enter = input.key() == 257 || input.key() == 335;
      if (active()) {
         if ((fillKey || enter) && input.action() == 1 && ready()) {
            UUID id = phase.id();
            phase = new Confirming(id, System.nanoTime());
            send(minecraft, id, SmartFillCommandPayload.Action.CONFIRM);
         }
         return fillKey || enter;
      }
      if (!idle || !fillKey || !canStart(minecraft)) return false;
      if (input.action() == 1) {
         start(minecraft);
      }
      return true;
   }

   void start(Minecraft minecraft) {
      if (active()) return;
      UUID id = UUID.randomUUID();
      phase = new Waiting(id, System.nanoTime());
      send(minecraft, id, SmartFillCommandPayload.Action.DISCOVER);
   }

   void receive(SmartFillPreviewPayload payload) {
      if (phase == null || !phase.id().equals(payload.id())) return;
      if (payload.targets().isEmpty()) { clear(); return; }
      if (!(phase instanceof Waiting)) return;
      Map<BlockPos, ClientBlockSnapshot> blocks = new java.util.LinkedHashMap<>();
      var snapshot = new ClientBlockSnapshot(payload.state(), null);
      for (BlockPos position : payload.targets()) blocks.put(position, snapshot);
      phase = new Preview(payload.id(), System.nanoTime(), Map.copyOf(blocks));
   }

   void tick(Minecraft minecraft) {
      if (!active()) return;
      if (!minecraft.player.isCreative() || !FastPlaceClientPreview.enabled()
         || !(phase instanceof Preview) && System.nanoTime() - phase.started() > 10_000_000_000L) {
         cancel(minecraft);
      }
   }

   void cancel(Minecraft minecraft) {
      if (!active()) return;
      UUID id = phase.id();
      clear();
      send(minecraft, id, SmartFillCommandPayload.Action.CANCEL);
   }

   void clear() { phase = null; }

   private static void send(Minecraft minecraft, UUID id, SmartFillCommandPayload.Action action) {
      if (minecraft.getConnection() == null || !NetworkRegistry.hasChannel(minecraft.getConnection(), SmartFillCommandPayload.TYPE.id())) return;
      Vec3 eye = minecraft.player == null ? Vec3.ZERO : minecraft.player.getEyePosition();
      Vec3 view = minecraft.player == null ? new Vec3(0, 0, 1) : minecraft.player.getViewVector(1);
      if (minecraft.level != null) PacketDistributor.sendToServer(new SmartFillCommandPayload(
         id, minecraft.level.dimension().location(), action, eye, view));
   }
}
