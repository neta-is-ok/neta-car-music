#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

source=artwork/neta-music-icon.png
mkdir -p app/src/main/res/drawable-nodpi
magick "$source" -resize 512x512 app/src/main/res/drawable-nodpi/neta_music_icon.png

for spec in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density=${spec%%:*}
  size=${spec##*:}
  dir="app/src/main/res/mipmap-$density"
  mkdir -p "$dir"
  magick "$source" -resize "${size}x${size}" "$dir/ic_launcher.png"
  cp "$dir/ic_launcher.png" "$dir/ic_launcher_round.png"
done
