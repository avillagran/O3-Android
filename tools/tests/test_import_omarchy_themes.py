"""Failure modes and rules of tools/import-omarchy-themes. Run: python3 -I tools/tests/test_import_omarchy_themes.py

The goldens and the 22 real themes are checked by the app's JVM test
(OmarchyThemeCatalogTest) against the committed JSON.
"""

import json
import tempfile
import unittest
from importlib.machinery import SourceFileLoader
from importlib.util import module_from_spec, spec_from_loader
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "import-omarchy-themes"
_loader = SourceFileLoader("import_omarchy_themes", str(SCRIPT))
_spec = spec_from_loader(_loader.name, _loader)
assert _spec is not None
importer = module_from_spec(_spec)
_loader.exec_module(importer)

DARK = """mode = "dark"
background = "#1a1b26"
foreground = "#a9b1d6"
accent = "#7aa2f7"
red = "#f7768e"
"""


class ImporterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.themes = self.root / "themes"
        self.themes.mkdir()
        self.output = self.root / "out.json"

    def tearDown(self):
        self.tmp.cleanup()

    def theme(self, name, text):
        (self.themes / name).mkdir()
        (self.themes / name / "colors.toml").write_text(text)

    def run_importer(self, *extra):
        return importer.main([str(self.themes), "--output", str(self.output), "--omarchy-version", "9.9", *extra])

    def test_missing_required_key_fails(self):
        self.theme("broken", DARK.replace('red = "#f7768e"\n', ""))
        self.assertEqual(self.run_importer(), 1)
        self.assertFalse(self.output.exists())

    def test_numbered_fallback_satisfies_a_required_key(self):
        self.theme("old", DARK.replace('red = "#f7768e"', 'color1 = "#AA0000"'))
        self.assertEqual(self.run_importer(), 0)
        theme = json.loads(self.output.read_text())["themes"][0]
        self.assertEqual(theme["roles"]["failed"], "#aa0000")

    def test_unparsable_required_value_fails(self):
        self.theme("rgba", DARK.replace('"#7aa2f7"', '"rgba(122,162,247,1)"'))
        self.assertEqual(self.run_importer(), 1)

    def test_eight_digit_hex_is_not_accepted(self):
        self.theme("alpha", DARK.replace('"#7aa2f7"', '"#7aa2f7ff"'))
        self.assertEqual(self.run_importer(), 1)

    def test_mode_mismatch_fails(self):
        self.theme("liar", DARK.replace('mode = "dark"', 'mode = "light"'))
        self.assertEqual(self.run_importer(), 1)

    def test_fallbacks_and_palette(self):
        self.theme("sparse", DARK)
        self.assertEqual(self.run_importer(), 0)
        theme = json.loads(self.output.read_text())["themes"][0]
        roles = theme["roles"]
        for role in ("working", "done", "waiting", "warning", "input"):
            self.assertEqual(roles[role], "#7aa2f7", role)
        self.assertEqual(theme["base"]["muted"], "#a9b1d6")  # no muted, no color8: foreground
        self.assertEqual(theme["projectPalette"], ["#7aa2f7", "#f7768e"])
        self.assertEqual(theme["name"], "Sparse")

    def test_warning_falls_back_to_yellow_before_accent(self):
        self.theme("noorange", DARK + 'yellow = "#e0af68"\n')
        self.run_importer()
        self.assertEqual(json.loads(self.output.read_text())["themes"][0]["roles"]["warning"], "#e0af68")

    def test_rerun_keeps_generated_at(self):
        self.theme("tokyo", DARK)
        self.run_importer("--generated-at", "2000-01-01T00:00:00Z")
        first = self.output.read_text()
        self.run_importer()
        self.assertEqual(self.output.read_text(), first)

    def test_round_half_to_even_on_exact_ties(self):
        # tokyo-night divider green: 27 + (177 - 27) * 0.09 = 40.5 -> 40 (tokens.md golden #272836).
        self.assertEqual(importer.composite("#a9b1d6", "#1a1b26", "0.09"), "#272836")
        # catppuccin-latte rowSelected blue: 245 + (105 - 245) * 0.075 = 234.5 -> 234 (#e3e5ea).
        self.assertEqual(importer.composite("#4c4f69", "#eff1f5", "0.075"), "#e3e5ea")


if __name__ == "__main__":
    unittest.main()
