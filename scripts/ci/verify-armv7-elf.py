#!/usr/bin/env python3
"""Verify that every native shared library packaged in an APK is an ARMv7 ELF."""

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
ELF_SECTION_HEADER_SIZE = 40
ELFCLASS32 = 1
ELFDATA2LSB = 1
ET_DYN = 3
EM_ARM = 40
SHT_STRTAB = 3
ARM_ATTRIBUTES_SECTION = ".ARM.attributes"
AEABI_VENDOR = "aeabi"
TAG_FILE = 1
TAG_CPU_RAW_NAME = 4
TAG_CPU_NAME = 5
TAG_CPU_ARCH = 6
ARMV7_ARCH = 10


def fail(message: str) -> None:
    raise SystemExit(f"ARMv7 ELF validation failed: {message}")


def read_uleb128(data: bytes, offset: int, end: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < end:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
        if shift >= 35:
            raise ValueError("oversized ULEB128 value")
    raise ValueError("truncated ULEB128 value")


def read_c_string(data: bytes, offset: int, end: int) -> tuple[str, int]:
    terminator = data.find(b"\\0", offset, end)
    if terminator < 0:
        raise ValueError("unterminated attribute string")
    return data[offset:terminator].decode("ascii"), terminator + 1


def arm_cpu_arch(data: bytes, name: str) -> int:
    if len(data) < ELF_HEADER_SIZE:
        raise ValueError("truncated ELF header")

    section_offset = struct.unpack_from("<I", data, 32)[0]
    section_entry_size, section_count, string_index = struct.unpack_from(
        "<HHH", data, 46
    )
    if section_entry_size != ELF_SECTION_HEADER_SIZE or section_count == 0:
        raise ValueError("missing or unsupported ELF section header table")
    table_end = section_offset + section_entry_size * section_count
    if section_offset < ELF_HEADER_SIZE or table_end > len(data):
        raise ValueError("ELF section header table is out of bounds")
    if string_index >= section_count:
        raise ValueError("invalid section-name string table index")

    sections = [
        struct.unpack_from("<IIIIIIIIII", data, section_offset + i * section_entry_size)
        for i in range(section_count)
    ]
    string_header = sections[string_index]
    string_offset, string_size = string_header[4], string_header[5]
    if string_header[1] != SHT_STRTAB or string_offset + string_size > len(data):
        raise ValueError("invalid section-name string table")
    section_names = data[string_offset:string_offset + string_size]

    attributes = None
    for section in sections:
        name_offset = section[0]
        if name_offset >= len(section_names):
            continue
        end = section_names.find(b"\\0", name_offset)
        if end < 0:
            continue
        if section_names[name_offset:end] == ARM_ATTRIBUTES_SECTION.encode("ascii"):
            offset, size = section[4], section[5]
            if offset + size > len(data):
                raise ValueError(".ARM.attributes section is out of bounds")
            attributes = data[offset:offset + size]
            break

    if not attributes:
        raise ValueError("missing .ARM.attributes section")
    if attributes[0:1] != b"A":
        raise ValueError("unsupported .ARM.attributes format")

    offset = 1
    while offset < len(attributes):
        if offset + 4 > len(attributes):
            raise ValueError("truncated ARM vendor subsection")
        vendor_length = struct.unpack_from("<I", attributes, offset)[0]
        vendor_end = offset + vendor_length
        if vendor_length < 5 or vendor_end > len(attributes):
            raise ValueError("invalid ARM vendor subsection length")
        vendor, cursor = read_c_string(attributes, offset + 4, vendor_end)
        if vendor == AEABI_VENDOR:
            while cursor < vendor_end:
                subsection_tag, cursor = read_uleb128(attributes, cursor, vendor_end)
                if cursor + 4 > vendor_end:
                    raise ValueError("truncated ARM attribute subsection")
                subsection_length = struct.unpack_from("<I", attributes, cursor)[0]
                subsection_start = cursor - (cursor - (cursor - 0))  # replaced below
                payload_start = cursor + 4
                subsection_end = cursor - 0 + subsection_length
                if subsection_length < 5 or subsection_end > vendor_end:
                    raise ValueError("invalid ARM attribute subsection length")
                if subsection_tag == TAG_FILE:
                    attr_cursor = payload_start
                    while attr_cursor < subsection_end:
                        tag, attr_cursor = read_uleb128(
                            attributes, attr_cursor, subsection_end
                        )
                        if tag in (TAG_CPU_RAW_NAME, TAG_CPU_NAME):
                            _, attr_cursor = read_c_string(
                                attributes, attr_cursor, subsection_end
                            )
                        elif tag == TAG_CPU_ARCH:
                            arch, _ = read_uleb128(
                                attributes, attr_cursor, subsection_end
                            )
                            return arch
                        else:
                            raise ValueError(
                                f"cannot safely parse ARM attribute tag {tag} before Tag_CPU_arch"
                            )
                cursor = subsection_end
        offset = vendor_end

    raise ValueError("AEABI Tag_CPU_arch attribute not found")


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
                    elf = library.read()

                if len(elf) < ELF_HEADER_SIZE:
                    fail(f"{name}: truncated ELF32 header ({len(elf)} bytes)")
                header = elf[:ELF_HEADER_SIZE]
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

                try:
                    cpu_arch = arm_cpu_arch(elf, name)
                except (ValueError, UnicodeDecodeError, struct.error) as error:
                    fail(f"{name}: cannot verify ARM CPU architecture: {error}")
                if cpu_arch != ARMV7_ARCH:
                    fail(
                        f"{name}: expected Tag_CPU_arch=10 (ARMv7), "
                        f"got {cpu_arch}"
                    )

            print(
                f"Verified {len(libraries)} native libraries: "
                "ELF32, little-endian, ET_DYN, EM_ARM, Tag_CPU_arch=10 (ARMv7)"
            )
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"cannot inspect APK {apk}: {error}")


if __name__ == "__main__":
    main()
