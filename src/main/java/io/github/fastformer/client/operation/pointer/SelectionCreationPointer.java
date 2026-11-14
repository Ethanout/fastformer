package io.github.fastformer.client.operation.pointer;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.InteractionIntentProvider;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.interaction.pointer.PointerResolver;
import io.github.fastformer.client.operation.edit.SelectionEdits;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

/** Resolves new selection points without consulting render state. */
public final class SelectionCreationPointer implements InteractionIntentProvider {
   private final ClientSelectionSession selection;
   private final SelectionEdits edits;
   private final Supplier<OperationPreviewPayload> preview;
   private final PointerResolver pointer;
   private final BooleanSupplier canCreate;

   public SelectionCreationPointer(ClientSelectionSession selection, SelectionEdits edits,
      Supplier<OperationPreviewPayload> preview, PointerResolver pointer, BooleanSupplier canCreate) {
      this.selection = Objects.requireNonNull(selection, "selection");
      this.edits = Objects.requireNonNull(edits, "edits");
      this.preview = Objects.requireNonNull(preview, "preview");
      this.pointer = Objects.requireNonNull(pointer, "pointer");
      this.canCreate = Objects.requireNonNull(canCreate, "canCreate");
   }

   @Override public Optional<OperationInteractionIntent> resolve(InteractionContext context) {
      if (context == null) return Optional.empty();
      if (context.alternative()) {
         if (!canCreate.getAsBoolean() || !hasWorld(context)) return Optional.empty();
         return intent(selectionCreationPoint(sample(context).hit()));
      }
      if (edits.canStartSelectionDraft() || !selection.workspace().isEmpty()) {
         if (!hasWorld(context)) return Optional.empty();
         return intent(draftCandidate(selection.draftState(), context, sample(context).hit()));
      }
      var remote = preview.get();
      if (context.vanillaOwnsPointer() || !(remote.active() || selection.retained())
         || remote.active() && remote.operationSelectionConfirmed()
         || remote.active() && remote.operationSelectionMode() == OperationSelectionMode.CUBOID
            && remote.hasFirst() && remote.hasSecond() || !hasWorld(context)) return Optional.empty();
      return intent(remoteCandidate(remote, context, sample(context)));
   }

   private io.github.fastformer.common.raycast.LongRangeBlockRaycast.Result sample(InteractionContext context) {
      return pointer.selection(context.player(), context.alternative());
   }

   private static boolean hasWorld(InteractionContext context) {
      return context.minecraft() != null && context.minecraft().level != null && context.player() != null;
   }

   private static Optional<OperationInteractionIntent> intent(BlockPos point) {
      return point == null ? Optional.empty() : Optional.of(new OperationInteractionIntent.CreateSelection(point));
   }

   static BlockPos selectionCreationPoint(BlockHitResult hit) {
      return hit != null && hit.getType() == Type.BLOCK ? hit.getBlockPos() : null;
   }

   static BlockPos draftCandidate(ClientSelectionSession.DraftState draft, InteractionContext context, BlockHitResult hit) {
      if (draft.selectionMode() != OperationSelectionMode.PRISM || draft.points().size() < 3)
         return selectionCreationPoint(hit);
      double distance = context.eye().distanceTo(hit.getLocation());
      if (draft.prismBaseCount() > 0) return SelectionPrism.resolveHeightPoint(
         draft.points().subList(0, draft.prismBaseCount()), context.eye(), context.view(), distance);
      Vec3 first = Vec3.atCenterOf(draft.points().getFirst());
      double along = first.subtract(context.eye()).dot(context.view());
      if (along > 0 && context.eye().add(context.view().scale(along)).distanceTo(first) < 0.35) return draft.points().getFirst();
      return SelectionPrism.resolveBasePlanePoint(draft.points(), context.eye(), context.view(), distance);
   }

   static BlockPos remoteCandidate(OperationPreviewPayload remote, InteractionContext context,
      io.github.fastformer.common.raycast.LongRangeBlockRaycast.Result ray) {
      if (remote.active() && remote.operationSelectionConfirmed()) return null;
      if (remote.active() && remote.operationSelectionMode() == OperationSelectionMode.PRISM) {
         int baseCount = remote.operationPrismBasePointCount();
         if (baseCount == 0 && remote.points().size() >= 3) return SelectionPrism.resolveBasePlanePoint(
            remote.points(), context.eye(), context.view(), ray.distance());
         if (baseCount >= 3 && remote.points().size() == baseCount) return SelectionPrism.resolveHeightPoint(
            remote.points().subList(0, baseCount), context.eye(), context.view(), ray.distance());
      }
      return selectionCreationPoint(ray.hit());
   }
}
