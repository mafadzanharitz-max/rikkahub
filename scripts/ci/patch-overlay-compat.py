#!/usr/bin/env python3
"""Adapt the pinned ARMv7 overlay to the fork's current RootFS test layout."""
from pathlib import Path

path = Path("scripts/ci/apply-overlay.py")
source = path.read_text()
old_installer_patch = r'''    installer = root / "workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt"
    replace_once(installer, 'extractTar(archive, stagingDir, format, onProgress)\n            linuxDir.deleteRecursively()',
                 'extractTar(archive, stagingDir, format, onProgress)\n            Armv7Rootfs.validate(stagingDir)\n            linuxDir.deleteRecursively()')
'''
new_installer_patch = r'''    installer = root / "workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt"
    installer_source = installer.read_text()
    if "internal fun activateRootfs(" in installer_source:
        print("Tracked transactional RootFS implementation detected; skipping legacy overlay mutation.")
    else:
        replace_once(installer, 'extractTar(archive, stagingDir, format, onProgress)\n            linuxDir.deleteRecursively()',
                     'extractTar(archive, stagingDir, format, onProgress)\n            Armv7Rootfs.validate(stagingDir)\n            linuxDir.deleteRecursively()')
'''
if old_installer_patch not in source:
    raise SystemExit("Pinned overlay RootFS installer adaptation changed")
source = source.replace(old_installer_patch, new_installer_patch, 1)
start_marker = '    fixture = root / "workspace/src/test/java/me/rerere/workspace/ExampleUnitTest.kt"\n'
end_marker = '    templates = {\n'
start = source.find(start_marker)
end = source.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit("Pinned overlay fixture adaptation boundaries changed")

old_block = source[start:end]
if old_block.count("replace_once(fixture,") != 1:
    raise SystemExit("Pinned overlay fixture patch is not the expected single fail-closed operation")

new_block = r'''    fixture = root / "workspace/src/test/java/me/rerere/workspace/ExampleUnitTest.kt"
    fixture_source = fixture.read_text()
    fixture_target = """val archive = tarGz(
            TarTestEntry("bin/", type = '5'),"""
    if fixture_target in fixture_source:
        replace_once(fixture, fixture_target,
                     fixture_target + "\n            TarTestEntry(\"bin/bash\", content = armv7TestShellElf(), mode = 493),")
    elif (
        "fun rootfsInstallerRejectsCleartextHttpUrl()" in fixture_source
        and "fun rootfsInstallerDownloadsAndExtractsTarGz()" not in fixture_source
    ):
        print("Current fork replaced the legacy HTTP integration fixture; no archive fixture patch is needed.")
    else:
        raise RuntimeError("Unrecognized RootFS test fixture layout; refusing to skip ARMv7 shell fixture validation")
'''
path.write_text(source[:start] + new_block + source[end:])
print("Pinned overlay compatibility patch applied.")
