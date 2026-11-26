"""Writes the adb steps of the playback scenarios (playback-test.yml) from fixture.json. Usage: playback_steps.py fixture.json"""
import json, sys

fx = json.load(open(sys.argv[1]))
Q = "'"
out = []
for u in fx["addons"]:
    out.append('adb shell am broadcast -a com.fof.tv.TAB --es addon "' + u + '"; sleep 4')
for s in fx["scenarios"]:
    # Outer double quotes for this shell, inner single quotes for the device shell ("|" and spaces)
    if s["good_key"]:
        k = s["good_key"].replace('"', "").replace(Q, "")
        out.append('adb shell "am broadcast -a com.fof.tv.TAB --es good ' + Q + s["id"] + "|" + k + Q + '"; sleep 1')
    arg = "|".join([s["id"], s["title"], str(s["year"]), str(s["resume_ms"])])
    out.append('adb shell "am broadcast -a com.fof.tv.TAB --es resume ' + Q + arg + Q + '"')
    out.append("sleep 55; adb exec-out screencap -p > p_" + s["id"] + ".png")
    out.append("adb shell input keyevent 4; sleep 3; adb shell input keyevent 4; sleep 3")
    # Back to the app in case the second Back left it (the test receiver lives with the activity)
    out.append("adb shell am start -n com.fof.tv/com.ipvio.stream.MainActivity; sleep 4")
print("\n".join(out))
