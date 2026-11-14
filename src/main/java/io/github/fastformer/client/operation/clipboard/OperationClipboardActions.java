package io.github.fastformer.client.operation.clipboard;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.preview.Composition;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Clipboard actions bound to one workspace; no global editor lookup or mutable error channel. */
public final class OperationClipboardActions {
   private final ClientOperationWorkspace workspace;
   private final OperationClipboardRepository repository;
   private final BooleanSupplier submissionPending;
   private final BooleanSupplier editPending;
   private final Runnable changed;

   public OperationClipboardActions(ClientOperationWorkspace workspace, OperationClipboardRepository repository,
      BooleanSupplier submissionPending, BooleanSupplier editPending, Runnable changed) {
      this.workspace = Objects.requireNonNull(workspace, "workspace");
      this.repository = Objects.requireNonNull(repository, "repository");
      this.submissionPending = Objects.requireNonNull(submissionPending, "submissionPending");
      this.editPending = Objects.requireNonNull(editPending, "editPending");
      this.changed = Objects.requireNonNull(changed, "changed");
   }

   public ClipboardActionResult copy() {
      var copied = OperationClipboard.fromWorkspace(workspace);
      if (!(copied instanceof ClipboardCopy.Copied content)) return rejectedCopy(copied);
      try {
         repository.save(content.clipboard());
         return ClipboardActionResult.SUCCESS;
      } catch (IOException | RuntimeException exception) {
         return ClipboardActionResult.STORAGE_FAILED;
      }
   }

   public ClipboardActionResult duplicate() {
      if (submissionPending.getAsBoolean() || editPending.getAsBoolean()) return ClipboardActionResult.PENDING;
      var selected = workspace.selectedParts();
      if (selected.isEmpty()) return ClipboardActionResult.EMPTY;
      if (!hasCapacity(selected.size())) return ClipboardActionResult.PART_LIMIT;
      var copied = OperationClipboard.fromWorkspace(workspace);
      if (!(copied instanceof ClipboardCopy.Copied)) return rejectedCopy(copied);
      return add(selected.stream().map(ClientSelectionPart::duplicate).toList());
   }

   public ClipboardActionResult paste(Minecraft minecraft) {
      if (submissionPending.getAsBoolean()) return ClipboardActionResult.PENDING;
      var clipboard = load(minecraft);
      if (clipboard == null) return ClipboardActionResult.INVALID_CLIPBOARD;
      if (!hasCapacity(clipboard.parts().size())) return ClipboardActionResult.PART_LIMIT;
      Vec3 offset;
      if (!workspace.isEmpty()) {
         offset = PastePlacement.inWorkspace(clipboard.bounds());
      } else {
         if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return ClipboardActionResult.NO_ANCHOR;
         }
         offset = PastePlacement.atSurface(clipboard.bounds(), hit.getLocation(), hit.getDirection());
      }
      return add(clipboard.instantiate().stream().map(part -> part.withTranslation(offset)).toList());
   }

   private OperationClipboard load(Minecraft minecraft) {
      if (minecraft == null || minecraft.level == null) return null;
      try {
         return repository.load(minecraft.level.registryAccess().lookupOrThrow(Registries.BLOCK)).orElse(null);
      } catch (IOException | RuntimeException exception) {
         return null;
      }
   }

   private boolean hasCapacity(int count) {
      return count <= ClientOperationWorkspace.MAX_PARTS - workspace.size();
   }

   private ClipboardActionResult add(java.util.List<ClientSelectionPart> parts) {
      if (!workspace.addParts(parts)) return ClipboardActionResult.UNAVAILABLE;
      changed.run();
      return ClipboardActionResult.SUCCESS;
   }

   private static ClipboardActionResult rejectedCopy(ClipboardCopy copied) {
      if (copied instanceof ClipboardCopy.TooLarge limit) {
         return limit.limit() == Composition.Limit.WORK ? ClipboardActionResult.WORK_LIMIT : ClipboardActionResult.RESULT_LIMIT;
      }
      return ClipboardActionResult.EMPTY;
   }
}
