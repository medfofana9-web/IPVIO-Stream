#!/bin/bash
# Soak test steps (soak-test.yml): about an hour of normal use on the emulator, memory measured after every round.
# Each round: Home, Movies, TV Shows and Sports with some scrolling, a live channel for 30 s, a movie resumed for 40 s,
# then idle for 4.5 minutes so the screensaver comes on, then a key wakes it. Writes mem.txt ("round pid pss_kb java_kb native_kb graphics_kb").
ROUNDS=${ROUNDS:-8}
mem() {
  local pid; pid=$(adb shell pidof com.fof.tv | tr -d '\r')
  adb shell dumpsys meminfo com.fof.tv > m.txt
  local pss java native gfx
  pss=$(grep -E "TOTAL PSS:|^ *TOTAL:? " m.txt | head -1 | grep -oE "[0-9]+" | head -1)
  java=$(grep -E "Java Heap:" m.txt | grep -oE "[0-9]+" | head -1)
  native=$(grep -E "Native Heap:" m.txt | grep -oE "[0-9]+" | head -1)
  gfx=$(grep -E "Graphics:" m.txt | grep -oE "[0-9]+" | head -1)
  echo "$1 ${pid:-none} ${pss:-0} ${java:-0} ${native:-0} ${gfx:-0}" | tee -a mem.txt
}
adb shell am broadcast -a com.fof.tv.TAB --es addon "http://10.0.2.2:8002/a/manifest.json"; sleep 4
adb shell am broadcast -a com.fof.tv.TAB --es addon "http://10.0.2.2:8002/b/manifest.json"; sleep 4
mem 0
for r in $(seq 1 "$ROUNDS"); do
  for t in 0 1 2 10; do
    adb shell am broadcast -a com.fof.tv.TAB --ei tab $t; sleep 5
    for i in 1 2 3 4 5 6; do adb shell input keyevent 20; sleep 0.6; done
    for i in 1 2 3; do adb shell input keyevent 22; sleep 0.6; done
    sleep 3
  done
  adb shell am broadcast -a com.fof.tv.TAB --ei tab 0; sleep 3
  adb shell "am broadcast -a com.fof.tv.TAB --es live 'Fixture Live A'"; sleep 30
  adb shell input keyevent 4; sleep 3; adb shell input keyevent 4; sleep 3
  adb shell am start -n com.fof.tv/com.ipvio.stream.MainActivity; sleep 4
  adb shell "am broadcast -a com.fof.tv.TAB --es resume 'tt9000004|Fixture Four|2009|60000'"; sleep 40
  adb shell input keyevent 4; sleep 3; adb shell input keyevent 4; sleep 3
  adb shell am start -n com.fof.tv/com.ipvio.stream.MainActivity; sleep 4
  # Idle: the screensaver comes on after 4 minutes without a key
  sleep 270
  adb exec-out screencap -p > s_idle_$r.png
  adb shell input keyevent 20; sleep 4
  mem "$r"
done
