#!/bin/bash
# エミュレータが, 起動し終わって, 落ち着くまで待つ.
#
# - adb の接続と, sys.boot_completed が 1 になるまで待つ
# - アニメーションを止める (ソフトウェア描画で, 軽くするため)
# - 起動の直後は, システムが重く, 「System UI isn't responding」(ANR) が出ることがあるので,
#   落ち着くまで, 少し待つ (SETTLE 秒. 既定は 60 秒)
#
# 使い方: docker compose exec emulator scripts/emulator-wait.sh [タイムアウト秒 [落ち着くまでの秒]]
set -eu

timeout=${1:-600}
settle=${2:-60}

adb wait-for-device
booted=0
for ((i = 0; i < timeout; i += 2)); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
        booted=1
        echo "The emulator has booted (${i} s)"
        break
    fi
    sleep 2
done
if [ "${booted}" != 1 ]; then
    echo "The emulator did not boot within ${timeout} s" >&2
    exit 1
fi

for name in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "${name}" 0
done

echo "Waiting ${settle} s for the system to settle"
sleep "${settle}"
echo "Ready"
