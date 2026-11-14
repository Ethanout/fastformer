package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.registry.Registry;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.server.specialitem.SpecialItemHandlers;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.QuickShapeSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Per-server mode assembly. Editor lifetime is registered once even when it has several modes. */
public final class ServerModes {
   private final List<ServerMode> activeOrder;
   private final List<ServerMode> confirmOrder;
   private final List<ServerMode> heldItemOrder;
   private final List<ServerMode> registered;
   private final List<GeometryServerMode> geometryModes;
   private final List<ServerEditor> editors;
   private final QuickShapeServerMode quickShape;
   private final SelectionServerMode selection;

   public ServerModes(QuickShapeSystem quickShape, OperationSystem selection, GeometrySystem geometry, Consumer<ServerPlayer> quit) {
      this.quickShape = new QuickShapeServerMode(quickShape, quit);
      this.selection = new SelectionServerMode(selection);
      List<GeometryServerMode> shapes = new ArrayList<>();
      for (GeometryMode mode : GeometryMode.values()) shapes.add(new GeometryServerMode(geometry, mode));
      geometryModes = List.copyOf(shapes);
      activeOrder = joined(List.of(this.selection), geometryModes, List.of(this.quickShape));
      // Confirm historically gives geometry priority if an abnormal boundary retains two editors.
      confirmOrder = joined(geometryModes, List.of(this.selection, this.quickShape));
      heldItemOrder = List.of(
         new HeldItemServerMode("special_item", InteractionState.SPECIAL_ITEM, player -> SpecialItemHandlers.isSpecial(player.getMainHandItem())),
         this.selection, this.quickShape, new HeldItemServerMode("vanilla", InteractionState.VANILLA, player -> true));
      editors = List.of(quickShape, selection, geometry);
      registered = joined(activeOrder, heldItemOrder.stream().filter(mode -> !activeOrder.contains(mode)).toList());
      Registry<String, ServerMode> registry = Registry.create("server modes");
      for (ServerMode mode : registered) {
         registry.register(mode.id(), mode);
      }
      registry.freeze();
   }

   public List<ServerMode> registered() { return registered; }

   public Optional<ServerMode> active(ServerPlayer player) { return firstActive(activeOrder, player); }
   public boolean hasActive(ServerPlayer player) { return active(player).isPresent(); }
   public boolean ownsInput(ServerPlayer player, InteractionState state) {
      return active(player).filter(mode -> mode.interactionState() == state).isPresent();
   }
   public ServerMode current(ServerPlayer player) {
      return active(player).orElseGet(() -> heldItemOrder.stream().filter(mode -> mode.acceptsHeldItem(player)).findFirst().orElseThrow());
   }

   public IntentResult cycle(ServerPlayer player, BlockPos lineCandidate) {
      ServerMode mode = active(player).orElse(quickShape.acceptsHeldItem(player) ? quickShape : selection);
      return mode.cycle(player, lineCandidate);
   }

   public boolean allowsCommandAdjust(ServerPlayer player) {
      return hasActive(player) && activeOrder.stream().filter(mode -> mode.active(player)).allMatch(mode -> mode.allowsCommandAdjust(player));
   }

   public boolean allowsSubmode(ServerPlayer player) {
      return activeOrder.stream().filter(mode -> mode.active(player)).allMatch(mode -> mode.allowsSubmode(player));
   }

   public void adjust(ServerPlayer player, int steps) { active(player).orElse(quickShape).adjust(player, steps); }
   public IntentResult confirm(ServerPlayer player) {
      return firstActive(confirmOrder, player).map(mode -> mode.confirm(player)).orElseGet(IntentResult::pass);
   }

   public boolean geometryPoint(ServerPlayer player, BlockHitResult hit, Vec3 eye, Vec3 view) {
      return geometryModes.stream().filter(mode -> mode.active(player))
         .findFirst().map(mode -> mode.point(player, hit, eye, view)).orElse(false);
   }

   public void modifierChanged(ServerPlayer player, boolean held, boolean sync) {
      editors.forEach(editor -> editor.modifierChanged(player, held));
      if (sync) editors.stream().filter(editor -> editor.active(player)).forEach(editor -> editor.sync(player));
   }

   public void cancelEditing(ServerPlayer player) { editors.forEach(editor -> editor.cancel(player)); }
   public void discardPlayer(ServerPlayer player) { editors.forEach(editor -> editor.remove(player)); }
   public void parkDimension(UUID owner, ResourceKey<Level> dimension) { editors.forEach(editor -> editor.parkDimensionSession(owner, dimension)); }
   public void restoreDimension(UUID owner, ResourceKey<Level> dimension) { editors.forEach(editor -> editor.restoreDimensionSession(owner, dimension)); }

   private static Optional<ServerMode> firstActive(List<ServerMode> modes, ServerPlayer player) {
      return modes.stream().filter(mode -> mode.active(player)).findFirst();
   }

   @SafeVarargs private static List<ServerMode> joined(List<? extends ServerMode>... lists) {
      List<ServerMode> result = new ArrayList<>();
      for (var list : lists) result.addAll(list);
      return List.copyOf(result);
   }
}
