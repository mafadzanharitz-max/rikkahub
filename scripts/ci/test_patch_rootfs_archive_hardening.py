#!/usr/bin/env python3
"""Regression tests for the fail-closed, idempotent source replacement helper."""
import importlib.util
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).with_name("patch-rootfs-archive-hardening.py")
SPEC = importlib.util.spec_from_file_location("rootfs_archive_patch", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)

class OnceTests(unittest.TestCase):
    def test_replaces_exactly_one_original_target(self):
        self.assertEqual("before new after", MODULE.once("before old after", "old", "new", "case"))

    def test_is_idempotent_when_original_is_inside_replacement(self):
        source = "prefix new suffix"
        self.assertEqual(source, MODULE.once(source, "old", "new", "case"))

    def test_is_idempotent_when_old_text_is_contained_in_new_block(self):
        source = "prefix expanded-old-suffix"
        self.assertEqual(
            source,
            MODULE.once(source, "old", "expanded-old", "case"),
        )

    def test_replaces_when_replacement_removes_original_text(self):
        self.assertEqual("prefix new suffix", MODULE.once("prefix old suffix", "old", "new", "case"))

    def test_replaces_shrinking_patch_even_if_new_text_exists_elsewhere(self):
        source = "onProgress earlier; entries++ onProgress"
        self.assertEqual("onProgress earlier; onProgress", MODULE.once(
            source, "entries++ onProgress", "onProgress", "case"
        ))

    def test_rejects_unpatched_target_next_to_existing_replacement(self):
        with self.assertRaises(SystemExit):
            MODULE.once("new old", "old", "new", "case")

    def test_rejects_duplicate_original_targets(self):
        with self.assertRaises(SystemExit):
            MODULE.once("old old", "old", "new", "case")

    def test_rejects_missing_target_and_replacement(self):
        with self.assertRaises(SystemExit):
            MODULE.once("unrelated", "old", "new", "case")

if __name__ == "__main__":
    unittest.main()
