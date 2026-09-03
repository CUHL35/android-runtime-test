#!/usr/bin/env bash
set -euo pipefail
APK="${1:?apk path required}"
PKG="vn.trolychoba"
OUT="runtime-results"
mkdir -p "$OUT/screens"

adb wait-for-device
adb shell getprop ro.build.version.sdk | tee "$OUT/api-level.txt"
test "$(tr -d '\r' < "$OUT/api-level.txt")" = "31"
adb logcat -c
adb install -r "$APK" | tee "$OUT/install.txt"

# Grant only permissions Android 12 allows shell to grant. Failures are evidence, not harness failures.
for p in android.permission.RECORD_AUDIO android.permission.READ_PHONE_STATE android.permission.ANSWER_PHONE_CALLS android.permission.BLUETOOTH_CONNECT; do
  adb shell pm grant "$PKG" "$p" >>"$OUT/permission-grants.txt" 2>&1 || true
done
adb shell dumpsys deviceidle whitelist +"$PKG" >>"$OUT/permission-grants.txt" 2>&1 || true

# Enable the two user-controlled services so their runtime paths can execute on the emulator.
adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.AssistService" || true
adb shell settings put secure accessibility_enabled 1 || true
adb shell cmd notification allow_listener "$PKG/$PKG.NotificationReader" >>"$OUT/notification-listener.txt" 2>&1 || true
sleep 2

snapshot() {
  local name="$1"
  adb exec-out screencap -p > "$OUT/screens/${name}.png" || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/window.xml > "$OUT/screens/${name}.xml" 2>/dev/null || true
  adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' > "$OUT/screens/${name}.focus.txt" || true
}

launch_main() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/activity-starts.txt" 2>&1
  sleep 1
}

launch_main
snapshot main

# Lifecycle/regression passes.
adb shell input keyevent KEYCODE_HOME
sleep 1
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >> "$OUT/lifecycle.txt" 2>&1 || true
sleep 1
snapshot foreground_after_home
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 1 || true
sleep 1
snapshot rotated_landscape
adb shell settings put system user_rotation 0 || true
sleep 1
adb shell am force-stop "$PKG" || true
adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/lifecycle.txt" 2>&1 || true
sleep 1
snapshot relaunch_after_forcestop

# Exercise exported shortcut/assistant entry points independently.
for component in ScreenOffPlayPauseActivity ScreenOffNextActivity PowerAssistantActivity; do
  adb shell am start -W -n "$PKG/.$component" >> "$OUT/exported-entrypoints.txt" 2>&1 || true
  sleep 1
  snapshot "entry_${component}"
  launch_main
done

# Clickable UI crawl: main screen plus one nested level. Avoid destructive uninstall/delete/reset actions.
python3 - <<'PY'
import subprocess, xml.etree.ElementTree as ET, re, time, os, hashlib
PKG='vn.trolychoba'
OUT='runtime-results'
os.makedirs(f'{OUT}/screens', exist_ok=True)

def sh(*args, check=False):
    p=subprocess.run(['adb',*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    if check and p.returncode: raise RuntimeError(p.stdout)
    return p.stdout

def launch():
    sh('shell','am','force-stop',PKG)
    sh('shell','am','start','-W','-n',f'{PKG}/.MainActivity')
    time.sleep(.7)

def dump(tag):
    sh('shell','uiautomator','dump','/sdcard/window.xml')
    xml=sh('shell','cat','/sdcard/window.xml')
    open(f'{OUT}/screens/{tag}.xml','w').write(xml)
    with open(f'{OUT}/screens/{tag}.png','wb') as f:
        p=subprocess.run(['adb','exec-out','screencap','-p'],stdout=f)
    focus=sh('shell','dumpsys','window','windows')
    focus='\n'.join(x for x in focus.splitlines() if 'mCurrentFocus' in x or 'mFocusedApp' in x)
    open(f'{OUT}/screens/{tag}.focus.txt','w').write(focus)
    return xml,focus

def nodes(xml):
    try: root=ET.fromstring(xml)
    except Exception: return []
    out=[]
    for n in root.iter('node'):
        if n.attrib.get('clickable')!='true': continue
        b=n.attrib.get('bounds','')
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
        if not m: continue
        x1,y1,x2,y2=map(int,m.groups())
        if x2<=x1 or y2<=y1: continue
        label=(n.attrib.get('text') or n.attrib.get('content-desc') or n.attrib.get('resource-id') or '').strip()
        out.append((label,(x1+x2)//2,(y1+y2)//2,b))
    return out

bad=('gỡ cài','xóa dữ liệu','xóa tất cả','factory','reset','xoá dữ liệu')
log=[]
launch()
xml,_=dump('crawl_root')
root_nodes=nodes(xml)[:45]
for i,(label,x,y,b) in enumerate(root_nodes):
    if any(k in label.lower() for k in bad):
        log.append(f'SKIP destructive root {i}: {label} {b}')
        continue
    launch()
    sh('shell','input','tap',str(x),str(y)); time.sleep(.8)
    xml2,focus=dump(f'crawl_root_{i:02d}')
    log.append(f'ROOT {i}: {label!r} {b} -> {focus}')
    # If still in app, exercise a bounded nested level.
    if PKG in focus:
        for j,(lab2,x2,y2,b2) in enumerate(nodes(xml2)[:12]):
            if any(k in lab2.lower() for k in bad):
                log.append(f'  SKIP destructive nested {j}: {lab2} {b2}')
                continue
            sh('shell','input','tap',str(x2),str(y2)); time.sleep(.5)
            _,focus2=dump(f'crawl_root_{i:02d}_nested_{j:02d}')
            log.append(f'  NEST {j}: {lab2!r} {b2} -> {focus2}')
            sh('shell','input','keyevent','KEYCODE_BACK'); time.sleep(.35)
            # If an external Settings/app consumed back, relaunch the root path is safer than guessing state.
            cur=sh('shell','dumpsys','window','windows')
            if PKG not in cur:
                break
open(f'{OUT}/ui-crawl.txt','w').write('\n'.join(log))
PY

# Randomized input to catch crashes/ANRs after deterministic flow checks.
launch_main
adb shell monkey -p "$PKG" --throttle 80 --pct-syskeys 0 --pct-appswitch 0 -v 150 > "$OUT/monkey.txt" 2>&1 || true
sleep 2
snapshot after_monkey

# Persist runtime/state evidence.
adb shell dumpsys package "$PKG" > "$OUT/dumpsys-package.txt" || true
adb shell dumpsys activity services "$PKG" > "$OUT/dumpsys-services.txt" || true
adb shell dumpsys notification --noredact > "$OUT/dumpsys-notification.txt" || true
adb shell settings get secure enabled_accessibility_services > "$OUT/accessibility-enabled.txt" || true
adb shell settings get secure enabled_notification_listeners > "$OUT/notification-listeners.txt" || true
adb logcat -d -v threadtime > "$OUT/logcat-full.txt"
grep -E 'FATAL EXCEPTION|ANR in |AndroidRuntime|SecurityException|Process: vn\.trolychoba|vn\.trolychoba.*Exception' "$OUT/logcat-full.txt" > "$OUT/logcat-critical.txt" || true

# Fail CI only on confirmed app crash/ANR. SecurityException is retained for audit because some OS-setting paths intentionally reject shell/app access.
if grep -Eq 'FATAL EXCEPTION|ANR in vn\.trolychoba|Process: vn\.trolychoba' "$OUT/logcat-critical.txt"; then
  echo 'Confirmed app crash/ANR found' >&2
  exit 20
fi
