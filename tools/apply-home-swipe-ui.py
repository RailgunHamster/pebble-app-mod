"""Apply the small UI adapter matching the upstream watch settings layout."""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    screen = source / 'pebble/src/commonMain/kotlin/coredevices/pebble/ui/WatchPrefsScreen.kt'
    text = screen.read_text(encoding='utf-8')
    if 'fun watchPrefs(): List<SettingsItem>' not in text:
        raise SystemExit('Unrecognized upstream watch preferences screen')
    variant = 'layout' if 'internal val WATCH_PREF_LAYOUT:' in text else 'classic'
    patch = Path(__file__).resolve().parents[1] / 'compat/home-swipes' / (variant + '.patch')
    print('Applying home swipe UI adapter:', variant, flush=True)
    subprocess.run(['git', 'apply', '--3way', '--whitespace=nowarn', str(patch)], cwd=source, check=True)


if __name__ == '__main__':
    main()
