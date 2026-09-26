#!/usr/bin/env bash
# Boots the API-34 'gate34' emulator headless with KVM and waits for boot.
# Copied verbatim from gate.yml's "Boot emulator" step so PR workflows can
# reuse it; gate.yml itself is unchanged.
set -uo pipefail
export ANDROID_HOME=/usr/local/lib/android/sdk
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
sudo gpasswd -a "$USER" kvm || true
echo "no" | avdmanager --verbose create avd --force -n gate34 \
  --package "system-images;android-34;google_apis;x86_64" --device "pixel_6" || true
if [ ! -f ~/.android/avd/gate34.ini ]; then
  echo "avdmanager did not create gate34; writing AVD config manually"
  mkdir -p ~/.android/avd/gate34.avd
  SYSIMG="system-images/android-34/google_apis/x86_64"
  cat > ~/.android/avd/gate34.ini << EOF
avd.ini.encoding=UTF-8
path=$HOME/.android/avd/gate34.avd
path.rel=avd/gate34.avd
target=android-34
EOF
  cat > ~/.android/avd/gate34.avd/config.ini << EOF
avd.ini.encoding=UTF-8
AvdId=gate34
PlayStore.enabled=false
abi.type=x86_64
avd.ini.displayname=gate34
disc.cachePartition=true
disc.cachePartition.size=66MB
hw.accelerometer=yes
hw.audioInput=yes
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.device.name=pixel_6
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.initialOrientation=Portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=3072
hw.sdCard=yes
image.sysdir.1=$SYSIMG/
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=no
skin.name=1080x2400
skin.path=_no_skin
tag.display=Google APIs
tag.id=google_apis
vm.heapSize=228
EOF
fi
test -f ~/.android/avd/gate34.ini || { echo "AVD CREATION FAILED"; exit 1; }
echo "AVD ready"
sg kvm -c "nohup $ANDROID_HOME/emulator/emulator -avd gate34 -no-window -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim -camera-back none > /tmp/emu.log 2>&1 &"
sleep 20
for i in $(seq 1 100); do
  if adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
    echo "BOOTED after ~$((i*15+20))s"; break
  fi
  if [ $((i % 8)) -eq 0 ]; then echo "still waiting... ${i}x15s"; tail -2 /tmp/emu.log | cut -c1-160; fi
  sleep 15
done
adb shell getprop sys.boot_completed | grep -q 1 || { echo "BOOT TIMEOUT"; tail -30 /tmp/emu.log; exit 1; }
adb shell input keyevent 82 || true
sleep 3
