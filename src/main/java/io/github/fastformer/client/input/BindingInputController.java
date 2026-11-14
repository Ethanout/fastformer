package io.github.fastformer.client.input;

import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.client.session.ClientSessionSystem;

import com.google.gson.*;
import io.github.fastformer.client.interaction.binding.*;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.common.interaction.binding.SessionProfiles;
import io.github.fastformer.fastplace.geometry.raycast.*;
import io.github.fastformer.fastplace.quickshape.QuickShapeCandidateContext;
import io.github.fastformer.protocol.c2s.placement.QuitFastPlacePayload;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Adapts data bindings to existing input commands, owning pending replay and acknowledgement cleanup. */
final class BindingInputController {
   private static final BindingCommands WORKSPACE_PROBE = new BindingCommands() {
      public boolean start(ResourceLocation profile) { return true; }
      public boolean point() { return true; }
      public boolean middleConfirm() { return true; }
      public boolean undoPoint() { return true; }
      public boolean cancel() { return true; }
      public boolean cycleMode() { return true; }
      public boolean tool(io.github.fastformer.client.interaction.binding.action.ToolCommand command) { return true; }
      public boolean workspace(io.github.fastformer.client.interaction.binding.action.WorkspaceCommand command) { return true; }
      public void replayPress(long time) { }
   };
   private BindingSystem system;
   private Object environment;
   private Object player;
   private ItemStack item;
   private ResourceLocation profile = SessionProfiles.NONE;
   private long startedAt;
   private boolean acknowledged;
   private Release release;
   private record Release(Vec3 eye, Vec3 view, BlockHitResult hit, long occurredAt) {}

   boolean onIdlePress(Minecraft minecraft, ClientInputSession owner, int button, long now, boolean control, IdlePressSnapshot press) {
      if (button != 1 || press.outline() == null) return false;
      BindingSystem bindings = system(minecraft, owner);
      boolean handled = bindings.onButton(button, "right", true, now, context(minecraft, true, false),
         commands(minecraft, owner, press, control, now));
      if (handled) {
         environment = minecraft.level;
         player = minecraft.player;
         item = minecraft.player.getMainHandItem().copy();
         owner.idleEntry.clear();
         minecraft.options.keyUse.setDown(false);
      }
      return handled;
   }

   boolean onButton(Minecraft minecraft, ClientInputSession owner, int action, int button, long now) {
      boolean wasActive = active();
      if (system != null && system.synchronizeDefinitions() && wasActive) return true;
      if (system == null || !system.active() && profile.equals(SessionProfiles.NONE)) return false;
      if (!intact(minecraft)) { cancel(minecraft, owner); return false; }
      if (action == 1 && button == 0) { cancel(minecraft, owner); return true; }
      if (action == 1 && button == 1) return true;
      if (button != 1 || action != 0) return false;
      boolean handled = system.onButton(button, "right", false, now, () -> context(minecraft, false, true),
         commands(minecraft, owner, null, false, now));
      owner.releaseQuickShapeButton(button);
      if (profile.equals(SessionProfiles.NONE) && !system.active()) clearCapturedContext();
      return handled;
   }

   boolean onKey(Minecraft minecraft, ClientInputSession owner, int key, int action, long now) {
      boolean wasActive = active();
      if (system != null && system.synchronizeDefinitions() && wasActive) return key == 81 && action == 1;
      if (action != 1 || key != 81 || !active()) return false;
      cancel(minecraft, owner);
      return true;
   }

   boolean onToolKey(Minecraft minecraft, ClientInputSession owner, Set<String> names, BindingContext context, long now) {
      return system(minecraft, owner).onKey(names, context, commands(minecraft, owner, null, false, now));
   }

   /** Probe uses the same rules without editing the workspace or emitting feedback. */
   boolean onWorkspaceKey(Minecraft minecraft, ClientInputSession owner, Set<String> names, BindingContext context, long now, boolean probe) {
      var result = system(minecraft, owner).onKeyResult(names, context, probe ? WORKSPACE_PROBE : commands(minecraft, owner, null, false, now));
      return probe ? result.consumesVanilla() : result.handled();
   }

   boolean onToolSignal(Minecraft minecraft, ClientInputSession owner, String name, String gesture, BindingContext context, long now) {
      return system(minecraft, owner).onSignal(name, gesture, context, commands(minecraft, owner, null, false, now));
   }

   io.github.fastformer.client.interaction.binding.action.BindingAction.Result onToolSignalResult(
      Minecraft minecraft, ClientInputSession owner, String name, String gesture, BindingContext context, long now) {
      return system(minecraft, owner).onSignalResult(name, gesture, context, commands(minecraft, owner, null, false, now));
   }

