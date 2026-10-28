# Generated test audio

`reference.mp3` is a synthetic 208-second quiet sine tone with a generated PNG
cover and 60 rows of test captions. It contains no external recording, cover art,
or song lyrics. The generated audio, pixels and captions may be freely used and
redistributed for any purpose.

Regenerate with Python 3 and FFmpeg installed:

```powershell
python tools/generate-test-audio.py
```

The fixture exercises audio metadata, embedded lyrics, artwork decoding, seeking,
library storage and file synchronization. It is not intended as listening music.
