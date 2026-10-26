# UI-1 integrated acceptance

Integrated branch: `feat/ui-1`, merge `cffa6e4`. PR #30 targets main.
Source: ot3 `7b5b1de`, lib-t3-rust 0.3.4. Bridge SDK production
`f7d93a9ea8600991a229cadb9d189306965d4195`, required for the candidate APK.

## Evidence boundaries

The offline workspace uses the production navigation, Home, reader, ViewModels, Room
and Rust projections over deterministic synthetic server responses. It proves layout
and interaction for that content, not authenticated networking or productive catalog sync.
The live Xiaomi retained its existing VQ session after `install -r`; OT3 opened, but
Android refused automated touch injection. No system setting was changed.

Existing source comparisons and video are retained in [UI-1i](../ui-1i/README.md) and
[UI-2a](../ui-2a/README.md). Fresh combined captures below will be distinguished from
those historical artifacts.

## Concrete differences still open

| Requirement/source | Current evidence | Remaining closure |
|---|---|---|
| Home rows/sky: `T3ThreadRow.qml`, sky shaders | Native sidebar view, source shaders, prior UI-1i video | Integrated Tokyo Night/Latte captures and fresh desktop same-content comparison |
| Sidebar: `T3Reveal.qml`, `T3Motion.qml` | Permanent workspace control, nebula reveal, compact toolbar | Combined portrait/landscape keyboard and collapse video; selectable styles/pace and shelf flight |
| Reader: `T3Panel.qml`, activity/changes components | Markdown/code, tool/reasoning inspector, native row roles | History paging, rich tables/math, previews, fork/rewind/quote and full role workflows |
| Composer: `T3Composer.qml` | Basic text editor and durable outbox | Integrate UI-3 controls/queue; attachments/completions/Plan-Build need common submission builders |
| Requests: approval/question components | UI-4 increment exists separately | Integrate and verify real request flows and remaining secret/plan workflows |
| Authorized catalog | Isolated Android/JNI/QUIC/two-real-T3 closure | NODE/VQ productive discovery, offline recovery and route changes |
| Local notifications | API24/26/33/36 builder tests | Pin 0.3.5, service/settings/leases, promoted chip and lockscreen proof on API36.1 |

The reader and the full application are **not** declared desktop parity.