   io.github.fastformer.client.interaction.binding.action.BindingAction.Result onLineSignal(
      Minecraft minecraft, ClientInputSession owner, String name, String gesture, BindingContext context,
      BindingCommands commands) {
      return system(minecraft, owner).onSignalResult(name, gesture, context, commands);
   }

   void synchronizeLineDefinitions(Minecraft minecraft, ClientInputSession owner) {
      system(minecraft, owner).synchronizeDefinitions();
   }

   void tick(Minecraft minecraft, ClientInputSession owner, long now) {
      if (system == null) return;
      if (system.synchronizeDefinitions()) return;
      if (active() && !intact(minecraft)) { cancel(minecraft, owner); return; }
      system.tick(now);
      if (profile.equals(SessionProfiles.NONE)) return;
      ResourceLocation published = ClientSessionSystem.instance().previews().buildingProfile();
      if (profile.equals(published)) acknowledged = true;
      else if (acknowledged || now - startedAt > 5_000_000_000L) { cancel(minecraft, owner); return; }
      if (release != null && acknowledged) {
         var draft = ClientSessionSystem.instance().previews().buildingSubmission().orElse(null);
         if (draft == null) return;
         var data = draft.data();
         var geometry = new QuickShapeCandidateContext(data.points(), data.polygonClosed(), data.faceBaseOffset(),
            data.volumeBaseOffset(), data.perpendicularAnchor(), data.freeScrollOffset(), data.modes(), data.ctrlHeld());
         var candidate = geometry.resolve(release.hit(), release.eye(), release.view());
         var path = new io.github.fastformer.client.input.gesture.PathClosePress(false, false, candidate, false, release.occurredAt());
         owner.postQuickShapePointer(new io.github.fastformer.client.quickshape.input.QuickShapePointerPress(
            draft, path, release.eye(), release.view(), data.ctrlHeld(), true));
         release = null;
         reset();
         owner.idleEntry.clear();
      }
   }

   boolean active() { return system != null && system.active() || !profile.equals(SessionProfiles.NONE); }

   void reset() {
      if (system != null) system.reset();
      profile = SessionProfiles.NONE;
      release = null;
      acknowledged = false;
      startedAt = 0;
      clearCapturedContext();
   }

   private BindingSystem system(Minecraft minecraft, ClientInputSession owner) {
      if (system == null) {
         var configuration = BindingConfiguration.instance();
         system = configuration.createSystem(() -> {
            owner.cancelLineBindingInput();
            if (active()) cancel(minecraft, owner);
         });
      }
      return system;
   }

   private boolean intact(Minecraft minecraft) {
      return minecraft.player != null && minecraft.player == player && minecraft.level == environment
         && minecraft.screen == null && minecraft.isWindowActive() && minecraft.getConnection() != null
         && ItemStack.isSameItemSameComponents(item, minecraft.player.getMainHandItem());
   }

   private void clearCapturedContext() { environment = null; player = null; item = null; }

