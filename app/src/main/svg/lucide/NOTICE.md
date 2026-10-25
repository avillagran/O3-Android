# Icon sources

The SVGs in this folder are converted to `res/drawable/ic_<name>.xml` at build time by
`tools/lucide-vectors` (`:app:generateOt3Icons`); `Ot3Icons` lists them by name. Files are
copied byte for byte from their source; the converter applies ot3's stroke 1.75.

Sources, in order of preference (UI_SPEC 3.8):

1. **ot3's icon folder**, `ot3:ui/assets/icons/` at `7b5b1de`.
2. **ot3's inline icons**, the runtime value of `icons["<name>"]` in `ot3:ui/js/IconAssets.js`
   at `7b5b1de`, for icons ot3 draws but keeps out of the folder. Several differ from the
   current Lucide drawing (`pin`, `git-pull-request`, `settings`, `pen-line`, `message-square`,
   `chart-no-axes-column`, `undo-2`); ot3's drawing wins (UI_SPEC rule 2).
3. **Lucide 0.564.0**, the version ot3 pins (`ot3:docs/LICENSES.md:43`), from the npm tarball
   `lucide-static-0.564.0.tgz` (registry integrity
   `sha512-SfECSvMnoIPa8ZuH4Ubtjc1ZWSxLpPy9yhHt+0DR8vvMEFWxw0gjX2INCNkIXWllFsiJDO4d9iye6cXvIxMG0Q==`,
   `package.json` version `0.564.0`), path `package/icons/<name>.svg`, for icons ot3 lacks.

| File | Source | Licence |
|---|---|---|
| `arrow-up.svg` | ot3 folder | Lucide ISC |
| `chart-no-axes-column.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `check.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `chevron-down.svg` | ot3 folder | Lucide ISC |
| `chevron-up.svg` | ot3 folder | Lucide ISC |
| `circle-alert.svg` | ot3 folder | Lucide ISC |
| `circle-check.svg` | ot3 folder | Lucide ISC |
| `circle-dashed.svg` | ot3 folder | Lucide ISC |
| `circle-dot-dashed.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `clock.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `cloud.svg` | ot3 folder | Lucide ISC |
| `copy.svg` | ot3 folder | Lucide ISC |
| `corner-up-right.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `ellipsis-vertical.svg` | ot3 folder | Lucide ISC |
| `external-link.svg` | ot3 folder | Lucide ISC |
| `eye.svg` | Lucide 0.564.0 | Lucide ISC |
| `file.svg` | ot3 folder | Lucide ISC |
| `folder-git-2.svg` | ot3 folder | Lucide ISC |
| `folder-plus.svg` | ot3 folder | Lucide ISC |
| `git-branch.svg` | ot3 folder | Lucide ISC |
| `git-merge.svg` | ot3 folder | Lucide ISC |
| `git-pull-request-closed.svg` | ot3 folder | Lucide ISC |
| `git-pull-request-draft.svg` | ot3 folder | Lucide ISC |
| `git-pull-request.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `laptop.svg` | ot3 folder | Lucide ISC |
| `list-filter.svg` | ot3 folder | Lucide ISC |
| `list-plus.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `message-circle-question.svg` | ot3 folder | Lucide ISC |
| `message-square.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `monitor.svg` | ot3 folder | Lucide ISC |
| `paperclip.svg` | ot3 folder | Lucide ISC |
| `pen-line.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `pin-off.svg` | ot3 folder | Lucide ISC |
| `pin.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `refresh-cw.svg` | ot3 folder | Lucide ISC |
| `search.svg` | ot3 folder | Lucide ISC |
| `server.svg` | ot3 folder | Lucide ISC |
| `settings.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `shield-question.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `sliders-horizontal.svg` | ot3 folder | Lucide ISC |
| `smartphone.svg` | ot3 folder | Lucide ISC |
| `square-pen.svg` | ot3 folder | Lucide ISC |
| `stop-filled.svg` | ot3 `IconAssets.js` (ot3's own glyph) | ot3 MIT |
| `t3-logo.svg` | ot3 folder (the T3 Code mark) | T3 Code MIT, mark of T3 Tools Inc. |
| `undo-2.svg` | ot3 `IconAssets.js` | Lucide ISC |
| `x.svg` | ot3 folder | Lucide ISC |

Licences: Lucide's ISC licence (with the Feather MIT notice it carries) is `LICENSE` in this
folder, copied from the same tarball. The T3 mark comes from T3 Code (MIT, Copyright (c) 2026
T3 Tools Inc., `ot3:licenses/T3-Code-MIT.txt`) and identifies T3 Code; it is not covered by
Lucide's licence. `stop-filled` is ot3's (MIT, Copyright (c) 2026 Andrés Villagrán). All of
these notices are collected in `docs/LICENSES.md` for Settings → About → Licenses.
