package io.github.fastformer.client.input.state;

import java.util.Objects;
import io.github.fastformer.fastplace.session.IdleSessionState;
import io.github.fastformer.fastplace.session.SessionActivationMode;

/** Owns input routing, cancellation, and the lifetime of a mouse gesture. */
public final class ClientInputStateMachine {
   private State state = State.IDLE;
   private long generation;
   private long gesture;
   private int gestureButton = -1;
   private State submissionOrigin;
   private final ClientRequestTracker requests = new ClientRequestTracker();
   private final java.util.function.BiConsumer<State, State> onTransition;
   private SessionActivationMode activationMode;

   public ClientInputStateMachine() { this((previous, next) -> { }); }

   public ClientInputStateMachine(java.util.function.BiConsumer<State, State> onTransition) {
      this(SessionActivationMode.AUTOMATIC, onTransition);
   }

   public ClientInputStateMachine(SessionActivationMode mode, java.util.function.BiConsumer<State, State> onTransition) {
      this.onTransition = Objects.requireNonNull(onTransition);
      this.activationMode = Objects.requireNonNull(mode);
      this.state = mode == SessionActivationMode.MANUAL ? State.IDLE_MANUAL : State.IDLE;
   }

   public IdleSessionState idleState() {
      if (state.idleSuspended()) return state.idleState();
      return activationMode == SessionActivationMode.MANUAL ? IdleSessionState.MANUAL_FF : IdleSessionState.READY;
   }

   public State state() {
      return state;
   }

   public boolean ownsQuickShapeSubmission() {
      return state == State.BUILDING || state == State.SUBMITTING
         && (submissionOrigin == State.BUILDING || submissionOrigin == State.IDLE);
   }

   public long generation() {
      return generation;
   }

   /** Cancel stays in effect until the session and its recovery task end. */
   public void observe(State observed) {
      onEvent(new ClientSemanticEvent.Observe(observed));
   }

   public boolean cancel() {
      return onEvent(ClientSemanticEvent.Cancel.INSTANCE) instanceof InteractionTransition.Switch;
   }

   public boolean submit(long requestId) {
      return onEvent(new ClientSemanticEvent.Submit.Placement(requestId)) instanceof InteractionTransition.Switch;
   }

   public boolean startPlacement(long requestId) {
      return onEvent(new ClientSemanticEvent.StartPlacement(requestId)) instanceof InteractionTransition.Switch;
   }

   public boolean awaitsPlacementRequest(long requestId) {
      return state == State.SUBMITTING && requests.current() instanceof ClientSemanticEvent.Submit.Placement placement
         && placement.requestId() == requestId;
   }

   public boolean submit(java.util.UUID transferId) {
      return onEvent(new ClientSemanticEvent.Submit.Workspace(transferId)) instanceof InteractionTransition.Switch;
   }

