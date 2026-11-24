#!/usr/bin/env python3
"""Standalone regression tests for verify-armv7-elf.py (no APK build required)."""

from __future__ import annotations

import importlib.util
import struct
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("verify-armv7-elf.py")
SPEC = importlib.util.spec_from_file_location("verify_armv7_elf", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
ELF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ELF)

REQUIRED = sorted(ELF.REQUIRED_LIBRARIES)


def uleb(value: int) -> bytes:
    out = bytearray()
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def arm_attributes(*architectures: int) -> bytes:
    subsections = bytearray()
    for architecture in architectures:
        # Tag_File, followed by its length field and Tag_CPU_arch value.
        payload = uleb(ELF.TAG_CPU_ARCH) + uleb(architecture)
        subsection_length = 4 + len(payload)
        subsections += uleb(ELF.TAG_FILE)
        subsections += struct.pack("<I", subsection_length)
        subsections += payload
    vendor = b"aeabi\0" + bytes(subsections)
    return b"A" + struct.pack("<I", 4 + len(vendor)) + vendor


def make_elf(*architectures: int) -> bytes:
    names = b"\0.shstrtab\0.ARM.attributes\0"
    shstr_offset = ELF.ELF_HEADER_SIZE + 3 * ELF.ELF_SECTION_HEADER_SIZE
    attr_offset = shstr_offset + len(names)
    attrs = arm_attributes(*architectures)

    header = bytearray(ELF.ELF_HEADER_SIZE)
    header[:7] = b"\x7fELF" + bytes((ELF.ELFCLASS32, ELF.ELFDATA2LSB, 1))
    struct.pack_into("<HHI", header, 16, ELF.ET_DYN, ELF.EM_ARM, 1)
    struct.pack_into("<I", header, 32, ELF.ELF_HEADER_SIZE)
    struct.pack_into("<H", header, 40, ELF.ELF_HEADER_SIZE)
    struct.pack_into("<HHH", header, 46, ELF.ELF_SECTION_HEADER_SIZE, 3, 1)

    sections = bytearray(3 * ELF.ELF_SECTION_HEADER_SIZE)
    # Null section is all zeroes.
    struct.pack_into("<IIIIIIIIII", sections, 40,
                     1, ELF.SHT_STRTAB, 0, 0, shstr_offset, len(names), 0, 0, 1, 0)
    struct.pack_into("<IIIIIIIIII", sections, 80,
                     11, 0x70000003, 0, 0, attr_offset, len(attrs), 0, 0, 1, 0)
    return bytes(header + sections + names + attrs)


def make_elf_with_dynsym(symbols: list[str]) -> bytes:
    """Create a minimal ELF32 ARM shared object with a .dynsym and .dynstr section."""
    # Build .dynstr
    dynstr = bytearray(b"\0")  # First entry is empty
    for sym in symbols:
        dynstr.extend(sym.encode("ascii"))
        dynstr.append(0)

    # Build .dynsym (16 bytes per entry)
    SYM_ENTRY_SIZE = 16
    dynsym = bytearray(SYM_ENTRY_SIZE)  # Null entry
    for i, sym in enumerate(symbols, 1):
        # st_name = offset in dynstr
        st_name = dynstr.find(sym.encode("ascii") + b"\0")
        struct.pack_into("<IBBHHI", dynsym, len(dynsym),
                         st_name, 0, 0, 0x12, 0, 0)  # STB_GLOBAL (1) << 4 | STT_FUNC (2)

    # Section headers: null, .dynsym, .dynstr, .shstrtab, .ARM.attributes
    shstrtab = b"\0.dynsym\0.dynstr\0.shstrtab\0.ARM.attributes\0"
    num_sections = 5
    sh_offset = ELF.ELF_HEADER_SIZE
    sh_size = num_sections * ELF.ELF_SECTION_HEADER_SIZE
    dynsym_offset = sh_offset + sh_size
    dynstr_offset = dynsym_offset + len(dynsym)
    attr_offset = dynstr_offset + len(dynstr)
    names_offset = attr_offset
    names = b"\0.shstrtab\0.dynsym\0.dynstr\0.ARM.attributes\0"

    header = bytearray(ELF.ELF_HEADER_SIZE)
    header[:7] = b"\x7fELF" + bytes((ELF.ELFCLASS32, ELF.ELFDATA2LSB, 1))
    struct.pack_into("<HHI", header, 16, ELF.ET_DYN, ELF.EM_ARM, 1)
    struct.pack_into("<I", header, 32, sh_offset)
    struct.pack_into("<H", header, 40, ELF.ELF_HEADER_SIZE)
    struct.pack_into("<HHH", header, 46, ELF.ELF_SECTION_HEADER_SIZE, num_sections, 3)  # shstrndx = 3

    sections = bytearray(sh_size)
    # [0] NULL
    # [1] .dynsym
    struct.pack_into("<IIIIIIIIII", sections, ELF.ELF_SECTION_HEADER_SIZE,
                     names.find(b".dynsym\0") + 1, 11, 2, 0, dynsym_offset, len(dynsym), 2, 16, 4, 0)
    # [2] .dynstr
    struct.pack_into("<IIIIIIIIII", sections, 2 * ELF.ELF_SECTION_HEADER_SIZE,
                     names.find(b".dynstr\0") + 1, 3, 0, 0, dynstr_offset, len(dynstr), 0, 1, 0, 0)
    # [3] .shstrtab
    struct.pack_into("<IIIIIIIIII", sections, 3 * ELF.ELF_SECTION_HEADER_SIZE,
                     names.find(b".shstrtab\0") + 1, 3, 0, 0, names_offset, len(names), 0, 1, 0, 0)
    # [4] .ARM.attributes
    attrs = arm_attributes(ELF.ARMV7_ARCH)
    struct.pack_into("<IIIIIIIIII", sections, 4 * ELF.ELF_SECTION_HEADER_SIZE,
                     names.find(b".ARM.attributes\0") + 1, 0, 0, 0, attr_offset, len(attrs), 0, 1, 0, 0)

    return bytes(header + sections + dynsym + dynstr + attrs + names)


