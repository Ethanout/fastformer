package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** One physical right press can pair with one later press, never with a held-key repeat. */
final class IdleEntryGesture {
   static final long DOUBLE_PRESS_NANOS = io.github.fastformer.client.input.mouse.MouseButtonInputSemantics.ENTRY_DOUBLE_PRESS_NANOS;
   static final double MAX_VIEW_CHANGE_DEGREES = 2.0;
   static final double MAX_HIT_DISTANCE = 0.5;
   private static final double MIN_VIEW_DOT = Math.cos(Math.toRadians(MAX_VIEW_CHANGE_DEGREES));
   private long token;
   private long pressedAt;
   private InteractionShapeSnapshot snapshot;
   private Vec3 firstEye;
   private Vec3 firstView;
   private boolean tinker;
   private Object environment;
   private net.minecraft.world.item.ItemStack item;
   private boolean placementCaptured;

   void remember(long token, long pressedAt, InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view, Object environment,
      net.minecraft.world.item.ItemStack item) {
      this.token = token;
      this.pressedAt = pressedAt;
      this.snapshot = snapshot;
      this.firstEye = eye;
      this.firstView = view.normalize();
      tinker = false;
      this.environment = environment;
      this.item = item == null ? null : item.copy();
      placementCaptured = false;
   }

   void validate(Object environment, net.minecraft.world.item.ItemStack item) {
      boolean sameItem = this.item == item || this.item != null && item != null
         && net.minecraft.world.item.ItemStack.isSameItemSameComponents(this.item, item);
      if (this.environment != environment || !sameItem) clear();
   }

   long token() { return token; }
   boolean tinker() { return tinker; }

   void rememberTinker(long token, long pressedAt, InteractionShapeSnapshot original, InteractionShapeSnapshot changed,
      Vec3 eye, Vec3 view, Object environment, net.minecraft.world.item.ItemStack item) {
      remember(token, pressedAt, original, eye, view, environment, item);
      snapshot = changed;
      tinker = true;
   }

   void update(long token, InteractionShapeSnapshot snapshot) {
      if (token > 0 && this.token == token) this.snapshot = snapshot;
   }

   void placed(long now, InteractionShapeSnapshot snapshot) {
      if (token == 0 || placementCaptured || now < pressedAt || now - pressedAt > 100_000_000L) return;
      this.snapshot = snapshot;
      placementCaptured = true;
   }

   BlockHitResult secondPress(long now, Vec3 eye, Vec3 view) {
      if (snapshot == null || now < pressedAt || now - pressedAt > DOUBLE_PRESS_NANOS) return null;
      if (!(firstView.dot(view.normalize()) >= MIN_VIEW_DOT)) return null;
      // Both rays use the post-interaction shape, including a newly placed block.
      var firstHit = snapshot.clip(firstEye, firstView);
      var secondHit = snapshot.clip(eye, view);
      if (firstHit == null || secondHit == null
         || firstHit.getLocation().distanceToSqr(secondHit.getLocation()) > MAX_HIT_DISTANCE * MAX_HIT_DISTANCE) return null;
      return secondHit;
   }

   void clear() {
      token = 0;
      pressedAt = 0;
      snapshot = null;
      firstEye = null;
      firstView = null;
      tinker = false;
      environment = null;
      item = null;
      placementCaptured = false;
   }
}
