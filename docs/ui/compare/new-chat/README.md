# New chat project selection and launch

The Home new-thread action previously opened a NotYetScreen placeholder. It now
lists every project in the saved environments' authorized shells and opens a draft
for that exact local environment id and project id, without needing an existing
thread. Identical project names remain distinguishable by environment and path.
Offline and read-only rows remain visible and cannot create threads.

The draft keeps text, model selection and the caller-selected thread id in saved
state. Rust supplies project defaults/catalogs and validates the typed launch. The
first message is included in the atomic launch request. An unknown outcome remains
in the existing outbox as a typed envelope, so recovery uses the same function,
command id and thread id; legacy message commands still use dispatchCommand.
Pending launches are recovered by environment/project even after closing the draft;
the restored form shows the original request instead of creating a second thread.

Five Compose tests cover host scoping, disabled rows, search/empty states and send
eligibility. Four persistence tests cover launch, exact recovery after sender
recreation, rejection/offline and an unexpected returned identity. A real JNI /
WebSocket fixture checks the selected project/model and atomic initial message.
Dark/light project and draft goldens are inspected. See verification.json for final
checks and installation evidence. No productive thread was created for acceptance.

Remaining UI-5 gaps: U32 source/sibling fallback and shared search/routing recipe,
branch/worktree choices, Add Project, automatic environment selection and favorite
changes. The new flow uses the current checkout and explicit model choices.

The separate missing-host diagnosis found no selected native bridge and zero
bridge-managed environments on the known phone. Do not infer that this UI update
makes all bridge peers discoverable or authorizes sessions for them.
