#!/system/bin/sh
# MIX 2S, 1080x2160: artwork -> lyrics -> artwork, then reverse a partial swipe.
set -e
trap 'input touchscreen motionevent UP 930 1100' EXIT
input touchscreen motionevent DOWN 930 1100
for x in 870 810 750 690 630 570 510 450; do
    input touchscreen motionevent MOVE "$x" 1100
    sleep 0.03
done
screencap -p /sdcard/replica-pager-half.png
for x in 390 330 270 210 150 90; do
    input touchscreen motionevent MOVE "$x" 1100
    sleep 0.03
done
input touchscreen motionevent UP 90 1100
sleep 1
input touchscreen swipe 150 1100 930 1100 1000
sleep 1
input touchscreen motionevent DOWN 930 1100
for x in 870 810 750 690 630 570 510 450 510 570 630 690 750 810 870 930; do
    input touchscreen motionevent MOVE "$x" 1100
    sleep 0.03
done
input touchscreen motionevent UP 930 1100