   public boolean completeSubmission(java.util.UUID transferId, SubmissionEvent event, State observed) {
      Objects.requireNonNull(event, "event");
      return transferId != null && onEvent(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId), event, observed
      )) instanceof InteractionTransition.Switch;
   }

   public boolean completeSubmission(long requestId, SubmissionEvent event, State observed) {
      return onEvent(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(requestId), event, observed
      )) instanceof InteractionTransition.Switch;
   }

   /** Returns a decision without changing state, gestures, or request ownership. */
   public InteractionTransition inspect(ClientSemanticEvent event) {
      return this.state.onEvent(Objects.requireNonNull(event, "event"), this.requests.current(), this.submissionOrigin, activationMode);
   }

   /** The client owner calls this once for each delivered event. */
   public InteractionTransition onEvent(ClientSemanticEvent event) {
      SessionActivationMode previousMode = activationMode;
      if (event instanceof ClientSemanticEvent.ConfigureActivation configure) {
         if (configure.mode() == activationMode) return InteractionTransition.Stay.INSTANCE;
         activationMode = Objects.requireNonNull(configure.mode());
      }
      InteractionTransition result = inspect(event);
      if (result instanceof InteractionTransition.Switch change) {
         apply(change);
      } else if (previousMode != activationMode) {
         onTransition.accept(state, state);
      }
      return result;
   }

   public Dispatch dispatch(InputKind input) {
      return state.dispatch(Objects.requireNonNull(input, "input"));
   }

   public boolean routesToVanilla(InputKind input) {
      return dispatch(input) == Dispatch.VANILLA || dispatch(input) == Dispatch.VANILLA_ONLY;
   }

   public long beginGesture() {
      return beginGesture(-1);
   }

   public long beginGesture(int button) {
      if (dispatch(InputKind.POINTER) == Dispatch.BLOCKED || dispatch(InputKind.POINTER) == Dispatch.VANILLA_ONLY) {
         return 0L;
      }
      gesture = ++generation;
      gestureButton = button;
      return gesture;
   }

   public boolean finishGesture(int button, long token) {
      if (!accepts(token) || gestureButton != button) {
         return false;
      }
      gesture = 0L;
      gestureButton = -1;
      return true;
   }

   public boolean accepts(long token) {
      return token != 0L && token == gesture;
   }

   public boolean accepts(int button, long token) {
      return accepts(token) && this.gestureButton == button;
   }

   public void reset() {
      onEvent(ClientSemanticEvent.Reset.INSTANCE);
   }

   private void apply(InteractionTransition.Switch change) {
      State previous = this.state;
      previous.exit(this, change.cause());
      this.state = change.target();
      this.state.enter(this, previous, change);
      onTransition.accept(previous, this.state);
   }

   private void invalidateGesture() {
      generation++;
      gesture = 0L;
      gestureButton = -1;
   }

   public enum State {
      IDLE(Dispatch.VANILLA, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.VANILLA, Dispatch.BLOCKED, false, true),
      IDLE_MANUAL(Dispatch.VANILLA_ONLY, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, false),
      IDLE_WAITING_FOR_FAR_ENTRY(Dispatch.VANILLA_ONLY, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, false),
      IDLE_WAITING_FOR_FAR_EXIT(Dispatch.VANILLA_ONLY, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, false),
      QUICK_REPLACE(Dispatch.VANILLA, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, true),
      BUILDING(Dispatch.BUILDING, Dispatch.BUILDING, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, true, true),
      GEOMETRY(Dispatch.GEOMETRY, Dispatch.GEOMETRY, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, true, true),
      SELECTING(Dispatch.OPERATION, Dispatch.BLOCKED, Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.CANCEL, false, true),
      ADJUSTING(Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.CANCEL, true, true),
      SUBMITTING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, false, false),
      PLACING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, false, true),
      RESTORING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, true),
      CANCELLING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, false);

      private final Dispatch regular;
      private final Dispatch submit;
      private final Dispatch createSelection;
      private final Dispatch pasteWorkspace;
      private final Dispatch cancel;
      private final boolean canSubmit;
      private final boolean serverObservable;

      State(
         Dispatch regular,
         Dispatch submit,
         Dispatch createSelection,
         Dispatch pasteWorkspace,
         Dispatch cancel,
         boolean canSubmit,
         boolean serverObservable
      ) {
         this.regular = regular;
         this.submit = submit;
         this.createSelection = createSelection;
         this.pasteWorkspace = pasteWorkspace;
         this.cancel = cancel;
         this.canSubmit = canSubmit;
         this.serverObservable = serverObservable;
      }

      InteractionTransition onEvent(
         ClientSemanticEvent event, ClientSemanticEvent.Submit activeRequest, State submissionOrigin, SessionActivationMode mode
      ) {
         return switch (event) {
            case ClientSemanticEvent.ConfigureActivation configure -> this == IDLE || idleSuspended()
               ? idleTransition(configure.mode() == SessionActivationMode.MANUAL ? IdleSessionState.MANUAL_VANILLA : IdleSessionState.READY)
               : InteractionTransition.Stay.INSTANCE;
            case ClientSemanticEvent.EnterManual ignored -> this == IDLE_MANUAL
               ? idleTransition(IdleSessionState.READY) : new InteractionTransition.Rejected(InteractionTransition.Rejection.INPUT_BLOCKED);
            case ClientSemanticEvent.SuspendIdle suspend -> this == IDLE
               ? idleTransition(mode == SessionActivationMode.MANUAL ? IdleSessionState.MANUAL_VANILLA : IdleSessionState.suspend(suspend.withinFar()))
               : new InteractionTransition.Rejected(InteractionTransition.Rejection.INPUT_BLOCKED);
            case ClientSemanticEvent.ObserveReach reach -> idleSuspended()
               ? idleTransition(idleState().observeDistance(reach.withinFar())) : InteractionTransition.Stay.INSTANCE;
            case ClientSemanticEvent.ResumeIdle ignored -> idleSuspended()
               ? idleTransition(IdleSessionState.READY) : InteractionTransition.Stay.INSTANCE;
            case ClientSemanticEvent.Observe observation ->
               observation.state() == this || !acceptsObservation(observation.state())
                  ? InteractionTransition.Stay.INSTANCE
                  : new InteractionTransition.Switch(observation.state(), InteractionTransition.Cause.OBSERVATION);
            case ClientSemanticEvent.Cancel ignored -> this.cancel == Dispatch.CANCEL
               ? new InteractionTransition.Switch(CANCELLING, InteractionTransition.Cause.CANCEL)
               : new InteractionTransition.Rejected(InteractionTransition.Rejection.INPUT_BLOCKED);
            case ClientSemanticEvent.Reset ignored ->
               new InteractionTransition.Switch(mode == SessionActivationMode.MANUAL ? IDLE_MANUAL : IDLE, InteractionTransition.Cause.RESET);
            case ClientSemanticEvent.Submit request -> submitTransition(request, activeRequest);
            case ClientSemanticEvent.StartPlacement start -> this == IDLE
               ? validatedSubmission(new ClientSemanticEvent.Submit.Placement(start.requestId()), activeRequest)
               : new InteractionTransition.Rejected(InteractionTransition.Rejection.INPUT_BLOCKED);
            case ClientSemanticEvent.SubmissionCompleted completion ->
               completionTransition(completion, activeRequest, submissionOrigin);
         };
      }

      public IdleSessionState idleState() {
         return switch (this) {
            case IDLE_MANUAL -> IdleSessionState.MANUAL_VANILLA;
            case IDLE_WAITING_FOR_FAR_ENTRY -> IdleSessionState.WAITING_FOR_FAR_ENTRY;
            case IDLE_WAITING_FOR_FAR_EXIT -> IdleSessionState.WAITING_FOR_FAR_EXIT;
            default -> IdleSessionState.READY;
         };
      }

      public boolean idleSuspended() { return idleState().suspended(); }

      private InteractionTransition idleTransition(IdleSessionState state) {
         State next = switch (state) {
            case READY, MANUAL_FF -> IDLE;
            case MANUAL_VANILLA -> IDLE_MANUAL;
            case WAITING_FOR_FAR_ENTRY -> IDLE_WAITING_FOR_FAR_ENTRY;
            case WAITING_FOR_FAR_EXIT -> IDLE_WAITING_FOR_FAR_EXIT;
         };
         return next == this ? InteractionTransition.Stay.INSTANCE
            : new InteractionTransition.Switch(next, InteractionTransition.Cause.IDLE_HANDOFF);
      }

      private InteractionTransition submitTransition(
         ClientSemanticEvent.Submit request, ClientSemanticEvent.Submit activeRequest
      ) {
         if (!this.canSubmit && !(this == SELECTING && request instanceof ClientSemanticEvent.Submit.Workspace)) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.INPUT_BLOCKED);
         }
         return validatedSubmission(request, activeRequest);
      }

      private InteractionTransition validatedSubmission(
         ClientSemanticEvent.Submit request, ClientSemanticEvent.Submit activeRequest
      ) {
         if (activeRequest != null) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.REQUEST_ACTIVE);
         }
         if (!ClientRequestTracker.valid(request)) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.INVALID_REQUEST);
         }
         return new InteractionTransition.Switch(SUBMITTING, InteractionTransition.Cause.SUBMIT, request);
      }

      private InteractionTransition completionTransition(
         ClientSemanticEvent.SubmissionCompleted completion,
         ClientSemanticEvent.Submit activeRequest,
         State submissionOrigin
      ) {
         if (this != SUBMITTING || !completion.request().equals(activeRequest)) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.STALE_REQUEST);
         }
         State next = Objects.requireNonNull(submissionOrigin, "submissionOrigin")
            .completeSubmission(completion.outcome(), completion.observed());
         return new InteractionTransition.Switch(next, InteractionTransition.Cause.SUBMISSION_COMPLETED);
      }

      private void exit(ClientInputStateMachine owner, InteractionTransition.Cause reason) {
         owner.invalidateGesture();
         owner.requests.clear();
         owner.submissionOrigin = null;
      }

      private void enter(ClientInputStateMachine owner, State previous, InteractionTransition.Switch change) {
         if (this == SUBMITTING) {
            // The decision validates the request before exit releases the previous phase.
            owner.requests.begin(change.submission());
            owner.submissionOrigin = previous;
         }
      }

      Dispatch dispatch(InputKind input) {
         return switch (input) {
            case KEY, POINTER, INTERACTION, SCROLL -> regular;
            case SUBMIT -> submit;
            case CREATE_SELECTION -> createSelection;
            case PASTE_WORKSPACE -> pasteWorkspace;
            case CANCEL -> cancel;
         };
      }

      boolean serverObservable() {
         return serverObservable;
      }

      boolean acceptsObservation(State observed) {
         if (idleSuspended() && observed == IDLE) return false;
         if (this == SUBMITTING) {
            return false;
         }
         return this != CANCELLING || observed == IDLE;
      }

      State completeSubmission(SubmissionEvent event, State observed) {
         Objects.requireNonNull(event, "event");
         if (event != SubmissionEvent.SUCCEEDED) {
            return this;
         }
         Objects.requireNonNull(observed, "observed");
         if (!observed.serverObservable()) {
            throw new IllegalArgumentException("Successful submission requires an observed server state");
         }
         return observed;
      }
   }

   public enum InputKind {
      KEY,
      SUBMIT,
      POINTER,
      INTERACTION,
      SCROLL,
      CREATE_SELECTION,
      PASTE_WORKSPACE,
      CANCEL
   }

   public enum SubmissionEvent {
      SUCCEEDED,
      FAILED,
      EXPIRED
   }

   public enum Dispatch {
      BLOCKED,
      CANCEL,
      VANILLA,
      VANILLA_ONLY,
      BUILDING,
      GEOMETRY,
      OPERATION
   }
}