   void cancel(Minecraft minecraft, ClientInputSession owner) {
      boolean started = !profile.equals(SessionProfiles.NONE);
      reset();
      owner.idleEntry.clear();
      owner.releaseQuickShapeButton(1);
      minecraft.options.keyUse.setDown(false);
      if (started) {
         owner.cancel();
         if (minecraft.getConnection() != null && NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id()))
            PacketDistributor.sendToServer(QuitFastPlacePayload.INSTANCE);
      }
   }

   private BindingContext context(Minecraft minecraft, boolean idle, boolean heldRelease) {
      Map<String, JsonElement> facts = new LinkedHashMap<>();
      facts.put("session", new JsonPrimitive(idle ? "fastformer:idle" : "fastformer:quick_shape"));
      facts.put("profile", new JsonPrimitive(profile.toString()));
      facts.put("screen", new JsonPrimitive(minecraft.screen != null));
      facts.put("range", new JsonPrimitive("near"));
      facts.put("aiming", new JsonPrimitive("block"));
      facts.put("tinker_consumes", new JsonPrimitive(false));
      facts.put("double_second_press", new JsonPrimitive(false));
      facts.put("in_fluid", new JsonPrimitive(minecraft.player.isInWater()));
      facts.put("through_fluids", new JsonPrimitive(InteractionRaycastRules.passesThroughFluids(minecraft.player, false)));
      facts.put("sneaking", new JsonPrimitive(minecraft.player.isShiftKeyDown()));
      JsonArray items = new JsonArray();
      items.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(minecraft.player.getMainHandItem().getItem()).toString());
      minecraft.player.getMainHandItem().getTags().forEach(tag -> items.add("#" + tag.location()));
      if (io.github.fastformer.common.placement.context.PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())) items.add("#fastformer:placeable");
      facts.put("items", items);
      facts.put("modifiers", new JsonArray());
      JsonArray tags = new JsonArray();
      if (minecraft.hitResult instanceof BlockHitResult hit) minecraft.level.getBlockState(hit.getBlockPos()).getTags()
         .forEach(tag -> tags.add("#" + tag.location()));
      facts.put("aiming_tag", tags);
      return new BindingContext(facts);
   }

   private BindingCommands commands(Minecraft minecraft, ClientInputSession owner, IdlePressSnapshot press, boolean control, long now) {
      return new BindingCommands() {
         public boolean workspace(io.github.fastformer.client.interaction.binding.action.WorkspaceCommand command) {
            var operations = ClientSessionSystem.instance().operations();
            switch (command) {
               case COPY -> ClientInteractionFeedback.showCopyResult(minecraft, operations.clipboard().copy());
               case PASTE -> ClientInteractionFeedback.showPasteResult(minecraft, operations.clipboard().paste(minecraft));
               case TOGGLE_SELECT_ALL -> operations.toggleAllWorkspaceParts();
               case DUPLICATE -> ClientInteractionFeedback.showPasteResult(minecraft, operations.clipboard().duplicate());
               case ROTATE_SELECTION -> operations.transforms().transformSelected(minecraft, false);
               case FLIP_SELECTION -> operations.transforms().transformSelected(minecraft, true);
               case REPORT_SUBMISSION_PENDING -> ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
            }
            return true;
         }
         public boolean start(ResourceLocation requestedProfile) {
            if (press == null) return false;
            var constraints = SessionProfiles.find(minecraft.player.registryAccess(), requestedProfile).orElse(null);
            if (constraints == null) return false;
            boolean embedded = constraints.entry().anchor().equals("embedded");
            if (!QuickShapeStartController.capture(minecraft, owner, press.outline(), 0, 1, embedded,
               InteractionRaycastRules.VANILLA_OUTLINE.throughFluids(), press.eye(), press.view(), requestedProfile)) return false;
            profile = requestedProfile;
            startedAt = System.nanoTime();
            owner.idleEntry.clear();
            return true;
         }
         public boolean point() { return QuickShapeMouseInputController.capture(minecraft, owner, 1, 1, now, false); }
         public boolean middleConfirm() {
            if (profile.equals(SessionProfiles.NONE)) return QuickShapeMouseInputController.capture(minecraft, owner, 1, 2, now, false);
            Vec3 eye = minecraft.player.getEyePosition();
            Vec3 view = minecraft.player.getViewVector(1);
            release = new Release(eye, view, LongRangeBlockRaycast.clipForPlacement(minecraft.level, minecraft.player, eye, view).hit(), now);
            return true;
         }
         public boolean undoPoint() { return QuickShapeMouseInputController.captureUndo(minecraft, owner, 1, 0, now); }
         public boolean cancel() { BindingInputController.this.cancel(minecraft, owner); return true; }
         public boolean cycleMode() { return false; }
         public boolean tool(io.github.fastformer.client.interaction.binding.action.ToolCommand command) {
            switch (command) {
               case TOGGLE_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.toggle(minecraft);
               case BEGIN_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.beginStroke(minecraft);
               case APPLY_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.replace(minecraft);
               case END_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.finishStroke(minecraft);
               case HISTORY_UNDO, HISTORY_REDO -> {
                  io.github.fastformer.client.placement.QuickReplaceMode.finishStroke(minecraft);
                  minecraft.options.keyUse.setDown(false);
                  WorldHistoryCommandDispatcher.send(minecraft, command ==
                     io.github.fastformer.client.interaction.binding.action.ToolCommand.HISTORY_UNDO ? 90 : 89);
               }
               case START_FILL -> owner.smartFill().start(minecraft);
               case CONFIRM_FILL -> owner.smartFill().confirm(minecraft);
               case CAPTURE_SWAP_KEY -> {
                  while (minecraft.options.keySwapOffhand.consumeClick()) { }
                  return false;
               }
            }
            return true;
         }
         public void replayPress(long occurredAtNanos) {
            if (press == null || !intact(minecraft)) return;
            if (IdleSessionEntryController.replay(minecraft, owner, 1, occurredAtNanos, control, press)) return;
            HitResult previous = minecraft.hitResult;
            var entity = minecraft.crosshairPickEntity;
            try {
               minecraft.hitResult = press.outline();
               minecraft.crosshairPickEntity = null;
               io.github.fastformer.client.placement.ClientForcedPlacement.input(minecraft.player, 1);
               ((io.github.fastformer.client.mixin.MinecraftUseInvoker)(Object)minecraft).fastformer$startUseItem();
            } finally {
               io.github.fastformer.client.placement.ClientForcedPlacement.input(minecraft.player, 0);
               minecraft.hitResult = previous;
               minecraft.crosshairPickEntity = entity;
               minecraft.options.keyUse.setDown(false);
            }
         }
      };
   }
}
