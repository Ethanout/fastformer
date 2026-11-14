package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.mode.ModeId;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** A mode binds typed state to its opening, input, preview and lifecycle hooks. */
public abstract class ServerMode<S> {
   private final ModeId id;
   private final InteractionState state;
   private final ServerEditor<S> repository;
   private boolean closed;

   protected ServerMode(ModeId id, InteractionState state, ServerEditor<S> repository) {
      this.id = Objects.requireNonNull(id, "id");
      this.state = Objects.requireNonNull(state, "state");
      this.repository = Objects.requireNonNull(repository, "repository");
   }

   public final ModeId id() { return id; }
   public final InteractionState interactionState() { return state; }
   public final ServerEditor<S> repository() { return repository; }
   protected boolean owns(S session) { return true; }
   protected boolean activeSession(S session) { return true; }
   public final Optional<S> session(ServerPlayer player) {
      return closed ? Optional.empty() : repository.session(player).filter(this::owns);
   }
   public final boolean active(ServerPlayer player) { return session(player).filter(this::activeSession).isPresent(); }
   public final S open(ServerPlayer player, S session) {
      if (closed) throw new IllegalStateException("Mode " + id + " is closed");
      Objects.requireNonNull(session, "session");
      if (!owns(session)) throw new IllegalArgumentException("Session does not belong to mode " + id);
      repository.activate(player, session);
      onOpen(player, session);
      return session;
   }
   protected void onOpen(ServerPlayer player, S session) {}
   public boolean acceptsHeldItem(ServerPlayer player) { return false; }
   public final boolean allowsCommandAdjust(ServerPlayer player) { return session(player).map(value -> allowsCommandAdjust(player, value)).orElse(true); }
   protected boolean allowsCommandAdjust(ServerPlayer player, S session) { return true; }
   public final boolean allowsSubmode(ServerPlayer player) { return session(player).map(value -> allowsSubmode(player, value)).orElse(true); }
   protected boolean allowsSubmode(ServerPlayer player, S session) { return true; }
   public final boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) {
      if (closed) return false;
      return session(player).map(value -> onRightClickBlock(player, value, hit)).orElseGet(() -> onIdleRightClickBlock(player, hit));
   }
   protected boolean onRightClickBlock(ServerPlayer player, S session, BlockHitResult hit) { return false; }
   protected boolean onIdleRightClickBlock(ServerPlayer player, BlockHitResult hit) { return false; }
   public final boolean rightClickItem(ServerPlayer player, BlockHitResult hit) {
      if (closed) return false;
      return session(player).map(value -> onRightClickItem(player, value, hit)).orElseGet(() -> onIdleRightClickItem(player, hit));
   }
   protected boolean onRightClickItem(ServerPlayer player, S session, BlockHitResult hit) { return false; }
   protected boolean onIdleRightClickItem(ServerPlayer player, BlockHitResult hit) { return false; }
   public final IntentResult cycle(ServerPlayer player, BlockPos candidate) {
      if (closed) return IntentResult.pass();
      return session(player).map(value -> onCycle(player, value, candidate)).orElseGet(() -> onIdleCycle(player, candidate));
   }
   protected IntentResult onCycle(ServerPlayer player, S session, BlockPos candidate) { return IntentResult.pass(); }
   protected IntentResult onIdleCycle(ServerPlayer player, BlockPos candidate) { return IntentResult.pass(); }
   public final void adjust(ServerPlayer player, int steps) {
      if (closed) return;
      var current = session(player);
      if (current.isPresent()) onAdjust(player, current.orElseThrow(), steps);
      else onIdleAdjust(player, steps);
   }
   protected void onAdjust(ServerPlayer player, S session, int steps) {}
   protected void onIdleAdjust(ServerPlayer player, int steps) {}
   public final IntentResult confirm(ServerPlayer player) {
      return session(player).map(value -> onConfirm(player, value)).orElseGet(IntentResult::pass);
   }
   protected IntentResult onConfirm(ServerPlayer player, S session) { return IntentResult.pass(); }
   public final ModeInputResult input(ServerPlayer player, ModeInput input) {
      return session(player).map(value -> onInput(player, value, input)).orElse(ModeInputResult.NEXT);
   }
   protected ModeInputResult onInput(ServerPlayer player, S session, ModeInput input) { return ModeInputResult.NEXT; }
   public final IntentResult quit(ServerPlayer player) {
      return session(player).map(value -> onQuit(player, value)).orElseGet(IntentResult::pass);
   }
   protected IntentResult onQuit(ServerPlayer player, S session) { return IntentResult.pass(); }
   public final void sync(ServerPlayer player) { session(player).ifPresent(value -> onPreview(player, value)); }
   protected void onPreview(ServerPlayer player, S session) {}
   public final void tick(ServerPlayer player) { session(player).filter(this::activeSession).ifPresent(value -> onTick(player, value)); }
   protected void onTick(ServerPlayer player, S session) {}
   public final void cancel(ServerPlayer player) {
      if (closed) return;
      var current = repository.session(player);
      if (current.isPresent() && !owns(current.orElseThrow())) return;
      current.ifPresent(value -> onCancel(player, value));
      repository.cancel(player);
   }
   protected void onCancel(ServerPlayer player, S session) {}
   public final void modifierChanged(ServerPlayer player, boolean held) {
      session(player).ifPresent(value -> onModifierChanged(player, value, held));
   }
   protected void onModifierChanged(ServerPlayer player, S session, boolean held) { repository.modifierChanged(player, held); }
   final void detach(ServerPlayer player) { session(player).ifPresent(value -> onDetach(player, value)); }
   protected void onDetach(ServerPlayer player, S session) {}
   final void dimensionChanged(ServerPlayer player) { session(player).ifPresent(value -> onDimensionChanged(player, value)); }
   protected void onDimensionChanged(ServerPlayer player, S session) {}
   final void retire() { if (!closed) { closed = true; onClose(); } }
   protected void onClose() {}

   public LongRangeBlockRaycast.Result raycast(ServerPlayer player, Vec3 eye, Vec3 view, boolean modifierHeld) {
      return LongRangeBlockRaycast.clip(player.level(), player, eye, view);
   }
}
