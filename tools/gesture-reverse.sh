#!/system/bin/sh
# MIX 2S, 1080x2160: expand the mini-player, pause, reverse without lifting.
set -e
trap 'input touchscreen motionevent UP 540 1980' EXIT
input touchscreen motionevent DOWN 540 1980
for y in 1920 1860 1800 1740 1680 1620 1560 1500 1440 1380 1320 1260 1200; do
    input touchscreen motionevent MOVE 540 "$y"
    sleep 0.025
done
sleep 0.3
for y in 1260 1320 1380 1440 1500 1560 1620 1680 1740 1800 1860 1920 1980; do
    input touchscreen motionevent MOVE 540 "$y"
    sleep 0.025
done
