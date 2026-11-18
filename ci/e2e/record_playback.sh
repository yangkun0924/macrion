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
WEB_APK="${3:-}"
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
  grep -E " (TapRecorder|TouchPassThroughOverlay|RecorderController|ShortcutLauncherActivity|PermissionsController|SmartAutoClickerService|LocalService|DetectorEngine|ScenarioProcessor|ScenarioSchedules|ScheduleReceiver|ElementTap[A-Za-z]*|ServiceActionExecutor|AndroidActionExecutor[A-Za-z]*|AndroidRuntime: (FATAL|Process|java)|System.err)" "$OUT/logcat.txt" \
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
adb root >/dev/null 2>&1; sleep 5; adb wait-for-device
adb install -r -g "$APK" || fail "install"
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put global heads_up_notifications_enabled 0
for _ in 1 2 3 4 5 6 7 8 9 10; do
  sleep 3
  adb shell dumpsys accessibility | grep -q "$PKG" && break
  # adbd restarts after "adb root": the settings may not have been written.
  adb wait-for-device
  adb shell settings put secure enabled_accessibility_services "$SVC"
  adb shell settings put secure accessibility_enabled 1
  adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
done
adb shell settings get secure enabled_accessibility_services
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
echo "--- touch recorder window"
adb shell dumpsys window windows | grep -A12 "MacrionTouchRecorder" | head -30
adb shell dumpsys input | grep -A12 "MacrionTouchRecorder" | head -30

# 3. A decoy app window shows up first (like Samsung Pass), then the real taps in Settings.
if [ -n "$DECOY" ]; then
  adb shell monkey -p $DECOY -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4; shot "decoy_opened"
  adb shell input keyevent KEYCODE_HOME; sleep 2
fi
adb shell monkey -p $TARGET -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5; adb shell input tap $P1; sleep 3; echo "after tap 1: $(focused_window)"; adb shell input tap $P2; sleep 3
echo "after tap 2: $(focused_window)"
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
adb logcat -d | grep -E " (TapRecorder|TouchPassThroughOverlay|RecorderController|ShortcutLauncherActivity|PermissionsController)" | tail -60
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

# 2. Schedule in "ask first" mode: a notification shows up, tapping Run starts the scenario.
reset_apps
log "fire a schedule in ASK mode"
adb shell am broadcast -n "$PKG/$RECEIVER" -a io.github.vibhor1102.macrion.schedule.TEST_FIRE \
  --el scenarioId "$ID" --es scenarioName e2e --es mode ASK | tee -a "$RESULT"
sleep 3
adb shell dumpsys notification --noredact | grep -q "Run e2e?" || fail "schedule notification not shown"
log "schedule notification shown"
adb shell cmd statusbar expand-notifications; sleep 3
dump "schedule_notification"
RUN=$(pos_in "$LAST" "^run$" "^실행$") || fail "'Run' button not found in the schedule notification"
sleep 3 # service binds again after the dump
adb shell input tap $RUN
check_playback "schedule_ask"

# 3. Schedule in automatic mode: the scenario starts by itself.
reset_apps
log "fire a schedule in AUTO mode"
adb shell am broadcast -n "$PKG/$RECEIVER" -a io.github.vibhor1102.macrion.schedule.TEST_FIRE \
  --el scenarioId "$ID" --es scenarioName e2e --es mode AUTO | tee -a "$RESULT"
check_playback "schedule_auto"

# 4. Schedule dialog: add one from the scenario card and check the alarm is set.
reset_apps
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
# The buttons are in the card details: expand the card first.
tap "card_expand" "^show details$" "^자세히 보기$" || fail "card expand button not found"
tap "schedule_button" "^schedule$" "^예약 실행$" || fail "'Schedule' button not found on the scenario card"
tap "schedule_add" "^add$" "^추가$" || fail "'Add' not found in the schedule dialog"
tap "schedule_time_ok" "^ok$" "^확인$" || fail "time picker OK not found"
tap "schedule_days_next" "^next$" "^다음$" || fail "days 'Next' not found"
tap "schedule_mode_save" "^save$" "^저장$" || fail "mode 'Save' not found"
dump "schedule_saved"
# The exact alarm permission dialog may show: dismiss it.
if pos=$(python3 "$HERE/ui.py" find "$LAST" "^(later|나중에)$"); then adb shell input tap $pos; sleep 1; fi
adb shell dumpsys alarm | grep -E "$PKG" | head -5 | tee -a "$RESULT"
adb shell dumpsys alarm | grep -q "schedule.FIRE" || adb shell dumpsys alarm | grep -q "$PKG" || fail "no alarm set for the schedule"
log "schedule alarm set"

