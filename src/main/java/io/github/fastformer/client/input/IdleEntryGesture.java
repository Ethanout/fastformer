package io.github.fastformer.client.input;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import io.github.fastformer.common.interaction.binding.DoubleClickPolicy;
import io.github.fastformer.common.raycast.InteractionRaycastRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** One physical right press can pair with one later press, never with a held-key repeat. */
final class IdleEntryGesture {
   private long token;
   private long pressedAt;
   private int physicalButton;
   private InteractionShapeSnapshot snapshot;
   private DoubleClickPolicy.Captured tolerance;
   private Vec3 firstEye;
   private Vec3 firstView;
   private boolean tinker;
   private Object environment;
   private net.minecraft.world.item.ItemStack item;
   private InteractionRaycastRules rules = InteractionRaycastRules.DEFAULT;

   record Match(long token, BlockHitResult hit, InteractionRaycastRules rules, boolean tinker) { }

   void remember(long token, long pressedAt, InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view, Object environment,
      net.minecraft.world.item.ItemStack item) {
      remember(token, pressedAt, snapshot, eye, view, environment, item, InteractionRaycastRules.DEFAULT);
   }

   void remember(long token, long pressedAt, InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view, Object environment,
      net.minecraft.world.item.ItemStack item, InteractionRaycastRules rules) {
      remember(token, pressedAt, snapshot, eye, view, environment, item, rules, DoubleClickPolicy.DEFAULT);
   }

   void remember(long token, long pressedAt, InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view, Object environment,
      net.minecraft.world.item.ItemStack item, InteractionRaycastRules rules, DoubleClickPolicy doubleClick) {
      remember(token, pressedAt, snapshot, eye, view, environment, item, rules, doubleClick, 1);
   }

   void remember(long token, long pressedAt, InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view, Object environment,
      net.minecraft.world.item.ItemStack item, InteractionRaycastRules rules, DoubleClickPolicy doubleClick, int physicalButton) {
      this.token = token;
      this.pressedAt = pressedAt;
      this.physicalButton = physicalButton;
      this.snapshot = snapshot;
      this.firstEye = eye;
      this.firstView = view.normalize();
      this.tolerance = doubleClick.capture(snapshot, eye, this.firstView);
      tinker = false;
      this.environment = environment;
      this.item = item == null ? null : item.copy();
      this.rules = java.util.Objects.requireNonNull(rules);
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
      rememberTinker(token, pressedAt, original, eye, view, environment, item, DoubleClickPolicy.DEFAULT);
   }

   void rememberTinker(long token, long pressedAt, InteractionShapeSnapshot original,
      Vec3 eye, Vec3 view, Object environment, net.minecraft.world.item.ItemStack item, DoubleClickPolicy policy) {
      rememberTinker(token, pressedAt, original, eye, view, environment, item, policy, 1);
   }

   void rememberTinker(long token, long pressedAt, InteractionShapeSnapshot original,
      Vec3 eye, Vec3 view, Object environment, net.minecraft.world.item.ItemStack item, DoubleClickPolicy policy, int physicalButton) {
      remember(token, pressedAt, original, eye, view, environment, item, InteractionRaycastRules.VANILLA_OUTLINE, policy, physicalButton);
      tinker = true;
   }

   void update(long token, InteractionShapeSnapshot snapshot) {
      if (token > 0 && this.token == token) this.snapshot = snapshot;
   }

   Match secondPress(long now, Vec3 eye, Vec3 view) {
      return secondPress(1, now, eye, view);
   }

   Match secondPress(int physicalButton, long now, Vec3 eye, Vec3 view) {
      if (this.physicalButton != physicalButton || snapshot == null || now < pressedAt || now - pressedAt > tolerance.windowNanos()) return null;
      // Clip only the frozen shape: intervening world blocks do not occlude it.
      var secondHit = tolerance.match(snapshot, firstEye, firstView, eye, view);
      return secondHit == null ? null : new Match(token, secondHit, rules, tinker);
   }

   void clear() {
      token = 0;
      pressedAt = 0;
      physicalButton = -1;
      snapshot = null;
      tolerance = null;
      firstEye = null;
      firstView = null;
      tinker = false;
      environment = null;
      item = null;
      rules = InteractionRaycastRules.DEFAULT;
   }
}
