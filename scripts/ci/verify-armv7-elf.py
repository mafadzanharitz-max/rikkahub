#!/usr/bin/env python3
"""Verify that every native shared library packaged in an APK is an ARMv7 ELF."""

from __future__ import annotations

import struct
import sys
import zipfile

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
ET_EXEC = 2
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

# Symbols that must be present in specific libraries.
# These are minimal sanity checks; not exhaustive.
REQUIRED_SYMBOLS = {
    "libsqlite3x.so": {"JNI_OnLoad", "sqlite3_open", "sqlite3_close"},
}


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
    terminator = data.find(b"\0", offset, end)
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
        end = section_names.find(b"\0", name_offset)
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
    cpu_arches = []
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
                subsection_start = cursor
                subsection_tag, cursor = read_uleb128(attributes, cursor, vendor_end)
                if cursor + 4 > vendor_end:
                    raise ValueError("truncated ARM attribute subsection")
                subsection_length = struct.unpack_from("<I", attributes, cursor)[0]
                payload_start = cursor + 4
                # AAELF32 defines this size as including the ULEB128 tag,
                # the 4-byte size field, and the subsection payload.
                subsection_end = subsection_start + subsection_length
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
                            cpu_arches.append(arch)
                            # Later attributes are not needed for ABI validation.
                            break
                        elif tag == 32:  # Tag_compatibility: integer plus string.
                            _, attr_cursor = read_uleb128(
                                attributes, attr_cursor, subsection_end
                            )
                            _, attr_cursor = read_c_string(
                                attributes, attr_cursor, subsection_end
                            )
                        elif tag & 1:  # AAELF32 odd-numbered tags carry strings.
                            _, attr_cursor = read_c_string(
                                attributes, attr_cursor, subsection_end
                            )
                        else:  # Other even-numbered tags carry ULEB128 integers.
                            _, attr_cursor = read_uleb128(
                                attributes, attr_cursor, subsection_end
                            )
                cursor = subsection_end
        offset = vendor_end

    if not cpu_arches:
        raise ValueError("AEABI Tag_CPU_arch attribute not found")
    if any(arch != cpu_arches[0] for arch in cpu_arches):
        raise ValueError(f"conflicting AEABI Tag_CPU_arch attributes: {cpu_arches}")
    return cpu_arches[0]


