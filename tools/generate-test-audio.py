"""Generate SyncTone's synthetic MP3/cover/lyrics fixture (Python 3 + FFmpeg).

All sound, pixels and caption text are generated here, without external media.
The generated fixture may be freely used and redistributed for any purpose.
"""
from pathlib import Path
import math
import random
import struct
import subprocess
import tempfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / 'app/src/main/assets/reference.mp3'


def stamp(milliseconds):
    return f'{milliseconds // 60000:02}:{milliseconds // 1000 % 60:02}.{milliseconds % 1000:03}'


def captions():
    rows = []
    for index in range(60):
        start = 968 + index * 3200
        words = 'Sync Tone plays a generated audio sample for local music player tests'.split()
        rows.append(''.join((f'[{stamp(start)}]' if word_index == 0 else f'<{stamp(start + word_index * 170)}>')
                            + word + (' ' if word_index < len(words) - 1 else '')
                            for word_index, word in enumerate(words)) + f'<{stamp(start + 2400)}>')
        rows.append(f'[{stamp(start)}]syn <{stamp(start + 432)}>the <{stamp(start + 1032)}>tic '
                    f'<{stamp(start + 1632)}>audio<{stamp(start + 2532)}>')
        rows.append(f'[{stamp(start)}]Generated audio sample {index + 1:02}')
    return '\n'.join(rows)


def cover_png():
    width = height = 512
    rng = random.Random(2181)
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            wave = math.sin(x / 43 + y / 61)
            raw.extend((int(60 + 90 * x / width + 25 * wave + rng.randrange(12)),
                        int(75 + 100 * y / height + rng.randrange(12)),
                        int(150 + 30 * wave + rng.randrange(12))))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def frame(name, payload):
    return name.encode('ascii') + struct.pack('>I', len(payload)) + b'\0\0' + payload


def main():
    with tempfile.TemporaryDirectory(prefix='synctone-fixture-') as temporary:
        sound = Path(temporary) / 'tone.mp3'
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi',
                        '-i', 'sine=frequency=440:sample_rate=22050:duration=208',
                        '-af', 'volume=0.08', '-c:a', 'libmp3lame', '-b:a', '64k',
                        '-id3v2_version', '3', '-map_metadata', '-1', str(sound)], check=True)
        audio = sound.read_bytes()
    if audio.startswith(b'ID3'):
        length = 0
        for value in audio[6:10]:
            length = (length << 7) | value
        audio = audio[10 + length:]
    tag = b''.join(frame(key, b'\x01' + value.encode('utf-16')) for key, value in (
        ('TIT2', 'SyncTone synthetic demo'), ('TPE1', 'SyncTone Tests'), ('TALB', 'Generated fixtures')))
    tag += frame('USLT', b'\x01eng\xff\xfe\0\0' + captions().encode('utf-16'))
    tag += frame('APIC', b'\0image/png\0\x03\0' + cover_png())
    size = bytes((len(tag) >> shift) & 127 for shift in (21, 14, 7, 0))
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    DESTINATION.write_bytes(b'ID3\x03\0\0' + size + tag + audio)
    print(f'Generated {DESTINATION.name}: {DESTINATION.stat().st_size} bytes')


if __name__ == '__main__':
    main()
