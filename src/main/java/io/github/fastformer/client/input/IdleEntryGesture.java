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
   }

   void validate(Object environment, net.minecraft.world.item.ItemStack item) {
      boolean sameItem = this.item == item || this.item != null && item != null
         && net.minecraft.world.item.ItemStack.isSameItemSameComponents(this.item, item);
      if (this.environment != environment || !sameItem) clear();
   }

   long token() { return token; }
   boolean tinker() { return tinker; }

   void rememberTinker(long token, long pressedAt, InteractionShapeSnapshot original,
      Vec3 eye, Vec3 view, Object environment, net.minecraft.world.item.ItemStack item) {
      remember(token, pressedAt, original, eye, view, environment, item);
      tinker = true;
   }

   void update(long token, InteractionShapeSnapshot snapshot) {
      if (token > 0 && this.token == token) this.snapshot = snapshot;
   }

   BlockHitResult secondPress(long now, Vec3 eye, Vec3 view) {
      if (snapshot == null || now < pressedAt || now - pressedAt > DOUBLE_PRESS_NANOS) return null;
      // Clip only the frozen shape: intervening world blocks do not occlude it.
      var secondHit = snapshot.clip(eye, view);
      if (secondHit == null) return null;
      if (firstView.dot(view.normalize()) >= MIN_VIEW_DOT) return secondHit;
      var firstHit = snapshot.clip(firstEye, firstView);
      return firstHit != null && firstHit.getLocation().distanceToSqr(secondHit.getLocation())
         <= MAX_HIT_DISTANCE * MAX_HIT_DISTANCE ? secondHit : null;
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
   }
}
