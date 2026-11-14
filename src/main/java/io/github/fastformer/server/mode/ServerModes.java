package io.github.fastformer.server.mode;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.common.quickshape.QuickShapeDraft;
import io.github.fastformer.common.selection.session.OperationSession;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.registry.Registry;
import io.github.fastformer.server.fill.SmartFillSystem;
import io.github.fastformer.server.geometry.workflow.GeometryWorkflowEditors;
import io.github.fastformer.server.mode.tool.QuickReplaceServerMode;
import io.github.fastformer.server.mode.tool.SmartFillServerMode;
import io.github.fastformer.server.mode.tool.TemporaryServerMode;
import io.github.fastformer.server.replace.QuickReplaceSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.specialitem.SpecialItemHandlers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Per-server mode assembly; shared repositories receive each lifecycle notification once. */
public final class ServerModes implements AutoCloseable {
   private final List<ServerMode<?>> activeOrder;
   private final List<ServerMode<?>> confirmOrder;
   private final List<ServerMode<?>> heldItemOrder;
   private final List<ServerMode<?>> registered;
   private final List<GeometryServerMode> geometryModes;
   private final List<ServerEditor<?>> editingRepositories;
   private final List<ServerEditor<?>> repositories;
   private final List<TemporaryServerMode<?>> temporaryModes;
   private final Registry<ModeId, ServerMode<?>> registry;
   private final QuickShapeServerMode quickShape;
   private final SelectionServerMode selection;
   private final QuickReplaceServerMode quickReplace;
   private final SmartFillServerMode smartFill;
   private boolean closed;

   public ServerModes(QuickShapeSystem quickShape, OperationSystem selection, GeometrySystem geometry, Consumer<ServerPlayer> quit) {
      this.quickShape = new QuickShapeServerMode(quickShape, quit);
      this.selection = new SelectionServerMode(selection);
      List<GeometryServerMode> shapes = new ArrayList<>();
      for (var workflow : GeometryWorkflowEditors.registered()) shapes.add(new GeometryServerMode(geometry, workflow.mode()));
      geometryModes = List.copyOf(shapes);
      activeOrder = joined(List.of(this.selection), geometryModes, List.of(this.quickShape));
      // Confirm historically gives geometry priority if an abnormal boundary retains two editors.
      confirmOrder = joined(geometryModes, List.of(this.selection, this.quickShape));
      heldItemOrder = List.of(
         new HeldItemServerMode(BuiltinModes.SPECIAL_ITEM, InteractionState.SPECIAL_ITEM, player -> SpecialItemHandlers.isSpecial(player.getMainHandItem())),
         this.selection, this.quickShape, new HeldItemServerMode(BuiltinModes.VANILLA, InteractionState.VANILLA, player -> true));
      this.smartFill = new SmartFillServerMode();
      this.quickReplace = new QuickReplaceServerMode();
      temporaryModes = List.of(this.smartFill, this.quickReplace);
      registered = joined(activeOrder, heldItemOrder.stream().filter(mode -> !activeOrder.contains(mode)).toList(), temporaryModes);
      editingRepositories = repositories(activeOrder);
      repositories = repositories(registered);
      registry = Registry.create("server modes");
      for (ServerMode<?> mode : registered) registry.register(mode.id(), mode);
      registry.freeze();
   }

