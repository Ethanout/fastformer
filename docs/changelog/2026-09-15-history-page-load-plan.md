# History page load plan

- `HistoryPageLoadPlan` owns durable history page selection and the request metadata used after the page loads.
- `WorldHistoryManager` keeps async storage calls, batch merge, and all world-write behavior.
- Tests cover in-memory completion, missing durable batches, configured page limits, and redo selection.
