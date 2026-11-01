# Shared Desktop thread rows

All sidebar layouts now render `DesktopThreadRow`: pinned, working, waiting,
settled/snoozed, parents and nested subagent children. The old card, slim and
parent-only renderers were removed, including project initials, server icons,
combined project/host labels and the extra branch/PR line.

The shared structure is title, inline PR count, pin, subagent expander and age,
then a status dot, project name and monospace host badge. Tree indentation stays
16dp per level. Project long press, row actions, PR navigation, pin actions and
expansion callbacks retain their ownership. Title truncation and selected-row
marquee follow the existing preference and motion policy.

Every row reserves the same 48dp action line, even without actions. This preserves
Android touch targets for PR/expansion and prevents badges from growing rows.
The metadata line grows with font scale; all variants have equal height at each
scale. Sober suppresses the wash, host strip and aura; selected rows keep their
accent and outline. Vivid uses the project gradient, host/project strip and
existing state effects, respecting project-colour preferences and child/shelf
states.

## Regression coverage

The row gallery asserts equal measured heights across seven variants, including
PR-free rows, pins, collapsed/expanded parents, working/waiting/settled states
and nested children. Both themes have a 320dp/160% gallery for sober and vivid.
Classic, Focus and Projects also have 320dp/160% whole-sidebar captures for both
styles and themes. Existing catalog and sidebar goldens are intentionally updated.

## Device limitation

No device tests were run. T3 `device_list` returns “Agent device access is turned
off for this environment”. ADB enumeration showed only the physical phone and no
running emulator. Local AVD definitions exist, but the required
`headless-android-emulator-host` skill is absent from this environment, so no AVD
was started through the CLI. The phone and other checkout files were not used.
