package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.ClientBlockTinker;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.entry.IdleCommand;
import io.github.fastformer.client.interaction.binding.action.entry.IdleEntryParameters;
import io.github.fastformer.client.interaction.binding.context.IdleBindingContext;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.IdleBindingContext.Fact;
import io.github.fastformer.client.interaction.binding.input.MouseButtons;
import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.common.interaction.binding.DoubleClickPolicy;
import io.github.fastformer.common.interaction.binding.SessionProfiles;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.protocol.c2s.placement.IdleInteractionCapturePayload;
import io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload;
import java.util.EnumSet;
import java.util.Map;
import java.util.HashMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Captures idle facts once; the resource tree chooses every entry operation and its precedence. */
final class IdleSessionEntryController {
   record Result(boolean owns, boolean consumesVanilla) {}
   private record Snapshot(IdlePressSnapshot press, IdleEntryGesture.Match anchor, DoubleClickPolicy policy,
      BindingContext context) {}
   private static long nextToken;
   private IdleSessionEntryController() {}

   static Result captureBindings(Minecraft minecraft, ClientInputSession session, int action, int button,
      long now, boolean alt, boolean control) {
      return capture(minecraft, session, action, button, now, alt, control, null);
   }

   static boolean replay(Minecraft minecraft, ClientInputSession session, int button, long now,
      boolean control, IdlePressSnapshot press) {
      return capture(minecraft, session, MouseButtonInputSemantics.PRESS, button, now, false, control, press).consumesVanilla();
   }

   private static Result capture(Minecraft minecraft, ClientInputSession session, int action, int button,
      long now, boolean alt, boolean control, IdlePressSnapshot frozen) {
      session.bindings.synchronizeShapeDefinitions(minecraft, session);
      if (session.entryPressGuard.capture(action, button, now)) {
         minecraft.options.keyUse.setDown(false);
         return new Result(true, true);
      }
      if (action == MouseButtonInputSemantics.RELEASE && button == session.idleEntryButton) {
         session.idleEntryButton = -1;
         session.releaseQuickShapeButton(button);
         var release = session.selectionPointer.release(session.idleEntrySelectionButton, now);
         if (release != null) session.postSelectionPointer(release);
         session.idleEntrySelectionButton = -1;
         return new Result(true, true);
      }
      if (!eligible(minecraft, session)) return new Result(false, false);
      if (action != MouseButtonInputSemantics.PRESS) return new Result(true, false);
      if (frozen == null && !session.idleButtons.press(button)) return new Result(true, false);
      session.idleEntry.validate(minecraft.level, minecraft.player.getMainHandItem());
      Snapshot snapshot = snapshot(minecraft, session, button, now, alt, control, frozen);
      BindingAction.Result result = session.bindings.onIdleSignals(minecraft, session, MouseButtons.name(button), snapshot.anchor != null,
         snapshot.context, commands(minecraft, session, button, now, alt, control, frozen != null, snapshot));
      return new Result(true, result.consumesVanilla());
   }

   private static boolean eligible(Minecraft minecraft, ClientInputSession session) {
      return minecraft.player != null && minecraft.level != null
         && session.routing.state() == ClientInputStateMachine.State.IDLE && minecraft.player.isCreative()
         && ClientSessionSystem.instance().previews().building().enabled() && minecraft.getCameraEntity() == minecraft.player
         && ServerGateway.supports(minecraft, IdleInteractionCapturePayload.TYPE);
   }

