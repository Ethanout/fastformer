package io.github.fastformer.fastplace;

public enum FastPlaceActivity implements TranslatableText {
   NONE("fastformer.activity.none", false, false),
   BUILDING_SESSION("fastformer.activity.building_session", false, true),
   GEOMETRY_SESSION("fastformer.activity.geometry_session", false, true),
   OPERATION_SESSION("fastformer.activity.operation_session", false, true),
   PLACEMENT_TASK("fastformer.activity.placement_task", true, true),
   OPERATION_TASK("fastformer.activity.operation_task", true, true),
   RESTORE_TASK("fastformer.activity.restore_task", true, true);

   private final String translationKey;
   private final boolean task;
   private final boolean cancellable;

   FastPlaceActivity(String translationKey, boolean task, boolean cancellable) {
      this.translationKey = translationKey;
      this.task = task;
      this.cancellable = cancellable;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public boolean task() {
      return this.task;
   }

   public boolean cancellable() {
      return this.cancellable;
   }
}
