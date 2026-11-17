#!/usr/bin/env bash
# End to end test of the tap recorder on an emulator:
#   install -> enable accessibility -> "Record taps" -> open a decoy app (another window shows up first, like
#   Samsung Pass does on Samsung phones) -> open Settings and tap 2 items -> "Stop and save" from the notification
#   -> play the saved scenario -> Settings must be opened (not the decoy) and end on the same screen.
#
# usage: record_playback.sh <apk> <output dir>
set -u
APK="$1"
OUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=io.github.vibhor1102.macrion.debug
SVC=$PKG/io.github.vibhor1102.macrion.SmartAutoClickerService
LAUNCHER_ACTIVITY=io.github.vibhor1102.macrion.feature.externallaunch.shortcut.ShortcutLauncherActivity
EXTRA_ID=io.github.vibhor1102.macrion.feature.externallaunch.shortcut.EXTRA_SCENARIO_ID
TARGET=com.android.settings
mkdir -p "$OUT"
STEP=0
RESULT="$OUT/result.txt"
: > "$RESULT"

log() { echo "== $*" | tee -a "$RESULT"; }
fail() {
  log "FAIL: $*"; dump "fail"
  adb logcat -d > "$OUT/logcat.txt"
  echo "--- relevant logcat"
  grep -E " (TapRecorder|RecorderController|ShortcutLauncherActivity|PermissionsController|SmartAutoClickerService|LocalService|DetectorEngine|ScenarioProcessor|ScenarioSchedules|ScheduleReceiver|ElementTap[A-Za-z]*|AndroidActionExecutor[A-Za-z]*|AndroidRuntime: (FATAL|Process|java)|System.err)" "$OUT/logcat.txt" \
    | grep -v -E "StrictMode|chatty|RuntimeInit|boot image|lock profiling|Calling main|Shutting down|VM exiting" | tail -150
  exit 1
}

# Screenshot + UI dump, numbered so the artifact reads in order.
dump() {
  STEP=$((STEP + 1))
  local name; name=$(printf "%02d_%s" "$STEP" "$1")
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$name.xml" >/dev/null 2>&1
  adb exec-out screencap -p > "$OUT/$name.png"
  LAST="$OUT/$name.xml"
  echo "--- screen $name"; python3 "$HERE/ui.py" texts "$LAST" | head -40
}

# Tap the first node matching one of the regexes, after a fresh dump. Retries a few times.
tap() {
  local what="$1"; shift
  for _ in 1 2 3 4 5; do
    dump "$what"
    for rx in "$@"; do
      if pos=$(python3 "$HERE/ui.py" find "$LAST" "$rx"); then
        log "tap '$rx' at $pos"
        adb shell input tap $pos
        sleep 3
        return 0
      fi
    done
    sleep 2
  done
  return 1
}

top_package() {
  adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | sed -E 's/.* ([a-zA-Z0-9_.]+)\/.*/\1/'
}

log "install $APK"
adb root >/dev/null 2>&1; adb wait-for-device
adb install -r -g "$APK" || fail "install"
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put global heads_up_notifications_enabled 0
sleep 3
adb shell dumpsys accessibility | grep -q "$PKG" || fail "accessibility service not bound"
log "accessibility service enabled"

# Pick a decoy app that will show its window first while recording.
DECOY=""
for p in com.android.deskclock com.android.calculator2 com.android.contacts com.android.dialer com.android.camera2; do
  if adb shell pm list packages | tr -d '\r' | grep -qx "package:$p"; then DECOY=$p; break; fi
done
log "decoy app: ${DECOY:-none}"

# IMPORTANT: "uiautomator dump" connects a UiAutomation that suppresses every other accessibility service while it
# runs, which unbinds Macrion's service (and stops a recording). So the screens are only dumped while nothing of
# Macrion has to run: positions are found first, then the recording and the playbacks are driven blind.
shot() { STEP=$((STEP + 1)); adb exec-out screencap -p > "$OUT/$(printf "%02d_%s" "$STEP" "$1").png"; }
pos_in() { local f="$1"; shift; for rx in "$@"; do python3 "$HERE/ui.py" find "$f" "$rx" && return 0; done; return 1; }
focused_window() { adb shell dumpsys window | grep -m1 -E "mCurrentFocus" | tr -d '\r'; }

# 1. Find the Settings positions to tap, and the screen they end on.
adb shell monkey -p $TARGET -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5; dump "find_step1"
P1=$(pos_in "$LAST" "^network (&|and) internet$" "^connected devices$" "^apps$") || fail "first Settings item not found"
adb shell input tap $P1; sleep 3; dump "find_step2"
P2=$(pos_in "$LAST" "^data saver$" "^private dns$" "^hotspot (&|and) tethering$" "^internet$" "^pair new device$" "^see all .*apps$") \
  || fail "second Settings item not found"
adb shell input tap $P2; sleep 3; dump "expected_end_screen"
EXPECTED="$LAST"
log "Settings taps: $P1 then $P2"
adb shell am force-stop $TARGET
adb shell input keyevent KEYCODE_HOME; sleep 2