   private static Snapshot snapshot(Minecraft minecraft, ClientInputSession session, int button, long now, boolean alt, boolean control, IdlePressSnapshot frozen) {
      IdlePressSnapshot press = frozen == null ? IdlePressSnapshot.capture(minecraft, alt) : frozen;
      EnumSet<Fact> facts = EnumSet.noneOf(Fact.class);
      put(facts, Fact.ALT, alt);
      put(facts, Fact.CONTROL, control);
      put(facts, Fact.DISTANT, LongRangeBlockRaycast.beyondInteractionRange(minecraft.player, press.directHit()));
      put(facts, Fact.ENTITY, frozen == null && minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.ENTITY);
      put(facts, Fact.REPLAY, frozen != null);
      put(facts, Fact.OUTLINE, press.outline() != null);
      put(facts, Fact.DIRECT, press.directHit() != null && press.directHit().getType() == HitResult.Type.BLOCK);
      put(facts, Fact.PLACEABLE, PlaceableItems.isPlaceable(minecraft.player.getMainHandItem()));
      put(facts, Fact.TINKER, press.outline() != null && ClientBlockTinker.consumes(minecraft.player, InteractionHand.MAIN_HAND, press.outline())
         && ServerGateway.supports(minecraft, TinkerInteractionPayload.TYPE));
      put(facts, Fact.PASSTHROUGH, press.outline() != null && minecraft.level.getBlockState(press.outline().getBlockPos())
         .is(TagKey.create(Registries.BLOCK, ResourceLocation.parse("fastformer:hold_line_passthrough"))));
      facts.add(Fact.ANCHOR);
      var environment = environment(minecraft, press, facts.contains(Fact.PLACEABLE));
      var policy = session.bindings.idleDoublePolicy(minecraft, session, MouseButtons.name(button), new IdleBindingContext(facts).freeze(environment));
      var anchor = policy.isEmpty() ? null : session.idleEntry.secondPress(button, now, press.eye(), press.view());
      put(facts, Fact.ANCHOR, anchor != null);
      return new Snapshot(press, anchor, policy.orElse(DoubleClickPolicy.DEFAULT), new IdleBindingContext(facts).freeze(environment));
   }

   private static Map<String, JsonElement> environment(Minecraft minecraft, IdlePressSnapshot press, boolean placeable) {
      Map<String, JsonElement> fields = new HashMap<>();
      JsonArray items = new JsonArray();
      items.add(BuiltInRegistries.ITEM.getKey(minecraft.player.getMainHandItem().getItem()).toString());
      minecraft.player.getMainHandItem().getTags().forEach(tag -> items.add("#" + tag.location()));
      if (placeable) items.add("#fastformer:placeable");
      fields.put("items", items);
      JsonArray tags = new JsonArray();
      if (press.outline() != null) minecraft.level.getBlockState(press.outline().getBlockPos()).getTags().forEach(tag -> tags.add("#" + tag.location()));
      fields.put("aiming_tag", tags);
      fields.put("through_fluids", new JsonPrimitive(press.throughFluids()));
      fields.put("in_fluid", new JsonPrimitive(minecraft.player.isInWater()));
      fields.put("sneaking", new JsonPrimitive(minecraft.player.isShiftKeyDown()));
      return fields;
   }

   private static void put(EnumSet<Fact> facts, Fact fact, boolean enabled) {
      if (enabled) facts.add(fact); else facts.remove(fact);
   }