   public List<ServerMode<?>> registered() { return registered; }
   public Optional<ServerMode<?>> find(ModeId id) { return registry.get(id); }
   public ServerMode<QuickShapeDraft> quickShape() { return quickShape; }
   public ServerMode<OperationSession> selection() { return selection; }
   public ServerMode<QuickReplaceSystem.State> quickReplace() { return quickReplace; }
   public ServerMode<SmartFillSystem.Session> smartFill() { return smartFill; }
   public ServerMode<GeometrySession> geometry(GeometryMode mode) {
      return geometryModes.stream().filter(candidate -> candidate.id().equals(BuiltinModes.geometry(mode)))
         .findFirst().orElseThrow(() -> new IllegalArgumentException("Unregistered geometry mode " + mode));
   }
   public boolean active(ServerPlayer player, ModeId id) { return find(id).filter(mode -> mode.active(player)).isPresent(); }
   public Optional<ServerMode<?>> editing(ServerPlayer player) { return firstActive(activeOrder, player); }
   public boolean hasEditing(ServerPlayer player) { return editing(player).isPresent(); }
   public Optional<ServerMode<?>> active(ServerPlayer player) {
      return temporary(player).<ServerMode<?>>map(mode -> mode).or(() -> editing(player));
   }
   public boolean ownsInput(ServerPlayer player, InteractionState state) {
      return editing(player).filter(mode -> mode.interactionState() == state).isPresent();
   }
   public ServerMode<?> current(ServerPlayer player) {
      return active(player).orElseGet(() -> heldItemOrder.stream().filter(mode -> mode.acceptsHeldItem(player)).findFirst().orElseThrow());
   }
   public IntentResult cycle(ServerPlayer player, BlockPos lineCandidate) {
      ServerMode<?> mode = editing(player).orElse(quickShape.acceptsHeldItem(player) ? quickShape : selection);
      return mode.cycle(player, lineCandidate);
   }
   public boolean allowsCommandAdjust(ServerPlayer player) {
      return hasEditing(player) && activeOrder.stream().filter(mode -> mode.active(player)).allMatch(mode -> mode.allowsCommandAdjust(player));
   }
   public boolean allowsSubmode(ServerPlayer player) {
      return activeOrder.stream().filter(mode -> mode.active(player)).allMatch(mode -> mode.allowsSubmode(player));
   }
   public void adjust(ServerPlayer player, int steps) { editing(player).orElse(quickShape).adjust(player, steps); }
   public IntentResult confirm(ServerPlayer player) {
      var temporary = temporary(player);
      if (temporary.isPresent()) {
         IntentResult result = temporary.orElseThrow().confirm(player);
         if (result.status() != IntentResult.Status.PASS) return result;
      }
      return firstActive(confirmOrder, player).map(mode -> mode.confirm(player)).orElseGet(IntentResult::pass);
   }
   public ModeInputResult input(ServerPlayer player, ModeInput input) {
      return temporary(player).map(mode -> mode.input(player, input)).orElse(ModeInputResult.NEXT);
   }
   public IntentResult quitTemporary(ServerPlayer player) {
      return temporary(player).map(mode -> mode.quit(player)).orElseGet(IntentResult::pass);
   }
   public void cancelTemporary(ServerPlayer player) { temporaryModes.forEach(mode -> mode.cancel(player)); }
   private Optional<TemporaryServerMode<?>> temporary(ServerPlayer player) {
      return temporaryModes.stream().filter(mode -> mode.active(player)).findFirst();
   }
   public boolean geometryPoint(ServerPlayer player, BlockHitResult hit, Vec3 eye, Vec3 view) {
      return geometryModes.stream().filter(mode -> mode.active(player))
         .findFirst().map(mode -> mode.point(player, hit, eye, view)).orElse(false);
   }
   public void modifierChanged(ServerPlayer player, boolean held, boolean sync) {
      for (ServerEditor<?> repository : repositories) {
         ServerMode<?> mode = owner(repository, player);
         mode.modifierChanged(player, held);
         if (sync && mode.active(player)) mode.sync(player);
      }
   }
   public void cancelEditing(ServerPlayer player) {
      editingRepositories.forEach(repository -> owner(repository, player).cancel(player));
   }
   public void discardPlayer(ServerPlayer player) { repositories.forEach(repository -> repository.remove(player)); }
   public void detach(ServerPlayer player) {
      registered.forEach(mode -> mode.detach(player));
      repositories.forEach(repository -> repository.detach(player));
   }
   public void dimensionChanged(ServerPlayer player) {
      registered.forEach(mode -> mode.dimensionChanged(player));
      repositories.forEach(repository -> repository.dimensionChanged(player));
   }
   public void parkDimension(UUID owner, ResourceKey<Level> dimension) {
      editingRepositories.forEach(repository -> repository.parkDimensionSession(owner, dimension));
   }
   public void restoreDimension(UUID owner, ResourceKey<Level> dimension) {
      editingRepositories.forEach(repository -> repository.restoreDimensionSession(owner, dimension));
   }
   public void tick(ServerPlayer player) { registered.forEach(mode -> mode.tick(player)); }

   @Override public void close() {
      if (closed) return;
      closed = true;
      RuntimeException failure = notifyAll(registered, ServerMode::retire, null);
      failure = notifyAll(repositories, ServerEditor::onDiscard, failure);
      if (failure != null) throw failure;
   }

   private ServerMode<?> owner(ServerEditor<?> repository, ServerPlayer player) {
      return registered.stream().filter(mode -> mode.repository() == repository && mode.active(player)).findFirst()
         .orElseGet(() -> registered.stream().filter(mode -> mode.repository() == repository).findFirst().orElseThrow());
   }
   private static List<ServerEditor<?>> repositories(List<ServerMode<?>> modes) {
      Set<ServerEditor<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      List<ServerEditor<?>> result = new ArrayList<>();
      for (ServerMode<?> mode : modes) if (seen.add(mode.repository())) result.add(mode.repository());
      return List.copyOf(result);
   }
   private static <T> RuntimeException notifyAll(List<T> values, Consumer<T> callback, RuntimeException failure) {
      for (T value : values) {
         try { callback.accept(value); }
         catch (RuntimeException next) {
            if (failure == null) failure = next;
            else if (failure != next) failure.addSuppressed(next);
         }
      }
      return failure;
   }
   private static Optional<ServerMode<?>> firstActive(List<ServerMode<?>> modes, ServerPlayer player) {
      return modes.stream().filter(mode -> mode.active(player)).findFirst();
   }
   @SafeVarargs private static List<ServerMode<?>> joined(List<? extends ServerMode<?>>... lists) {
      List<ServerMode<?>> result = new ArrayList<>();
      for (var list : lists) result.addAll(list);
      return List.copyOf(result);
   }
}