class ElfParserTests(unittest.TestCase):
    def test_accepts_armv7_shared_object(self):
        self.assertEqual(ELF.ARMV7_ARCH, ELF.arm_cpu_arch(make_elf(ELF.ARMV7_ARCH), "fixture.so"))

    def test_rejects_non_armv7_architecture(self):
        self.assertEqual(9, ELF.arm_cpu_arch(make_elf(9), "fixture.so"))

    def test_rejects_conflicting_file_architecture_attributes(self):
        with self.assertRaisesRegex(ValueError, "conflicting AEABI Tag_CPU_arch"):
            ELF.arm_cpu_arch(make_elf(ELF.ARMV7_ARCH, 9), "fixture.so")

    def test_rejects_truncated_elf_header(self):
        with self.assertRaisesRegex(ValueError, "truncated ELF header"):
            ELF.arm_cpu_arch(b"\x7fELF", "fixture.so")

    def test_rejects_section_table_out_of_bounds(self):
        elf = bytearray(make_elf(ELF.ARMV7_ARCH))
        struct.pack_into("<I", elf, 32, len(elf) + 100)
        with self.assertRaisesRegex(ValueError, "out of bounds"):
            ELF.arm_cpu_arch(bytes(elf), "fixture.so")

    def test_rejects_missing_arm_attributes(self):
        elf = bytearray(make_elf(ELF.ARMV7_ARCH))
        # Rename .ARM.attributes so the section cannot be discovered.
        elf[ELF.ELF_HEADER_SIZE + 80:ELF.ELF_HEADER_SIZE + 84] = struct.pack("<I", 0)
        with self.assertRaisesRegex(ValueError, "missing .ARM.attributes"):
            ELF.arm_cpu_arch(bytes(elf), "fixture.so")

    def test_rejects_truncated_uleb128(self):
        with self.assertRaisesRegex(ValueError, "truncated ULEB128"):
            ELF.read_uleb128(b"\x80", 0, 1)

    def test_rejects_oversized_uleb128(self):
        with self.assertRaisesRegex(ValueError, "oversized ULEB128"):
            ELF.read_uleb128(b"\x80\x80\x80\x80\x80", 0, 5)


class SymbolVerificationTests(unittest.TestCase):
    def test_accepts_libsqlite3x_with_required_symbols(self):
        # All required symbols present
        elf = make_elf_with_dynsym(["JNI_OnLoad", "sqlite3_open", "sqlite3_close", "extra_sym"])
        # Should not raise
        ELF.check_required_symbols(elf, "libsqlite3x.so")

    def test_rejects_libsqlite3x_missing_JNI_OnLoad(self):
        elf = make_elf_with_dynsym(["sqlite3_open", "sqlite3_close"])
        with self.assertRaisesRegex(SystemExit, "missing required dynamic symbols: \\['JNI_OnLoad'\\]"):
            ELF.check_required_symbols(elf, "libsqlite3x.so")

    def test_rejects_libsqlite3x_missing_sqlite3_open(self):
        elf = make_elf_with_dynsym(["JNI_OnLoad", "sqlite3_close"])
        with self.assertRaisesRegex(SystemExit, "missing required dynamic symbols: \\['sqlite3_open'\\]"):
            ELF.check_required_symbols(elf, "libsqlite3x.so")

    def test_rejects_libsqlite3x_missing_multiple_symbols(self):
        elf = make_elf_with_dynsym(["JNI_OnLoad"])
        with self.assertRaisesRegex(SystemExit, "missing required dynamic symbols: \\['sqlite3_close', 'sqlite3_open'\\]"):
            ELF.check_required_symbols(elf, "libsqlite3x.so")

    def test_skips_non_target_libraries(self):
        # Other libraries should not be checked
        elf = make_elf_with_dynsym([])  # No symbols
        # Should not raise for non-target library
        ELF.check_required_symbols(elf, "libsimple.so")
        ELF.check_required_symbols(elf, "libtermux.so")


