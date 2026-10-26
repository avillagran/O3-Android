"""Rules and check mode of tools/import-ot3-strings. Run: python3 -I tools/tests/test_import_ot3_strings.py

Each test builds a small fake AAR (a zip with assets/lib-t3-rust/i18n.json and
META-INF/lib-t3-rust.properties) and a fake res/ tree, and points the importer at them.
"""

import contextlib
import io
import json
import tempfile
import unittest
import zipfile
from importlib.machinery import SourceFileLoader
from importlib.util import module_from_spec, spec_from_loader
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "import-ot3-strings"
_loader = SourceFileLoader("import_ot3_strings", str(SCRIPT))
_spec = spec_from_loader(_loader.name, _loader)
assert _spec is not None
importer = module_from_spec(_spec)
_loader.exec_module(importer)

ENGLISH = {
    "Close": "Close",
    "{activity} ({count})": "{activity} ({count})",
    "Retry 100% of it?": "Retry 100% of it?",
}


def catalog(**overrides):
    """Every locale with the English values, except the per-locale overrides given."""
    result = {locale: dict(ENGLISH) for locale in importer.LOCALES}
    for locale, values in overrides.items():
        result[locale].update(values)
    return result


class ImporterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.res = root / "res"
        (self.res / "values").mkdir(parents=True)
        self.tsv = self.res / "ot3-strings.tsv"
        self.tsv.write_text("# comment\n"
                            "ot3_close\tClose\n"
                            "ot3_activity_count\t{activity} ({count})\n"
                            "ot3_retry_all\tRetry 100% of it?\n", encoding="utf-8")
        self.aar = root / "libs" / "lib-t3-rust-android.aar"
        self.aar.parent.mkdir()
        self.lock = root / "libs" / "lib-t3-rust.lock"
        self.lock.write_text("tag=v0.3.4\nsha256=" + "0" * 64 + "\n", encoding="utf-8")
        self.saved = {name: getattr(importer, name) for name in ("REPO", "RES", "TSV", "HAND_WRITTEN", "AAR", "LOCK")}
        importer.REPO = root
        importer.RES = self.res
        importer.TSV = self.tsv
        importer.HAND_WRITTEN = (self.res / "values" / "strings.xml",)
        importer.AAR = self.aar
        importer.LOCK = self.lock

    def tearDown(self):
        for name, value in self.saved.items():
            setattr(importer, name, value)
        self.tmp.cleanup()

    def write_aar(self, data, version="0.3.4"):
        with zipfile.ZipFile(self.aar, "w") as archive:
            archive.writestr("classes.jar", b"")
            archive.writestr(importer.AAR_PROPERTIES_ENTRY, f"version={version}\nsource={'a' * 40}\n")
            if data is not None:
                archive.writestr(importer.CATALOG_ENTRY, json.dumps(data, ensure_ascii=False))

    def run_importer(self, *argv):
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            code = importer.main(list(argv))
        return code, err.getvalue()

    def output(self, folder):
        return (self.res / folder / importer.OUTPUT_NAME).read_text(encoding="utf-8")

    def test_placeholders_become_positional_in_english_order(self):
        self.write_aar(catalog(es={"{activity} ({count})": "({count}) {activity}"}))
        self.assertEqual(self.run_importer(), (0, ""))
        self.assertIn('<string name="ot3_activity_count">%1$s (%2$s)</string>', self.output("values"))
        self.assertIn('<string name="ot3_activity_count">(%2$s) %1$s</string>', self.output("values-es"))

    def test_escaping_and_literal_percent(self):
        self.write_aar(catalog())
        self.assertEqual(self.run_importer()[0], 0)
        english = self.output("values")
        self.assertIn('<string name="ot3_retry_all" formatted="false">Retry 100% of it\\?</string>', english)
        self.assertIn('<string name="ot3_close">Close</string>', english)

    def test_header_names_the_aar_asset_and_lib_version(self):
        self.write_aar(catalog())
        self.run_importer()
        header = self.output("values").splitlines()[1]
        self.assertIn("from lib-t3-rust 0.3.4, AAR asset assets/lib-t3-rust/i18n.json (sha256 ", header)

    def test_norwegian_is_nb_and_arabic_is_marked_rtl(self):
        self.write_aar(catalog())
        self.run_importer()
        self.assertFalse((self.res / "values-no").exists())
        self.assertIn("RTL", self.output("values-ar"))
        self.assertTrue((self.res / "values-nb" / importer.OUTPUT_NAME).is_file())

    def test_check_mode_writes_nothing_and_fails_when_stale(self):
        self.write_aar(catalog())
        code, err = self.run_importer("--check")
        self.assertEqual(code, 1)
        self.assertIn("out of date", err)
        self.assertFalse((self.res / "values" / importer.OUTPUT_NAME).exists())

        self.assertEqual(self.run_importer()[0], 0)
        self.assertEqual(self.run_importer("--check"), (0, ""))

        self.write_aar(catalog(fr={"Close": "Quitter"}))
        before = self.output("values-fr")
        code, err = self.run_importer("--check")
        self.assertEqual(code, 1)
        self.assertIn("values-fr/strings_ot3.xml", err)
        self.assertEqual(self.output("values-fr"), before)

    def test_aar_must_match_the_lock(self):
        self.write_aar(catalog(), version="0.3.5")
        code, err = self.run_importer("--check")
        self.assertEqual(code, 1)
        self.assertIn("pins v0.3.4", err)

    def test_aar_without_the_catalog_fails(self):
        self.write_aar(None)
        code, err = self.run_importer()
        self.assertEqual(code, 1)
        self.assertIn("has no assets/lib-t3-rust/i18n.json", err)

    def test_missing_aar_fails(self):
        code, err = self.run_importer()
        self.assertEqual(code, 1)
        self.assertIn("fetch-or-build-lib.sh", err)

    def test_translation_placeholders_must_match_english(self):
        self.write_aar(catalog(de={"{activity} ({count})": "{activity} ({anzahl})"}))
        code, err = self.run_importer()
        self.assertEqual(code, 1)
        self.assertIn("de placeholders", err)

    def test_collision_with_hand_written_strings_fails(self):
        (self.res / "values" / "strings.xml").write_text(
            '<resources><string name="ot3_close">Close</string></resources>', encoding="utf-8")
        self.write_aar(catalog())
        code, err = self.run_importer()
        self.assertEqual(code, 1)
        self.assertIn("ot3_close: already defined", err)


if __name__ == "__main__":
    unittest.main()
