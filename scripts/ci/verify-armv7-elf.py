#!/usr/bin/env python3
"""Verify that every native shared library packaged in an APK is a real ARMv7 ELF."""

from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import PurePosixPath

REQUIRED_LIBRARIES = {
    "libsimple.so",
    "libtermux.so",
    "libworkspace.so",
    "libproot_exec.so",
    "libproot_loader.so",
    "libquickjs.so",
    "libsqlite3x.so",
}
ELF_HEADER_SIZE = 52
ELFCLASS32 = 1
ELFDATA2LSB = 1
ET_DYN = 3
EM_ARM = 40


def fail(message: str) -> None:
    raise SystemExit(f"ARMv7 ELF validation failed: {message}")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: verify-armv7-elf.py <apk>")

    apk = sys.argv[1]
    try:
        with zipfile.ZipFile(apk) as archive:
            libraries = [
                name
                for name in archive.namelist()
                if name.startswith("lib/") and name.endswith(".so")
            ]
            if not libraries:
                fail("APK contains no native shared libraries")

            wrong_abi = [
                name for name in libraries
                if not name.startswith("lib/armeabi-v7a/")
            ]
            if wrong_abi:
                fail(f"unexpected ABI paths: {wrong_abi}")

            names = {PurePosixPath(name).name for name in libraries}
            missing = sorted(REQUIRED_LIBRARIES - names)
            if missing:
                fail(f"missing required native libraries: {missing}")

            for name in sorted(libraries):
                with archive.open(name) as library:
                    header = library.read(ELF_HEADER_SIZE)

                if len(header) != ELF_HEADER_SIZE:
                    fail(f"{name}: truncated ELF32 header ({len(header)} bytes)")
                if header[:4] != bytes((0x7F, 0x45, 0x4C, 0x46)):
                    fail(f"{name}: invalid ELF magic")
                if header[4] != ELFCLASS32:
                    fail(f"{name}: expected ELFCLASS32, got {header[4]}")
                if header[5] != ELFDATA2LSB:
                    fail(f"{name}: expected little-endian ELF, got EI_DATA={header[5]}")
                if header[6] != 1:
                    fail(f"{name}: unsupported ELF identification version {header[6]}")

                elf_type, machine, version = struct.unpack_from("<HHI", header, 16)
                header_size = struct.unpack_from("<H", header, 40)[0]
                if elf_type != ET_DYN:
                    fail(f"{name}: expected ET_DYN shared object, got e_type={elf_type}")
                if machine != EM_ARM:
                    fail(f"{name}: expected EM_ARM (40), got e_machine={machine}")
                if version != 1:
                    fail(f"{name}: unsupported ELF version {version}")
                if header_size != ELF_HEADER_SIZE:
                    fail(f"{name}: expected ELF header size 52, got {header_size}")

            print(
                f"Verified {len(libraries)} native libraries: "
                "ELF32, little-endian, ET_DYN, EM_ARM (armeabi-v7a)"
            )
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"cannot inspect APK {apk}: {error}")


if __name__ == "__main__":
    main()
