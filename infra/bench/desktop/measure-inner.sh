#!/usr/bin/env bash
set -euo pipefail
cgroup=/sys/fs/cgroup
memory() { awk '{ printf "%.0f", $1 / 1048576 }' "$cgroup/memory.current"; }
pids() { cat "$cgroup/pids.current"; }
settle() { sleep "${1:-5}"; sync; }

baseline_memory=$(memory)
baseline_pids=$(pids)
started=$(date +%s%N)
Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
until xdpyinfo -display :99 >/dev/null 2>&1; do sleep 0.05; done
eval "$(dbus-launch --sh-syntax)"
openbox >/tmp/openbox.log 2>&1 &
until xprop -root _NET_CLIENT_LIST 2>/dev/null | grep -q "^_NET_CLIENT_LIST(WINDOW)"; do sleep 0.05; done
ready_ms=$(( ($(date +%s%N) - started) / 1000000 ))
settle 3
display_memory=$(memory)
display_pids=$(pids)

pointer-chromium --user-data-dir=/tmp/profile "--app=file:///bench/apps/form-web/index.html" >/tmp/chromium.log 2>&1 &
until xdotool search --onlyvisible --name form-web >/dev/null 2>&1; do sleep 0.2; done
settle 8
chromium_memory=$(memory)
chromium_pids=$(pids)
pkill -f chromium || true
settle 2

python3 /bench/apps/tk-counter/app.py /tmp/tk.json >/tmp/tk.log 2>&1 &
until xdotool search --onlyvisible --name "Tk Counter" >/dev/null 2>&1; do sleep 0.1; done
settle 2
tk_memory=$(memory)
pkill -f tk-counter || true

pointer-chromium --user-data-dir=/tmp/profile2 "--app=file:///bench/apps/form-web/index.html" >/tmp/chromium2.log 2>&1 &
cargo new --quiet /tmp/build-load
cd /tmp/build-load
cat >> Cargo.toml <<'TOML'
serde = { version = "1", features = ["derive"] }
serde_json = "1"
regex = "1"
tokio = { version = "1", features = ["full"] }
TOML
build_started=$(date +%s)
peak=0
cargo build --quiet --jobs "$(nproc)" >/tmp/build.log 2>&1 &
build=$!
while kill -0 "$build" 2>/dev/null; do
  current=$(pids)
  [ "$current" -gt "$peak" ] && peak=$current
  sleep 0.2
done
build_seconds=$(( $(date +%s) - build_started ))
peak_recorded=$(cat "$cgroup/pids.peak" 2>/dev/null || echo "$peak")

printf 'baseline_memory_mb=%s\nbaseline_pids=%s\ndisplay_ready_ms=%s\ndisplay_memory_mb=%s\ndisplay_pids=%s\nchromium_memory_mb=%s\nchromium_pids=%s\ntk_memory_mb=%s\nbuild_with_chromium_peak_pids=%s\ncgroup_pids_peak=%s\nbuild_seconds=%s\n' \
  "$baseline_memory" "$baseline_pids" "$ready_ms" "$display_memory" "$display_pids" "$chromium_memory" "$chromium_pids" "$tk_memory" "$peak" "$peak_recorded" "$build_seconds"