class ApkIntegrationTests(unittest.TestCase):
    def test_apk_accepts_all_required_armv7_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "valid.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in REQUIRED:
                    archive.writestr(f"lib/armeabi-v7a/{name}", make_elf(ELF.ARMV7_ARCH))
            with patch.object(sys, "argv", [str(SCRIPT), str(apk)]):
                ELF.main()

    def test_apk_rejects_wrong_abi_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "wrong-abi.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in REQUIRED:
                    archive.writestr(f"lib/arm64-v8a/{name}", make_elf(ELF.ARMV7_ARCH))
            with patch.object(sys, "argv", [str(SCRIPT), str(apk)]):
                with self.assertRaisesRegex(SystemExit, "unexpected native library paths"):
                    ELF.main()

    def test_apk_rejects_nested_native_library_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "nested.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in REQUIRED:
                    archive.writestr(
                        f"lib/armeabi-v7a/nested/{name}",
                        make_elf(ELF.ARMV7_ARCH),
                    )
            with patch.object(sys, "argv", [str(SCRIPT), str(apk)]):
                with self.assertRaisesRegex(SystemExit, "unexpected native library paths"):
                    ELF.main()

    def test_apk_rejects_noncanonical_native_library_paths(self):
        malformed_paths = (
            f"lib/armeabi-v7a/./{REQUIRED[0]}",
            f"lib//armeabi-v7a/{REQUIRED[0]}",
        )
        for malformed_path in malformed_paths:
            with self.subTest(path=malformed_path):
                with tempfile.TemporaryDirectory() as directory:
                    apk = Path(directory) / "noncanonical.apk"
                    with zipfile.ZipFile(apk, "w") as archive:
                        archive.writestr(malformed_path, make_elf(ELF.ARMV7_ARCH))
                        for name in REQUIRED[1:]:
                            archive.writestr(
                                f"lib/armeabi-v7a/{name}",
                                make_elf(ELF.ARMV7_ARCH),
                            )
                    with patch.object(sys, "argv", [str(SCRIPT), str(apk)]):
                        with self.assertRaisesRegex(
                            SystemExit, "unexpected native library paths"
                        ):
                            ELF.main()

    def test_apk_rejects_duplicate_native_library_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "duplicate.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in REQUIRED:
                    archive.writestr(
                        f"lib/armeabi-v7a/{name}",
                        make_elf(ELF.ARMV7_ARCH),
                    )
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    archive.writestr(
                        f"lib/armeabi-v7a/{REQUIRED[0]}",
                        make_elf(ELF.ARMV7_ARCH),
                    )
            with patch.object(sys, "argv", [str(SCRIPT), str(apk)]):
                with self.assertRaisesRegex(
                    SystemExit, "duplicate native library paths"
                ):
                    ELF.main()

    def test_apk_rejects_missing_required_library(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "missing.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in REQUIRED[1:]:
                    archive.writestr(f"lib/armeabi-v7a/{name}", make_elf(ELF.ARMV7_ARCH))
            with patch.object(sys, "argv", [str(SCRIPT), str(apk)]):
                with self.assertRaisesRegex(SystemExit, "missing required native libraries"):
                    ELF.main()

    def test_apk_rejects_non_armv7_library(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "wrong-machine.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in REQUIRED:
                    archive.writestr(
                        f"lib/armeabi-v7a/{name}",
                        make_elf(9) if name == REQUIRED[0] else make_elf(ELF.ARMV7_ARCH),
                    )
            with patch.object(sys, "argv", [str(SCRIPT), str(apk)]):
                with self.assertRaisesRegex(SystemExit, "expected Tag_CPU_arch=10"):
                    ELF.main()


if __name__ == "__main__":
    unittest.main(verbosity=2)