# 5. Web page menu, like the company app: native screen -> "Order menu" -> web page -> "Coffee" tab -> scroll to the
#    bottom -> "Iced americano". The playback must scroll by itself to find the item.
if [ -n "$WEB_APK" ]; then
  WEB=io.github.e2e.coffee
  adb install -r "$WEB_APK" || fail "web app install"
  read -r W H < <(adb shell wm size | tr -d '\r' | sed -E 's/.* ([0-9]+)x([0-9]+)$/\1 \2/')
  SWIPE="$((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 400"
  adb shell input keyevent KEYCODE_HOME; sleep 2

  adb shell monkey -p $WEB -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4; dump "web_find_home"
  WP1=$(pos_in "$LAST" "^order menu$") || fail "web app 'Order menu' button not found"
  adb shell input tap $WP1; sleep 4
  WP2=""
  for _ in 1 2 3 4 5 6 7 8; do
    dump "web_find_menu"
    WP2=$(pos_in "$LAST" "^coffee$") && break
    sleep 4
  done
  if [ -z "$WP2" ]; then
    adb logcat -d | grep -iE "chromium|webview|cr_|e2e.coffee|AndroidRuntime" | tail -40
    fail "web page 'Coffee' tab not found"
  fi
  adb shell input tap $WP2; sleep 2
  for _ in 1 2 3 4 5 6 7 8; do adb shell input swipe $SWIPE; sleep 1; done
  dump "web_find_bottom"
  WP3=$(pos_in "$LAST" "^iced americano$") || fail "web page 'Iced americano' not found at the bottom"
  # The recording taps the price of the item: the same price is shown for another coffee, the recorder must record
  # the name of the item instead.
  WP3_PRICE=$(python3 "$HERE/ui.py" below "$LAST" "^1,500 won$" "${WP3#* }") || fail "price of 'Iced americano' not found"
  log "Iced americano price at $WP3_PRICE"
  adb shell input tap $WP3; sleep 2; dump "web_expected_end"
  python3 "$HERE/ui.py" has "$LAST" "^ordered: iced americano$" || fail "the web page did not take the order by hand"
  log "web taps: $WP1, $WP2, scroll, $WP3"
  adb shell am force-stop $WEB
  adb shell input keyevent KEYCODE_HOME; sleep 3

  # Record it.
  adb shell am force-stop $WEB
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  for _ in 1 2 3 4; do
    dump "web_macrion_start"
    python3 "$HERE/ui.py" has "$LAST" "^record taps$" && break
    pos=$(python3 "$HERE/ui.py" find "$LAST" "^(ok|got it|close|skip|later|not now|dismiss|cancel|확인|닫기)$") && adb shell input tap $pos && sleep 2 \
      || { adb shell input keyevent KEYCODE_BACK; sleep 2; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4; }
  done
  REC=$(pos_in "$LAST" "^record taps$") || fail "'Record taps' button not found before the web recording"
  for _ in 1 2 3 4 5; do sleep 3; adb shell dumpsys accessibility | grep -q "$PKG" && break; done
  adb shell dumpsys accessibility | grep -q "$PKG" || fail "accessibility service not bound before the web recording"
  sleep 3
  adb shell input tap $REC; sleep 3; shot "web_recording_started"
  adb shell dumpsys notification --noredact | grep -q "Recording your taps" || fail "web recording notification not shown"
  adb shell monkey -p $WEB -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4; adb shell input tap $WP1; sleep 6; echo "after web tap 1: $(focused_window)"; adb shell input tap $WP2; sleep 2
  for _ in 1 2 3 4 5 6 7; do adb shell input swipe $SWIPE; sleep 1; done
  # Last swipe is a quick flick and the tap comes right after it, while the page may still be moving: the recorder
  # must record the item really tapped.
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 120
  sleep 0.7 # a finger needs about that long to come back down
  adb shell input tap $WP3_PRICE; sleep 4; shot "web_recorded_end"
  adb shell am start -n "$PKG/io.github.vibhor1102.macrion.recorder.RecorderActionActivity" -a io.github.vibhor1102.macrion.recorder.STOP | tee -a "$RESULT"
  sleep 5; dump "web_after_save"
  if pos=$(python3 "$HERE/ui.py" find "$LAST" "^(add automatically|add|add to home screen)$"); then adb shell input tap $pos; sleep 2; fi
  WID=$(adb shell "sqlite3 /data/data/$PKG/databases/$DB 'select max(id) from scenario_table;'" | tr -d '\r')
  [ "$WID" != "$ID" ] || fail "the web recording was not saved"
  log "web scenario $WID actions:"
  adb shell "sqlite3 /data/data/$PKG/databases/$DB 'select a.name from action_table a join event_table e on a.eventId=e.id where e.scenario_id=$WID order by a.priority;'" | tee "$OUT/web_actions.txt" | tee -a "$RESULT"
  grep -qi "coffee$" "$OUT/web_actions.txt" || WEB_REC_FAIL="the 'Coffee' tab tap was not recorded"
  grep -qi "iced americano" "$OUT/web_actions.txt" || WEB_REC_FAIL="${WEB_REC_FAIL:-} the 'Iced americano' tap was not recorded"
  grep -qi "1,500 won" "$OUT/web_actions.txt" && WEB_REC_FAIL="${WEB_REC_FAIL:-} the price was recorded instead of the item name"
  grep -qiE "swipe|스와이프" "$OUT/web_actions.txt" || log "WARNING: no swipe recorded"
  echo "--- web recorder logcat"
  adb logcat -d | grep -E " (TapRecorder|TouchPassThroughOverlay)" | tail -60

  # Play it back.
  adb shell am force-stop $WEB
  adb shell input keyevent KEYCODE_HOME; sleep 3
  adb shell am start -n "$PKG/$LAUNCHER_ACTIVITY" --el "$EXTRA_ID" "$WID" | tee -a "$RESULT"
  sleep 4; shot "web_play_start"
  focused_window | grep -qiE "mediaprojection" && fail "[web] the 'share your screen' dialog showed up"
  sleep 35
  TOP=$(top_package); log "[web] app on screen after playback: $TOP"
  dump "web_play_end"
  [ "$TOP" = "$WEB" ] || fail "[web] playback opened '$TOP' instead of '$WEB'"
  python3 "$HERE/ui.py" has "$LAST" "^ordered: iced americano$" || fail "[web] playback did not order the iced americano"
  echo "--- web playback logcat"
  adb logcat -d | grep -E " (ElementTap[A-Za-z]*|ServiceActionExecutor)" | tail -30
  [ -z "${WEB_REC_FAIL:-}" ] || fail "[web] recording incomplete: $WEB_REC_FAIL"
  log "[web] OK: recorded and ordered by the playback"
