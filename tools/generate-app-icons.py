"""Adapt the supplied app artwork to Android resources and Windows icon formats.

Requires Pillow. Does not redraw or alter the source artwork.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'assets/branding/app-icon.png'


def save_png(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format='PNG', optimize=True)


def main():
    with Image.open(SOURCE) as source:
        artwork = source.convert('RGBA')
    if artwork.width != artwork.height:
        raise ValueError('The app artwork must be square; do not crop it implicitly.')
    resource_root = ROOT / 'app/src/main/res'
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        save_png(artwork.resize((size, size), Image.Resampling.LANCZOS),
                 resource_root / f'mipmap-{density}/ic_launcher.png')
    # Adaptive icon layers are 108 dp; the central 72 dp holds the supplied image.
    foreground = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
    foreground.alpha_composite(artwork.resize((288, 288), Image.Resampling.LANCZOS), (72, 72))
    save_png(foreground, resource_root / 'drawable-nodpi/ic_launcher_foreground.png')
    save_png(artwork.resize((512, 512), Image.Resampling.LANCZOS),
             ROOT / 'desktopApp/src/desktopMain/resources/app-icon.png')
    ico_path = ROOT / 'desktopApp/packaging/app-icon.ico'
    ico_path.parent.mkdir(parents=True, exist_ok=True)
    artwork.resize((256, 256), Image.Resampling.LANCZOS).save(
        ico_path, format='ICO', sizes=[(size, size) for size in (16, 20, 24, 32, 40, 48, 64, 128, 256)])
    print('Generated Android launcher icons and Windows app icons.')


if __name__ == '__main__':
    main()
