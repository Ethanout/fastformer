package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ForwardDashMotion;
import io.github.fastformer.client.input.state.ForwardDashRequests;
import io.github.fastformer.client.FastFormerClientConfig;
import io.github.fastformer.fastplace.geometry.movement.ForwardDashTraversal;
import io.github.fastformer.network.payload.movement.ForwardDashPayload;
import io.github.fastformer.network.payload.movement.ForwardDashStartPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Applies authorized dash movement after the player tick, preserving vanilla camera interpolation. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ForwardDashController {
   private static final ForwardDashRequests REQUESTS = new ForwardDashRequests();
   private static LocalPlayer owner;
   private static ForwardDashMotion motion;
   private static Vec3 lastPosition;
   private static double tick;

   private ForwardDashController() { }

   public static void request(Minecraft minecraft) {
      validate(minecraft);
      if (!eligible(minecraft) || FastFormerClientConfig.flightSettings().dashDistance() == 0
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), ForwardDashPayload.TYPE.id())
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), ForwardDashStartPayload.TYPE.id())) return;
      owner = minecraft.player;
      if (lastPosition == null) lastPosition = owner.position();
      sendRequest(REQUESTS.request(System.nanoTime()));
   }

   public static void receive(ForwardDashStartPayload payload) {
      Minecraft minecraft = Minecraft.getInstance();
      validate(minecraft);
      checkExternalMovement();
      if (!eligible(minecraft) || owner != minecraft.player || minecraft.player.getId() != payload.entityId()
         || !minecraft.level.dimension().location().equals(payload.dimension())
         || !REQUESTS.accept(payload.requestId())) return;
      // The server may observe an earlier position while an existing dash continues.
      // Apply its approved displacement from here, then recheck every step locally.
      Vec3 displacement = payload.destination().subtract(payload.origin());
      if (displacement.lengthSqr() > 1.0E-10) {
         motion = new ForwardDashMotion(minecraft.player.position(), minecraft.player.position().add(displacement));
         lastPosition = minecraft.player.position();
         tick = 0;
      }
      sendRequest(REQUESTS.nextQueued(System.nanoTime()));
   }

   @SubscribeEvent
   public static void beforeTick(ClientTickEvent.Pre event) {
      Minecraft minecraft = Minecraft.getInstance();
      validate(minecraft);
      checkExternalMovement();
   }

   @SubscribeEvent
   public static void onTick(ClientTickEvent.Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      validate(minecraft);
      if (minecraft.options.keyJump.isDown() || minecraft.options.keyShift.isDown()) {
         clear();
         return;
      }
      if (motion == null) {
         if (owner != null && REQUESTS.pending()) lastPosition = owner.position();
         return;
      }
      Vec3 steering = owner.position().subtract(lastPosition);
      ForwardDashMotion.Step step = motion.advance(tick, steering);
      tick = step.tick();
      Vec3 movement = step.movement();
      double fraction = ForwardDashTraversal.COLLISION_SAFE.clearFraction(owner, movement);
      ForwardDashTraversal.COLLISION_SAFE.move(owner, movement.scale(fraction));
      lastPosition = owner.position();
      if (fraction < 1.0) clear();
      else if (tick >= motion.durationTicks()) finishMotion();
   }

   private static void validate(Minecraft minecraft) {
      if (owner != minecraft.player || !eligible(minecraft) || FastFormerClientConfig.flightSettings().dashDistance() == 0) clear();
      REQUESTS.expire(System.nanoTime());
      if (motion == null && !REQUESTS.pending()) finishMotion();
   }

   private static boolean eligible(Minecraft minecraft) {
      return minecraft.player != null && minecraft.level != null && minecraft.getConnection() != null
         && minecraft.screen == null && minecraft.isWindowActive() && !minecraft.isPaused()
         && minecraft.player.isAlive() && !minecraft.player.isSpectator() && !minecraft.player.isPassenger()
         && minecraft.player.getAbilities().flying && minecraft.getCameraEntity() == minecraft.player;
   }

   private static void sendRequest(long id) {
      if (id != 0) PacketDistributor.sendToServer(new ForwardDashPayload(id, FastFormerClientConfig.flightSettings().dashDistance()));
   }

   private static void checkExternalMovement() {
      // WASD movement occurs inside the player tick; corrections and teleports arrive outside it.
      if (owner != null && lastPosition != null && owner.position().distanceToSqr(lastPosition) > 0.25) clear();
   }

   private static void finishMotion() {
      motion = null;
      tick = 0;
      if (!REQUESTS.pending()) {
         owner = null;
         lastPosition = null;
      } else lastPosition = owner.position();
   }

   private static void clear() {
      REQUESTS.reset();
      finishMotion();
      owner = null;
   }
}