fi

# Settings: the "Support" area (GitHub, Discord, bug report) and the crash reports page must be gone, and the
# open source notice must be shown in "About".
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -n "$PKG/io.github.vibhor1102.macrion.settings.SettingsActivity" | tee -a "$RESULT"
sleep 4
: > "$OUT/settings_texts.txt"
for i in 1 2 3 4 5 6; do
  dump "settings_$i"
  python3 "$HERE/ui.py" texts "$LAST" >> "$OUT/settings_texts.txt"
  adb shell input swipe 500 1600 500 400 300; sleep 2
done
# ui.py texts prints "<text> <resource-id> <bounds>": match the whole text only.
END=' +([^ ]+:id/[^ ]+ +)?\['
BANNED="^(support|visit github|join the discord server|report a bug|local crash reports)$END"
if grep -qiE "$BANNED" "$OUT/settings_texts.txt"; then
  fail "[settings] a support item is still shown: $(grep -iE "$BANNED" "$OUT/settings_texts.txt" | head -3 | tr '\n' ' ')"
fi
grep -qiE "^open source notice$END" "$OUT/settings_texts.txt" || fail "[settings] the open source notice is missing"
grep -qiE "^troubleshooting$END" "$OUT/settings_texts.txt" || log "WARNING: troubleshooting item not seen"
log "[settings] OK: no support area, open source notice shown"

adb logcat -d > "$OUT/logcat.txt"
log "PASS"
