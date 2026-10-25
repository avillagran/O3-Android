# Licences of bundled third-party material

This list feeds Settings → About → Licenses (UI_SPEC 4.9). Each entry names what the APK
ships, where it came from and the notice that must travel with it. Library dependencies
(AndroidX, Kotlin, lib-t3-rust) are collected separately when that screen is built.

## Lucide icons

- **What:** the line icons in `app/src/main/svg/lucide/` (all but `t3-logo.svg` and
  `stop-filled.svg`), converted to `res/drawable/ic_*.xml` at build time. Per-file sources
  are in that folder's `NOTICE.md`.
- **Version:** Lucide 0.564.0, the version ot3 pins (`ot3:docs/LICENSES.md:43`), through ot3's
  own copies (`ot3:ui/assets/icons/`, `ot3:ui/js/IconAssets.js`) or the `lucide-static@0.564.0`
  npm tarball.
- **Licence:** ISC, with an MIT notice for the portions derived from Feather. Source:
  https://lucide.dev/. Full text, as shipped in the tarball (`app/src/main/svg/lucide/LICENSE`):

```
ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2026 as part of Feather (MIT). All other copyright (c) for Lucide are held by Lucide Contributors 2026.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

---

The MIT License (MIT) (for portions derived from Feather)

Copyright (c) 2013-2026 Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## T3 mark

- **What:** `app/src/main/svg/lucide/t3-logo.svg` (`ic_t3_logo`), copied from
  `ot3:ui/assets/icons/t3-logo.svg`, which takes it from T3 Code.
- **Notice:** T3 Code is MIT licensed, Copyright (c) 2026 T3 Tools Inc.
  (https://github.com/pingdotgg/t3code; `ot3:licenses/T3-Code-MIT.txt`). The mark identifies
  T3 Code, the server this app connects to; it is used for that purpose only and no
  endorsement is implied.

## ot3

- **What:** `stop-filled.svg` (ot3's own glyph, `ot3:ui/js/IconAssets.js:5`), the strings
  in `app/src/main/res/values*/strings_ot3.xml`, and the visual design this app follows.
- **Licence:** MIT, Copyright (c) 2026 Andrés Villagrán
  (https://github.com/avillagran/omarchy-t3-code).
- **Translations:** the string catalog is `ot3:translations/i18n.json` (20 locales; format
  [omarchy-i18n](https://github.com/avillagran/omarchy-i18n)). All non-English values are
  machine-authored drafts, not human-approved translations (`ot3:translations/COVERAGE.md:27`);
  the app inherits that status and says so in About.