def check_required_symbols(elf: bytes, lib_name: str) -> None:
    """Verify required dynamic symbols are present in the ELF."""
    if lib_name not in REQUIRED_SYMBOLS:
        return
    # Parse dynamic symbol table (.dynsym) and string table (.dynstr)
    # Elf32_Sym is 16 bytes: st_name(I), st_value(I), st_size(I), st_info(B), st_other(B), st_shndx(H)
    SYM_ENTRY_SIZE = 16
    SYM_FMT = "<IIIBBH"  # st_name, st_value, st_size, st_info, st_other, st_shndx
    if len(elf) < ELF_HEADER_SIZE:
        fail(f"{lib_name}: truncated ELF header; required symbols cannot be verified")
    section_offset = struct.unpack_from("<I", elf, 32)[0]
    section_entry_size, section_count, string_index = struct.unpack_from(
        "<HHH", elf, 46
    )
    if section_entry_size != ELF_SECTION_HEADER_SIZE or section_count == 0:
        fail(f"{lib_name}: missing or unsupported ELF section header table")
    table_end = section_offset + section_entry_size * section_count
    if section_offset < ELF_HEADER_SIZE or table_end > len(elf):
        fail(f"{lib_name}: ELF section header table is out of bounds")
    if string_index >= section_count:
        fail(f"{lib_name}: invalid section-name string table index")

    sections = [
        struct.unpack_from("<IIIIIIIIII", elf, section_offset + i * section_entry_size)
        for i in range(section_count)
    ]

    dynsym = None
    dynstr = None
    for section in sections:
        if section[1] == 11:  # SHT_DYNSYM
            if dynsym is not None:
                fail(f"{lib_name}: multiple dynamic symbol tables")
            dynsym = section

    if dynsym is None:
        fail(f"{lib_name}: missing dynamic symbol table; required symbols cannot be verified")

    # Find .dynstr via dynsym's sh_link
    # Section header fields: [sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size, sh_link, sh_info, sh_addralign, sh_entsize]
    dynstr_idx = dynsym[6]  # sh_link
    if dynstr_idx >= section_count:
        fail(f"{lib_name}: dynamic symbol table has invalid string-table link")
    dynstr_section = sections[dynstr_idx]
    if dynstr_section[1] != 3:  # SHT_STRTAB
        fail(f"{lib_name}: dynamic symbol table link is not a string table")

    dynsym_offset, dynsym_size = dynsym[4], dynsym[5]
    dynstr_offset, dynstr_size = dynstr_section[4], dynstr_section[5]
    if (
        dynsym[9] != SYM_ENTRY_SIZE
        or dynsym_size == 0
        or dynsym_size % SYM_ENTRY_SIZE != 0
        or dynstr_size == 0
        or dynsym_offset < ELF_HEADER_SIZE
        or dynstr_offset < ELF_HEADER_SIZE
        or dynsym_offset + dynsym_size > len(elf)
        or dynstr_offset + dynstr_size > len(elf)
    ):
        fail(f"{lib_name}: malformed or out-of-bounds dynamic symbol/string table")

    dynsym_data = elf[dynsym_offset:dynsym_offset + dynsym_size]
    dynstr_data = elf[dynstr_offset:dynstr_offset + dynstr_size]

    # Parse symbols
    found = set()
    for i in range(0, len(dynsym_data), SYM_ENTRY_SIZE):
        if i + SYM_ENTRY_SIZE > len(dynsym_data):
            break
        st_name, st_value, st_size, st_info, st_other, st_shndx = struct.unpack_from(
            SYM_FMT, dynsym_data, i
        )
        if st_name == 0:
            continue
        if st_name >= len(dynstr_data):
            fail(f"{lib_name}: dynamic symbol name offset is out of bounds")
        name_end = dynstr_data.find(b"\0", st_name)
        if name_end < 0:
            fail(f"{lib_name}: unterminated dynamic symbol name")
        sym_name = dynstr_data[st_name:name_end].decode("ascii", errors="ignore")
        if sym_name in REQUIRED_SYMBOLS[lib_name]:
            # Required APIs must be exported definitions, not merely undefined
            # imports that happen to have the same names.
            binding = st_info >> 4
            visibility = st_other & 0x03
            if st_shndx != 0 and binding in (1, 2) and visibility in (0, 3):
                found.add(sym_name)

    required = REQUIRED_SYMBOLS[lib_name]
    missing = required - found
    if missing:
        fail(f"{lib_name}: missing required dynamic symbols: {sorted(missing)}")


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

            wrong_paths = []
            duplicate_paths = []
            seen_paths = set()
            for name in libraries:
                # Validate the raw ZIP path: PurePosixPath normalizes "." and
                # repeated separators, which would hide non-canonical entries.
                parts = name.split("/")
                if (
                    len(parts) != 3
                    or parts[0] != "lib"
                    or parts[1] != "armeabi-v7a"
                    or parts[2] in ("", ".", "..")
                ):
                    wrong_paths.append(name)
                if name in seen_paths:
                    duplicate_paths.append(name)
                seen_paths.add(name)
            if wrong_paths:
                fail(f"unexpected native library paths: {wrong_paths}")
            if duplicate_paths:
                fail(f"duplicate native library paths: {duplicate_paths}")

            names = {name.rsplit("/", 1)[-1] for name in libraries}
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
                lib_basename = name.rsplit("/", 1)[-1]
                # proot_loader is an executable launched as a program, even
                # though it is packaged in Android's native-library directory.
                # Every other packaged .so must remain an ET_DYN shared object.
                expected_type = ET_EXEC if lib_basename == "libproot_loader.so" else ET_DYN
                if elf_type != expected_type:
                    expected_name = (
                        "ET_EXEC executable" if expected_type == ET_EXEC
                        else "ET_DYN shared object"
                    )
                    fail(f"{name}: expected {expected_name}, got e_type={elf_type}")
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

                # Verify required dynamic symbols
                check_required_symbols(elf, lib_basename)

            print(
                f"Verified {len(libraries)} native libraries: "
                "ELF32, little-endian, ET_DYN (ET_EXEC for proot_loader), "
                "EM_ARM, Tag_CPU_arch=10 (ARMv7)"
            )
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"cannot inspect APK {apk}: {error}")


if __name__ == "__main__":
    main()