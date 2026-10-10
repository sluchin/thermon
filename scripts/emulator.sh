#!/bin/bash
# Android エミュレータを起動する (Docker の中. docker compose up emulator から呼ばれる).
#
# - 初回は, AVD (仮想の端末) を作る (ボリューム /avd に残る)
# - 既定は, 仮想の画面 (Xvfb) に, エミュレータのウィンドウを出して, ブラウザ (noVNC) で見られるようにする
#   (http://localhost:6080/vnc.html)
# - EMULATOR_HEADLESS=1 のときは, 画面を出さない (adb だけで使う)
set -eu

# KVM を使えないと, エミュレータは起動しない. 原因を, 先に伝える
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "ERROR: no access to /dev/kvm." >&2
    echo "  Export the group ID of the kvm group on the host, then recreate the container:" >&2
    echo "    export KVM_GID=\$(getent group kvm | cut -d: -f3)" >&2
    exit 1
fi

AVD_NAME=${AVD_NAME:-thermon}
HEADLESS=${EMULATOR_HEADLESS:-0}

if [ ! -d "${ANDROID_AVD_HOME}/${AVD_NAME}.avd" ]; then
    echo "Creating the AVD ${AVD_NAME} (${EMULATOR_IMAGE})"
    echo no | avdmanager create avd --name "${AVD_NAME}" --package "${EMULATOR_IMAGE}" \
        --device pixel_6 --force
fi

options=(-avd "${AVD_NAME}" -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
    -memory 2048 -cores 2)

if [ "${HEADLESS}" = 1 ]; then
    options+=(-no-window)
else
    # キーボードの警告 (xkbcomp) が, 大量に出るので, 捨てる
    Xvfb "${DISPLAY}" -screen 0 1280x1024x24 2>/dev/null &
    sleep 1
    x11vnc -display "${DISPLAY}" -forever -shared -nopw -rfbport 5900 -quiet -bg
    websockify --web /usr/share/novnc 6080 localhost:5900 &
fi

echo "Starting the emulator (adb: emulator-5554)"
exec emulator "${options[@]}"