   private static BindingCommands commands(Minecraft minecraft, ClientInputSession session, int button, long now,
      boolean alt, boolean control, boolean replay, Snapshot snapshot) {
      return new BindingCommands() {
         public BindingAction.Result idle(IdleCommand command, IdleEntryParameters parameters) {
            var press = snapshot.press;
            return switch (command) {
               case CLEAR -> {
                  session.idleEntry.clear();
                  yield BindingAction.Result.HANDLED_PASS_VANILLA;
               }
               case ENTER_EMBEDDED, ENTER_SURFACE -> {
                  session.idleEntry.clear();
                  boolean started = enter(minecraft, session, press.directHit(), now, control, 0, button,
                     command == IdleCommand.ENTER_EMBEDDED, press.throughFluids(), parameters.middleEntry(), press);
                  if (started) {
                     if (alt) session.entryConsumedAlt = true;
                     if (parameters.guardMillis() > 0) session.entryPressGuard.arm(now, button, parameters.guardMillis());
                  }
                  yield terminal(started);
               }
               case ENTER_ANCHOR -> terminal(enterAnchor(minecraft, session, button, now, control, snapshot));
               case TINKER -> {
                  if (press.outline() == null || press.shape() == null || !ServerGateway.supports(minecraft, TinkerInteractionPayload.TYPE)
                     || !ClientBlockTinker.consumes(minecraft.player, InteractionHand.MAIN_HAND, press.outline())) {
                     yield BindingAction.Result.HANDLED_PASS_VANILLA;
                  }
                  long token = ++nextToken;
                  session.idleEntry.rememberTinker(token, now, press.shape(), press.eye(), press.view(), minecraft.level,
                     minecraft.player.getMainHandItem(), snapshot.policy, button);
                  session.idleEntryButton = button;
                  minecraft.options.keyUse.setDown(false);
                  ServerGateway.send(new TinkerInteractionPayload(token, press.outline(), press.eye(), press.view(), snapshot.policy));
                  yield BindingAction.Result.HANDLED;
               }
               case HOLD -> !replay && session.bindings.onIdlePress(minecraft, session, button, now, control, press)
                  ? BindingAction.Result.HANDLED : BindingAction.Result.PASS;
               case REMEMBER -> {
                  remember(minecraft, session, button, now, snapshot);
                  yield BindingAction.Result.HANDLED_PASS_VANILLA;
               }
            };
         }

         public void replayPress(long time) { throw new IllegalStateException("Idle entry actions do not replay a captured hold"); }
      };
   }

   private static BindingAction.Result terminal(boolean consumed) {
      return consumed ? BindingAction.Result.HANDLED : BindingAction.Result.HANDLED_PASS_VANILLA;
   }

   private static boolean enterAnchor(Minecraft minecraft, ClientInputSession session, int button, long now,
      boolean control, Snapshot snapshot) {
      var anchor = snapshot.anchor;
      if (anchor == null) return false;
      session.idleEntry.clear();
      if (!anchor.tinker()) return enter(minecraft, session, anchor.hit(), now, control, anchor.token(), button,
         anchor.rules().embedded(), anchor.rules().throughFluids(), false, snapshot.press);
      boolean started = IdleSelectionEntryController.begin(minecraft, session, anchor.token(), snapshot.press.eye(), snapshot.press.view(), control, now);
      if (started) {
         session.idleEntryButton = button;
         minecraft.options.keyUse.setDown(false);
      }
      return started;
   }

   private static void remember(Minecraft minecraft, ClientInputSession session, int button, long now, Snapshot snapshot) {
      var press = snapshot.press;
      if (press.outline() == null || press.shape() == null) { session.idleEntry.clear(); return; }
      long token = ++nextToken;
      var rules = InteractionRaycastRules.VANILLA_OUTLINE;
      session.idleEntry.remember(token, now, press.shape(), press.eye(), press.view(), minecraft.level,
         minecraft.player.getMainHandItem(), rules, snapshot.policy, button);
      ServerGateway.send(new IdleInteractionCapturePayload(token, press.outline(), press.eye(), press.view(), rules, snapshot.policy));
   }

   private static boolean enter(Minecraft minecraft, ClientInputSession session, BlockHitResult hit, long now,
      boolean control, long token, int button, boolean embedded, boolean throughFluids, boolean middleEntry, IdlePressSnapshot press) {
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
      boolean placeable = PlaceableItems.isPlaceable(minecraft.player.getMainHandItem());
      boolean started = placeable
         ? QuickShapeStartController.capture(minecraft, session, hit, token, button, embedded, throughFluids,
            press.eye(), press.view(), SessionProfiles.NONE, middleEntry)
         : FastPlaceClientInput.captureSelectionEntry(hit.getBlockPos(), control, now);
      if (!started) return false;
      if (!placeable) session.idleEntrySelectionButton = MouseButtonInputSemantics.RIGHT_BUTTON;
      session.modifier.consume();
      session.idleEntryButton = button;
      minecraft.options.keyUse.setDown(false);
      return true;
   }
}