# 2. Start the recording from the app.
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 6
for _ in 1 2 3; do
  dump "macrion_start"
  python3 "$HERE/ui.py" has "$LAST" "^record taps$" && break
  pos=$(python3 "$HERE/ui.py" find "$LAST" "^(ok|got it|close|skip|later|not now|dismiss|continue|확인|닫기)$") && adb shell input tap $pos && sleep 2 || break
done
REC=$(pos_in "$LAST" "^record taps$") || fail "'Record taps' button not found"
sleep 3 # let the accessibility service bind again after the dump
adb shell dumpsys accessibility | grep -q "$PKG" || fail "accessibility service not bound before recording"
log "tap 'Record taps' at $REC"
adb shell input tap $REC
sleep 3; shot "recording_started"
adb shell dumpsys notification --noredact | grep -q "Recording your taps" || fail "recording notification not shown"
log "recording notification shown"

# 3. A decoy app window shows up first (like Samsung Pass), then the real taps in Settings.
if [ -n "$DECOY" ]; then
  adb shell monkey -p $DECOY -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4; shot "decoy_opened"
  adb shell input keyevent KEYCODE_HOME; sleep 2
fi
adb shell monkey -p $TARGET -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5; adb shell input tap $P1; sleep 3; adb shell input tap $P2; sleep 3
shot "recorded_end_screen"

# 4. Stop and save: same activity as the notification "Stop and save" button.
log "stop and save"
adb shell am start -n "$PKG/io.github.vibhor1102.macrion.recorder.RecorderActionActivity" -a io.github.vibhor1102.macrion.recorder.STOP | tee -a "$RESULT"
sleep 5
dump "after_save"
# The app asks the launcher to pin a home screen shortcut: accept it when the dialog shows.
if pos=$(python3 "$HERE/ui.py" find "$LAST" "^(add automatically|add|add to home screen)$"); then
  adb shell input tap $pos; sleep 2; log "home screen shortcut accepted"
fi

echo "--- recorder logcat"
adb logcat -d | grep -E " (TapRecorder|RecorderController|ShortcutLauncherActivity|PermissionsController)" | tail -60
DB=$(adb shell ls /data/data/$PKG/databases/ | tr -d '\r' | grep -v -E "journal|wal|shm" | head -1)
log "database: $DB"
adb shell "sqlite3 /data/data/$PKG/databases/$DB '.tables'" | tee -a "$RESULT"
adb shell "sqlite3 /data/data/$PKG/databases/$DB 'select id,name from scenario_table;'" | tee -a "$RESULT"
adb shell "sqlite3 /data/data/$PKG/databases/$DB 'select * from action_table;'" | tee "$OUT/actions.txt" | tee -a "$RESULT"
ID=$(adb shell "sqlite3 /data/data/$PKG/databases/$DB 'select max(id) from scenario_table;'" | tr -d '\r')
if ! [[ "$ID" =~ ^[0-9]+$ ]]; then
  log "WARNING: could not read the database ($ID), assuming the first scenario of a fresh install"
  ID=1
fi
log "saved scenario id $ID"

reset_apps() {
  adb shell am force-stop $TARGET
  [ -n "$DECOY" ] && adb shell am force-stop $DECOY
  adb shell input keyevent KEYCODE_HOME; sleep 2
}

# Check that a playback started directly (no screen sharing dialog) and ended on the recorded screen.
check_playback() {
  local label="$1"
  sleep 4
  shot "${label}_start"
  local focus; focus=$(focused_window)
  echo "focus: $focus"
  if echo "$focus" | grep -qiE "mediaprojection|MediaProjectionPermission"; then
    fail "[$label] the 'share your screen' dialog showed up, playback must start directly"
  fi
  log "[$label] no screen sharing dialog"
  sleep 20
  TOP=$(top_package)
  log "[$label] app on screen after playback: $TOP"
  dump "${label}_end_screen"
  [ "$TOP" = "$TARGET" ] || fail "[$label] playback opened '$TOP' instead of '$TARGET'"
  SIMILARITY=$(python3 - "$EXPECTED" "$LAST" <<'EOF2'
import sys, xml.etree.ElementTree as ET
def texts(p):
    return {n.get("text") for n in ET.parse(p).getroot().iter("node") if n.get("text")}
a, b = texts(sys.argv[1]), texts(sys.argv[2])
print(round(len(a & b) / max(1, len(a | b)), 2))
EOF2
)
  log "[$label] end screen similarity with the recorded one: $SIMILARITY"
  python3 -c "import sys; sys.exit(0 if float('$SIMILARITY') >= 0.6 else 1)" || fail "[$label] playback did not end on the recorded screen"
  sleep 3 # service binds again after the dump
  log "[$label] OK"
}

RECEIVER=io.github.vibhor1102.macrion.feature.externallaunch.schedule.ScheduleReceiver

# 1. Home screen shortcut entry point.
reset_apps
log "play scenario $ID (same entry point as the home screen shortcut)"
adb shell am start -n "$PKG/$LAUNCHER_ACTIVITY" --el "$EXTRA_ID" "$ID" | tee -a "$RESULT"
check_playback "shortcut"

log "PASS"
