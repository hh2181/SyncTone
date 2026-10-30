"""Export release artifacts while excluding portable user data and logs."""
from pathlib import Path
import argparse
import hashlib
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT.parent / 'deliverables')
    parser.add_argument('--archive-only', action='store_true', help='Export the Windows zip without a duplicate extracted folder.')
    args = parser.parse_args()
    android_config = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
    windows_config = (ROOT / 'desktopApp/build.gradle.kts').read_text(encoding='utf-8')
    version = re.search(r'versionName\s*=\s*"([^"]+)"', android_config).group(1)
    windows_version = re.search(r'packageVersion\s*=\s*"([^"]+)"', windows_config).group(1)
    if version != windows_version or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Both platforms must have the same numeric release version.')
    source = ROOT / 'desktopApp/build/compose/binaries/main/app/SyncTone'
    apk_source = ROOT / 'app/build/outputs/apk/release/app-release.apk'
    if not apk_source.is_file() or not (source / 'SyncTone.exe').is_file():
        raise FileNotFoundError('Build the Android Release and Windows distributable first.')
    destination = args.output.resolve()
    windows_folder = destination / f'Windows/SyncTone-Windows-{version}-portable/SyncTone'
    apk = destination / f'Android/SyncTone-Android-{version}-release.apk'
    archive = destination / f'Windows/SyncTone-Windows-{version}-x64-portable.zip'
    checksums = destination / f'Release-{version}/SHA256SUMS.txt'
    if any(path.exists() for path in (windows_folder, apk, archive, checksums)):
        raise FileExistsError('Release outputs already exist; use a new --output directory.')
    if not args.archive_only:
        windows_folder.mkdir(parents=True)
        for path in sorted(source.rglob('*')):
            relative = path.relative_to(source)
            if not path.is_file() or relative.parts[0] in {'data', 'portable'}:
                continue
            target = windows_folder / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
        shutil.copy2(ROOT / 'THIRD_PARTY_NOTICES.md', windows_folder / 'THIRD_PARTY_NOTICES.md')
    readme = (
        f'SyncTone {version} for Windows x64\n\n'
        'Extract this entire SyncTone folder before running SyncTone.exe.\n'
        'User data is stored in data/ beside the EXE; logs and temporary files use portable/.\n'
        'Keep a backup of data/ when upgrading or moving your music library.\n'
        'This release package does not include development machine user data.\n'
        'Equalizer processing uses the default audio device at 48 kHz / 16-bit / stereo.\n'
        'See THIRD_PARTY_NOTICES.md and app/resources/vlc/COPYING.txt for third-party notices.\n')
    if not args.archive_only:
        (windows_folder / 'README.txt').write_text(readme, encoding='utf-8')
    apk.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk_source, apk)
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as package:
        archive_source = source if args.archive_only else windows_folder
        for path in sorted(archive_source.rglob('*')):
            if path.is_file():
                relative = path.relative_to(archive_source)
                if relative.parts[0] in {'data', 'portable'}:
                    if args.archive_only:
                        continue
                    raise ValueError(f'User data cannot be packaged: {relative}')
                package.write(path, f'SyncTone/{relative.as_posix()}')
        if args.archive_only:
            package.write(ROOT / 'THIRD_PARTY_NOTICES.md', 'SyncTone/THIRD_PARTY_NOTICES.md')
            package.writestr('SyncTone/README.txt', readme)
    checksums.parent.mkdir(parents=True, exist_ok=True)
    checksums.write_text(''.join(f'{digest(path)}  {path.name}\n' for path in (apk, archive)),
                         encoding='ascii', newline='\n')
    for path in (apk, archive, checksums):
        print(f'{path.name}: {path.stat().st_size} bytes')


if __name__ == '__main__':
    main()
