#!/usr/bin/env node
/**
 * openlight-camera MCP server.
 *
 * Drives a Light L16 over adb: shutter, focus and the manual controls the app
 * already exposes through its CI test-intent surface, plus logs and capture
 * retrieval.
 *
 * Control path (reverse-engineered from TestIntentManager / TestIntentReceiver):
 *   broadcast openlight.co.intent.CITEST_INTENT with string extras whose keys
 *   come from the CITest enum. startTest() walks the bundle keys, matches each
 *   against CITest.getTestKey() and dispatches the value.
 *
 * The receiver is registered at runtime by ImagePreviewFragment, so the camera
 * app has to be in the foreground for control calls to land. enable_test_mode
 * covers the other precondition: TestIntentManager's constructor requires
 * /sdcard/fkitten.txt or /sdcard/lightest.txt to exist, gated by the
 * feature.cli_support flag (default true).
 *
 * Beyond the intents: screenshot / tap / swipe / key / ui_tree / tap_text for the UI,
 * camera_request for the last CaptureRequest the HAL actually received (dumpsys
 * media.camera — the ground truth for effect mode, focal length, AF trigger),
 * record_preview for per-frame colour saturation of the viewfinder, set_feature to
 * edit one features.prop key in place, restart_app to apply it.
 *
 * Gotchas measured on the device:
 *   - mode_test wants the mode name, e.g. "mono", "auto", "manual". Before 2026.10.15.0
 *     the label match was case-sensitive and "AUTO" killed the app (System.exit(2)).
 *   - The mode menu is drawn by hand: it has no text nodes for tap_text. Use mode_test.
 *   - The right screen edge is the system back gesture; swipes there leave the app.
 *   - LogUtil.d/v are silent unless debug_on_user=true (set_feature + restart_app).
 *
 * Transport: MCP over stdio, newline-delimited JSON-RPC 2.0. No dependencies beyond
 * adb, plus sips (screenshot) and ffmpeg (record_preview) on the host.
 *
 * Env:
 *   OPENLIGHT_ADB_SERIAL  target device (default: the only connected one)
 *   OPENLIGHT_PACKAGE     app package (default: openlight.co.lightcamera)
 */

import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const PROTOCOL_VERSION = "2025-06-18";
const SERVER_INFO = { name: "openlight-camera", version: "1.1.0" };

const BETA_PACKAGE = process.env.OPENLIGHT_PACKAGE || "openlight.co.lightcamera";
const STOCK_PACKAGE = "light.co.lightcamera";
const LAUNCH_ACTIVITY = "openlight.co.camera.CameraActivity";
const CITEST_ACTION = "openlight.co.intent.CITEST_INTENT";
const DCIM = "/sdcard/DCIM/Camera";
const FEATURES = "/sdcard/features.prop";

/** CITest enum → bundle key, with the value each dispatch expects. */
const CONTROLS = {
  iso: { key: "iso_test", hint: "ISO index, or 'auto'" },
  exposure_time: { key: "exposure_test", hint: "shutter, e.g. '1/60' or '0.5'" },
  ev: { key: "ev_test", hint: "exposure compensation index" },
  flash: { key: "flash_test", hint: "on | off | auto" },
  metering: { key: "metering_test", hint: "center | center-weighted | spot | touch-weighted" },
  mode: { key: "mode_test", hint: "mode name: auto | mono | manual | iso | shutter | video" },
  timer: { key: "timer_test", hint: "3 | 5 | 10 | 20 | off" },
  burst: { key: "burst_test", hint: "on | off" },
  caf: { key: "caf_test", hint: "continuous AF mode" },
  audio: { key: "audio_test", hint: "shutter audio setting" },
  focal_length: { key: "focal_length_test", hint: "focal length in mm, e.g. '28'" },
};

// ---------------------------------------------------------------- adb helpers

function adbArgs(args) {
  const serial = process.env.OPENLIGHT_ADB_SERIAL;
  return serial ? ["-s", serial, ...args] : args;
}

async function adb(args, { timeout = 60000, maxBuffer = 32 * 1024 * 1024 } = {}) {
  try {
    const { stdout, stderr } = await execFileAsync("adb", adbArgs(args), { timeout, maxBuffer });
    return { ok: true, stdout: stdout ?? "", stderr: stderr ?? "" };
  } catch (err) {
    return {
      ok: false,
      stdout: err.stdout ?? "",
      stderr: err.stderr ?? String(err.message ?? err),
    };
  }
}

const sh = (script, opts) => adb(["shell", script], opts);

/** Raw bytes from `adb exec-out`, for screenshots: `shell` would mangle binary on this adbd. */
async function adbBytes(args, { timeout = 30000 } = {}) {
  const { stdout } = await execFileAsync("adb", adbArgs(["exec-out", ...args]), {
    timeout,
    encoding: "buffer",
    maxBuffer: 64 * 1024 * 1024,
  });
  return stdout;
}

async function host(cmd, args, { timeout = 120000 } = {}) {
  const { stdout, stderr } = await execFileAsync(cmd, args, { timeout, maxBuffer: 64 * 1024 * 1024 });
  return { stdout: stdout ?? "", stderr: stderr ?? "" };
}

/** Quote a value for safe interpolation into an adb shell command line. */
const q = (value) => `'${String(value).replace(/'/g, `'\\''`)}'`;

// ------------------------------------------------------------------ tool impl

async function requireDevice() {
  const { stdout } = await adb(["devices"]);
  const lines = stdout
    .split("\n")
    .slice(1)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith("*"));
  const ready = lines.filter((l) => l.endsWith("\tdevice") || / device$/.test(l));
  if (ready.length === 0) {
    throw new Error(
      lines.length
        ? `No device in 'device' state:\n${lines.join("\n")}`
        : "No device connected. Check the USB cable and that adb sees the L16."
    );
  }
  return ready.map((l) => l.split(/\s+/)[0]);
}

async function deviceInfo() {
  const serials = await requireDevice();
  const props = [
    "ro.product.model",
    "ro.build.version.release",
    "ro.build.display.id",
    "ro.build.type",
    // ro.boot.flash.locked is unset on this device; verified boot and dm-verity
    // are what actually establish that /system cannot be modified.
    "ro.boot.verifiedbootstate",
    "ro.boot.veritymode",
  ];
  // Emit key=value per line: a property with an empty value must not shift the
  // rest of the list, which a bare `getprop` sequence would do.
  const { stdout: propOut } = await sh(props.map((p) => `echo "${p}=$(getprop ${p})"`).join("; "));
  const values = new Map(
    propOut
      .split("\n")
      .map((l) => l.trim())
      .filter(Boolean)
      .map((l) => {
        const eq = l.indexOf("=");
        return [l.slice(0, eq), l.slice(eq + 1)];
      })
  );
  const { stdout: dfOut } = await sh("df /sdcard | tail -1");

  const lines = [`serial: ${serials.join(", ")}`];
  props.forEach((p) => lines.push(`${p}: ${values.get(p) || "(unset)"}`));
  lines.push(`storage: ${dfOut.trim()}`);
  return lines.join("\n");
}

async function appStatus() {
  await requireDevice();
  const out = [];
  for (const [label, pkg] of [["beta", BETA_PACKAGE], ["stock", STOCK_PACKAGE]]) {
    const { stdout: listed } = await sh(`pm list packages ${q(pkg)}`);
    const installed = listed.includes(pkg);
    let detail = "not installed";
    if (installed) {
      const { stdout: dump } = await sh(`dumpsys package ${q(pkg)} | grep -E 'versionName|codePath'`);
      const { stdout: ps } = await sh(`ps | grep ${q(pkg)} | grep -v grep`);
      detail = `${dump.trim().replace(/\s+/g, " ")} | ${ps.trim() ? "running" : "not running"}`;
    }
    out.push(`${label} (${pkg}): ${detail}`);
  }
  const { stdout: focus } = await sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'");
  out.push(`foreground: ${focus.trim() || "unknown"}`);
  // The L16 runs hot with the preview open; 45 °C is where AGENTS.md says to stop.
  const { stdout: batt } = await sh("dumpsys battery | grep -E 'level|temperature'");
  const temp = /temperature:\s*(\d+)/.exec(batt);
  const level = /level:\s*(\d+)/.exec(batt);
  out.push(
    `battery: ${level ? level[1] + "%" : "?"}, ${temp ? (Number(temp[1]) / 10).toFixed(1) + " °C" : "?"}`
  );
  return out.join("\n");
}

/**
 * Set one key in /sdcard/features.prop without touching the rest of the file. The file
 * on the camera is the full commented copy from the repo and both the stock app and
 * ours read it, so rewriting it wholesale would silently reset every other flag. The
 * first edit keeps a copy as features.prop.orig.
 */
async function setFeatureKey(key, value) {
  if (!/^[A-Za-z0-9_.]+$/.test(key)) throw new Error(`bad key '${key}'`);
  if (/[\n'\\]/.test(String(value))) throw new Error("value must be a single plain line");
  const script = [
    `f=${FEATURES}`,
    `[ -f "$f" ] || touch "$f"`,
    `[ -f "$f.orig" ] || cp "$f" "$f.orig"`,
    // Toybox sed on Android 6 has no -i; rebuild through a temp file.
    `if grep -q '^[#]*${key}=' "$f"; then sed 's|^[#]*${key}=.*|${key}=${value}|' "$f" > "$f.tmp" && cat "$f.tmp" > "$f" && rm "$f.tmp"; else echo '${key}=${value}' >> "$f"; fi`,
    `grep '^${key}=' "$f"`,
  ].join("; ");
  const res = await sh(script);
  if (!res.ok) throw new Error(res.stderr.trim() || "edit failed");
  return res.stdout.trim();
}

async function setFeature({ key, value }) {
  await requireDevice();
  const line = await setFeatureKey(key, value);
  return `${line}\n(restart the camera app to apply — restart_app)`;
}

async function enableTestMode({ debug_logs = true }) {
  await requireDevice();
  const steps = [];

  const marker = await sh("touch /sdcard/lightest.txt && ls -l /sdcard/lightest.txt");
  steps.push(`marker: ${marker.ok ? marker.stdout.trim() : `FAILED ${marker.stderr.trim()}`}`);

  steps.push(`features.prop: ${await setFeatureKey("feature.cli_support", "true")}`);
  if (debug_logs) {
    // debug_on_user flips CommonConstants.IS_USER_BUILD, which gates LogUtil.d/v.
    steps.push(`features.prop: ${await setFeatureKey("debug_on_user", "true")}`);
  }

  steps.push("NOTE: restart the camera app for these to take effect (restart_app).");
  return steps.join("\n");
}

async function launchApp({ target = "beta" }) {
  await requireDevice();
  const pkg = target === "stock" ? STOCK_PACKAGE : BETA_PACKAGE;
  const activity =
    target === "stock" ? "light.co.camera.CameraActivity" : `${pkg}/${LAUNCH_ACTIVITY}`;
  const component = target === "stock" ? `${pkg}/${activity}` : activity;
  const res = await sh(`am start -n ${q(component)}`);
  return res.ok ? res.stdout.trim() || "started" : `FAILED: ${res.stderr.trim()}`;
}

async function sendTestIntent(extras) {
  await requireDevice();
  const pairs = Object.entries(extras)
    .map(([k, v]) => `--es ${k} ${q(v)}`)
    .join(" ");
  const res = await sh(`am broadcast -a ${CITEST_ACTION} ${pairs}`);
  if (!res.ok) return `FAILED: ${res.stderr.trim()}`;
  const out = res.stdout.trim();
  // A delivered broadcast reports result=0; nothing here proves the app acted
  // on it, so surface the raw result and let the caller check logs.
  return `${out}\n(sent ${JSON.stringify(extras)}; app must be in foreground to receive)`;
}

async function capture() {
  return sendTestIntent({ capture_test: "1" });
}

async function focus({ x, y }) {
  const value = x != null && y != null ? `${x},${y}` : "1";
  return sendTestIntent({ focus_test: value });
}

async function setControl({ control, value }) {
  const spec = CONTROLS[control];
  if (!spec) {
    throw new Error(`Unknown control '${control}'. Known: ${Object.keys(CONTROLS).join(", ")}`);
  }
  return sendTestIntent({ [spec.key]: value });
}

async function logs({ lines = 200, filter, clear = false, since_app_only = true }) {
  await requireDevice();
  if (clear) await adb(["logcat", "-c"]);

  const { stdout } = await adb(["logcat", "-d", "-v", "time"], { maxBuffer: 64 * 1024 * 1024 });
  let out = stdout.split("\n");

  if (since_app_only) {
    // App tags carry no package prefix, so match on the PID of our process.
    const { stdout: ps } = await sh(`ps | grep ${q(BETA_PACKAGE)} | grep -v grep`);
    const pid = ps.trim().split(/\s+/)[1];
    if (pid) out = out.filter((l) => l.includes(`(${pid.padStart(5)})`) || l.includes(`( ${pid})`));
  }
  if (filter) {
    const re = new RegExp(filter, "i");
    out = out.filter((l) => re.test(l));
  }
  const tail = out.slice(-lines).join("\n");
  return tail.trim() || "(no matching log lines — is debug_on_user set and the app restarted?)";
}

async function listCaptures({ limit = 30 }) {
  await requireDevice();
  const { stdout } = await sh(`ls -l ${DCIM} | tail -${Number(limit) + 1}`);
  const { stdout: counts } = await sh(
    `for e in lri jpg mp4; do printf '%s: ' "$e"; find ${DCIM} -maxdepth 1 -name "*.$e" | wc -l; done`
  );
  return `${counts.trim()}\n\n${stdout.trim()}`;
}

async function pullCapture({ name, dest }) {
  await requireDevice();
  if (!name || /[/\s]/.test(name)) throw new Error("name must be a bare filename in DCIM/Camera");
  const target = dest || `./${name}`;
  const res = await adb(["pull", `${DCIM}/${name}`, target], { timeout: 600000 });
  return res.ok ? res.stdout.trim() : `FAILED: ${res.stderr.trim()}`;
}

async function restartApp() {
  await requireDevice();
  await sh(`am force-stop ${q(BETA_PACKAGE)}`);
  const res = await sh(`am start -W -n ${q(`${BETA_PACKAGE}/${LAUNCH_ACTIVITY}`)}`);
  return res.ok ? res.stdout.trim() : `FAILED: ${res.stderr.trim()}`;
}

/** PNG of the screen, downscaled on the host so it fits a tool result. */
async function screenshot({ scale = 0.5 }) {
  await requireDevice();
  const png = await adbBytes(["screencap", "-p"]);
  const dir = await mkdtemp(join(tmpdir(), "openlight-"));
  try {
    const raw = join(dir, "raw.png");
    const out = join(dir, "shot.jpg");
    await writeFile(raw, png);
    const width = Math.max(160, Math.round(1920 * Number(scale)));
    await host("sips", ["-Z", String(width), "-s", "format", "jpeg", "-s", "formatOptions", "75", raw, "--out", out]);
    const data = (await readFile(out)).toString("base64");
    return {
      content: [
        { type: "image", data, mimeType: "image/jpeg" },
        { type: "text", text: `screen 1920×1080, image scaled to ${width} px wide — multiply coordinates by ${(1920 / width).toFixed(3)} for tap` },
      ],
    };
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

async function tap({ x, y }) {
  await requireDevice();
  const res = await sh(`input tap ${Math.round(x)} ${Math.round(y)}`);
  return res.ok ? `tapped ${Math.round(x)},${Math.round(y)}` : `FAILED: ${res.stderr.trim()}`;
}

async function swipe({ x1, y1, x2, y2, duration_ms = 300 }) {
  await requireDevice();
  const res = await sh(
    `input swipe ${[x1, y1, x2, y2, duration_ms].map((v) => Math.round(v)).join(" ")}`
  );
  return res.ok ? "swiped" : `FAILED: ${res.stderr.trim()}`;
}

async function key({ key }) {
  await requireDevice();
  if (!/^[A-Z0-9_]+$/.test(key)) throw new Error("key must be a KEYCODE_* name or number");
  const res = await sh(`input keyevent ${key}`);
  return res.ok ? `sent ${key}` : `FAILED: ${res.stderr.trim()}`;
}

/** Visible view nodes with text or an id, from uiautomator. */
async function uiNodes() {
  const dump = await sh("uiautomator dump /sdcard/openlight-ui.xml >/dev/null 2>&1; cat /sdcard/openlight-ui.xml", {
    timeout: 30000,
  });
  const xml = dump.stdout;
  if (!xml.includes("<node")) {
    throw new Error(`uiautomator gave no tree: ${(dump.stderr || xml).trim().slice(0, 300)}`);
  }
  const nodes = [];
  const attr = (tag, name) => {
    const m = new RegExp(`\\b${name}="([^"]*)"`).exec(tag);
    return m ? m[1] : "";
  };
  for (const m of xml.matchAll(/<node\b[^>]*>/g)) {
    const tag = m[0];
    const b = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(attr(tag, "bounds"));
    if (!b) continue;
    const [x1, y1, x2, y2] = b.slice(1).map(Number);
    nodes.push({
      text: attr(tag, "text"),
      desc: attr(tag, "content-desc"),
      id: attr(tag, "resource-id").replace(`${BETA_PACKAGE}:id/`, ""),
      cls: attr(tag, "class").split(".").pop(),
      clickable: attr(tag, "clickable") === "true",
      x1, y1, x2, y2,
      cx: Math.round((x1 + x2) / 2),
      cy: Math.round((y1 + y2) / 2),
    });
  }
  return nodes;
}

async function uiTree({ all = false }) {
  await requireDevice();
  const nodes = (await uiNodes()).filter((n) => all || n.text || n.desc || n.id);
  return nodes
    .map(
      (n) =>
        `${n.cls}${n.id ? " #" + n.id : ""}${n.text ? ` "${n.text}"` : ""}${n.desc ? ` [${n.desc}]` : ""}` +
        ` @${n.cx},${n.cy} (${n.x1},${n.y1}-${n.x2},${n.y2})${n.clickable ? " clickable" : ""}` +
        // uiautomator reports a 1776 px wide window (the nav bar's width is taken off) while
        // the app draws to 1920: anything on the right control column is cut at 1776, and
        // its centre is wrong. Take those coordinates from a screenshot instead.
        (n.x2 >= 1776 ? " clipped-right" : "")
    )
    .join("\n");
}

async function tapText({ text, exact = false }) {
  await requireDevice();
  const want = String(text).toLowerCase();
  const hit = (await uiNodes()).find((n) => {
    const t = (n.text || n.desc).toLowerCase();
    return exact ? t === want : t.includes(want);
  });
  if (!hit) throw new Error(`no visible node with text '${text}' (try ui_tree)`);
  await sh(`input tap ${hit.cx} ${hit.cy}`);
  return `tapped "${hit.text || hit.desc}" at ${hit.cx},${hit.cy}`;
}

/**
 * The last CaptureRequest the framework sent to the HAL, from `dumpsys media.camera`.
 * This is what the hardware was actually asked for — the way to check an effect mode,
 * focal length or AF trigger without trusting the app's own logs.
 */
async function cameraRequest({ keys, all = false }) {
  await requireDevice();
  const { stdout } = await sh("dumpsys media.camera", { timeout: 30000 });
  const start = stdout.indexOf("Last request sent:");
  if (start < 0) return "no 'Last request sent' — is the camera open?";
  const lines = stdout.slice(start).split("\n");
  const entries = [];
  for (let i = 2; i < lines.length; i++) {
    const m = /^\s+([a-zA-Z0-9_.]+) \([0-9a-f]+\): /.exec(lines[i]);
    if (m) {
      entries.push([m[1], (lines[i + 1] || "").trim()]);
      i++;
    } else if (entries.length && !/^\s{6,}/.test(lines[i])) {
      break;
    }
  }
  const wanted = all
    ? null
    : keys && keys.length
      ? keys
      : [
          "android.control.captureIntent",
          "android.control.effectMode",
          "android.control.mode",
          "android.control.afMode",
          "android.control.afTrigger",
          "android.control.aeMode",
          "android.lens.focalLength",
          "android.sensor.exposureTime",
          "android.sensor.sensitivity",
          "android.scaler.cropRegion",
        ];
  return entries
    .filter(([k]) => !wanted || wanted.some((w) => k === w || k.endsWith("." + w)))
    .map(([k, v]) => `${k} = ${v}`)
    .join("\n");
}

/**
 * Record the screen and score every frame's colour saturation in the preview area.
 * A grey viewfinder scores ~1; a colour frame on a colourful scene scores tens.
 */
async function recordPreview({ seconds = 6, crop = "1300:1000:300:40", threshold = 4, keep }) {
  await requireDevice();
  const secs = Math.max(1, Math.min(60, Math.round(seconds)));
  const remote = "/sdcard/openlight-rec.mp4";
  await sh(`screenrecord --time-limit ${secs} --bit-rate 8000000 ${remote}`, { timeout: (secs + 15) * 1000 });
  const dir = await mkdtemp(join(tmpdir(), "openlight-"));
  const local = keep || join(dir, "rec.mp4");
  try {
    const pulled = await adb(["pull", remote, local], { timeout: 120000 });
    await sh(`rm ${remote}`);
    if (!pulled.ok) return `FAILED pull: ${pulled.stderr.trim()}`;
    const { stdout } = await host("ffmpeg", [
      "-v", "error", "-i", local,
      "-vf", `crop=${crop},signalstats,metadata=print:key=lavfi.signalstats.SATAVG:file=-`,
      "-f", "null", "-",
    ]);
    const frames = [];
    let t = null;
    for (const line of stdout.split("\n")) {
      const pt = /pts_time:([\d.]+)/.exec(line);
      if (pt) t = Number(pt[1]);
      const sat = /SATAVG=([\d.]+)/.exec(line);
      if (sat && t !== null) frames.push([t, Number(sat[1])]);
    }
    if (!frames.length) return "no frames decoded";
    const sorted = frames.map((f) => f[1]).sort((a, b) => a - b);
    const hot = frames.filter((f) => f[1] > threshold);
    const out = [
      `frames ${frames.length}, saturation min ${sorted[0].toFixed(2)} median ${sorted[sorted.length >> 1].toFixed(2)} max ${sorted[sorted.length - 1].toFixed(2)}`,
      `frames above ${threshold}: ${hot.length}`,
      ...hot.slice(0, 40).map(([t2, s2]) => `  t=${t2.toFixed(2)}s sat=${s2.toFixed(2)}`),
    ];
    if (keep) out.push(`video kept at ${keep}`);
    return out.join("\n");
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

// ------------------------------------------------------------------ tool table

const TOOLS = [
  {
    name: "device_info",
    description: "Model, Android build, bootloader lock state and /sdcard usage of the connected L16.",
    inputSchema: { type: "object", properties: {} },
    handler: deviceInfo,
  },
  {
    name: "app_status",
    description:
      "Whether the openlight beta and the stock camera are installed and running, plus what is in the foreground.",
    inputSchema: { type: "object", properties: {} },
    handler: appStatus,
  },
  {
    name: "enable_test_mode",
    description:
      "Precondition for every control call: writes the /sdcard/lightest.txt marker TestIntentManager requires, and optionally a features.prop with debug_on_user=true to turn on logging. Requires an app restart.",
    inputSchema: {
      type: "object",
      properties: {
        debug_logs: { type: "boolean", description: "Also enable logging via features.prop (default true)" },
      },
    },
    handler: enableTestMode,
  },
  {
    name: "launch_app",
    description: "Start the camera app. Controls only work while it is in the foreground.",
    inputSchema: {
      type: "object",
      properties: { target: { type: "string", enum: ["beta", "stock"], description: "default beta" } },
    },
    handler: launchApp,
  },
  {
    name: "capture",
    description: "Trigger the shutter.",
    inputSchema: { type: "object", properties: {} },
    handler: capture,
  },
  {
    name: "focus",
    description: "Trigger autofocus, optionally at a screen point.",
    inputSchema: {
      type: "object",
      properties: {
        x: { type: "number", description: "x coordinate; omit for a plain AF trigger" },
        y: { type: "number", description: "y coordinate" },
      },
    },
    handler: focus,
  },
  {
    name: "set_control",
    description:
      `Set one manual control. Available: ${Object.entries(CONTROLS)
        .map(([name, s]) => `${name} (${s.hint})`)
        .join("; ")}.`,
    inputSchema: {
      type: "object",
      properties: {
        control: { type: "string", enum: Object.keys(CONTROLS) },
        value: { type: "string" },
      },
      required: ["control", "value"],
    },
    handler: setControl,
  },
  {
    name: "logs",
    description:
      "Read logcat, by default narrowed to the beta app's PID. The app logs nothing unless debug_on_user is set (see enable_test_mode).",
    inputSchema: {
      type: "object",
      properties: {
        lines: { type: "number", description: "tail length, default 200" },
        filter: { type: "string", description: "case-insensitive regex" },
        clear: { type: "boolean", description: "clear the buffer before reading" },
        since_app_only: { type: "boolean", description: "restrict to the app's PID (default true)" },
      },
    },
    handler: logs,
  },
  {
    name: "list_captures",
    description: "Counts of .lri/.jpg/.mp4 in DCIM/Camera and the most recent entries.",
    inputSchema: {
      type: "object",
      properties: { limit: { type: "number", description: "entries to show, default 30" } },
    },
    handler: listCaptures,
  },
  {
    name: "pull_capture",
    description: "Copy one file out of DCIM/Camera to the host. .lri files are 160-180 MB.",
    inputSchema: {
      type: "object",
      properties: {
        name: { type: "string", description: "bare filename, e.g. L16_00104.jpg" },
        dest: { type: "string", description: "host path, default ./<name>" },
      },
      required: ["name"],
    },
    handler: pullCapture,
  },
  {
    name: "set_feature",
    description:
      "Set one key in /sdcard/features.prop in place (the rest of the file is kept; first edit saves features.prop.orig). Both the stock and the beta app read this file. Needs restart_app.",
    inputSchema: {
      type: "object",
      properties: { key: { type: "string" }, value: { type: "string" } },
      required: ["key", "value"],
    },
    handler: setFeature,
  },
  {
    name: "restart_app",
    description: "Force-stop and relaunch the beta camera app (applies features.prop changes).",
    inputSchema: { type: "object", properties: {} },
    handler: restartApp,
  },
  {
    name: "screenshot",
    description: "Screenshot of the camera screen (1920×1080 landscape), returned as an image.",
    inputSchema: {
      type: "object",
      properties: { scale: { type: "number", description: "0.1–1, default 0.5" } },
    },
    handler: screenshot,
  },
  {
    name: "tap",
    description: "Tap at screen coordinates (1920×1080). In the viewfinder this is tap-to-focus.",
    inputSchema: {
      type: "object",
      properties: { x: { type: "number" }, y: { type: "number" } },
      required: ["x", "y"],
    },
    handler: tap,
  },
  {
    name: "swipe",
    description: "Swipe between two screen points. Avoid the right screen edge: it is the system back gesture.",
    inputSchema: {
      type: "object",
      properties: {
        x1: { type: "number" }, y1: { type: "number" },
        x2: { type: "number" }, y2: { type: "number" },
        duration_ms: { type: "number", description: "default 300" },
      },
      required: ["x1", "y1", "x2", "y2"],
    },
    handler: swipe,
  },
  {
    name: "key",
    description: "Send a key event, e.g. KEYCODE_BACK, KEYCODE_HOME, KEYCODE_CAMERA.",
    inputSchema: { type: "object", properties: { key: { type: "string" } }, required: ["key"] },
    handler: key,
  },
  {
    name: "ui_tree",
    description: "Visible views (class, id, text, centre, bounds) from uiautomator — find what to tap.",
    inputSchema: {
      type: "object",
      properties: { all: { type: "boolean", description: "include nodes with no text or id" } },
    },
    handler: uiTree,
  },
  {
    name: "tap_text",
    description: "Tap the first visible view whose text or description contains the string, e.g. a camera mode in the open mode menu.",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string" }, exact: { type: "boolean" } },
      required: ["text"],
    },
    handler: tapText,
  },
  {
    name: "camera_request",
    description:
      "Fields of the last CaptureRequest sent to the HAL (dumpsys media.camera): effect mode, focal length, AF trigger, exposure… Ground truth for what the app asked the hardware for.",
    inputSchema: {
      type: "object",
      properties: {
        keys: { type: "array", items: { type: "string" }, description: "metadata keys or suffixes, e.g. 'effectMode'" },
        all: { type: "boolean", description: "dump every key" },
      },
    },
    handler: cameraRequest,
  },
  {
    name: "record_preview",
    description:
      "Screen-record for N seconds and report per-frame colour saturation of the preview area — catches a single colour frame in mono mode. Do the actions (tap, mode switch) while it runs from a parallel call.",
    inputSchema: {
      type: "object",
      properties: {
        seconds: { type: "number", description: "1–60, default 6" },
        crop: { type: "string", description: "ffmpeg crop w:h:x:y of the preview, default 1300:1000:300:40" },
        threshold: { type: "number", description: "saturation above which a frame is listed, default 4" },
        keep: { type: "string", description: "host path to keep the mp4" },
      },
    },
    handler: recordPreview,
  },
];

const TOOLS_BY_NAME = new Map(TOOLS.map((t) => [t.name, t]));

// -------------------------------------------------------------- JSON-RPC loop

function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

function respond(id, result) {
  send({ jsonrpc: "2.0", id, result });
}

function respondError(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

async function handle(request) {
  const { id, method, params } = request;

  switch (method) {
    case "initialize":
      return respond(id, {
        protocolVersion: params?.protocolVersion || PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: SERVER_INFO,
      });

    case "notifications/initialized":
      return; // notification: no reply

    case "ping":
      return respond(id, {});

    case "tools/list":
      return respond(id, {
        tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })),
      });

    case "tools/call": {
      const tool = TOOLS_BY_NAME.get(params?.name);
      if (!tool) return respondError(id, -32602, `Unknown tool: ${params?.name}`);
      try {
        const result = await tool.handler(params.arguments ?? {});
        if (result && typeof result === "object" && Array.isArray(result.content)) {
          return respond(id, result);
        }
        return respond(id, { content: [{ type: "text", text: String(result) }] });
      } catch (err) {
        return respond(id, {
          content: [{ type: "text", text: `Error: ${err.message ?? err}` }],
          isError: true,
        });
      }
    }

    default:
      if (id !== undefined) respondError(id, -32601, `Method not found: ${method}`);
  }
}

let buffer = "";
let inFlight = 0;
let stdinClosed = false;

/** Exit only once stdin is done AND every dispatched request has answered. */
function exitWhenIdle() {
  if (stdinClosed && inFlight === 0) process.exit(0);
}

process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf("\n")) !== -1) {
    const line = buffer.slice(0, newline).trim();
    buffer = buffer.slice(newline + 1);
    if (!line) continue;
    let request;
    try {
      request = JSON.parse(line);
    } catch {
      respondError(null, -32700, "Parse error");
      continue;
    }
    inFlight++;
    handle(request)
      .catch((err) => {
        if (request.id !== undefined) respondError(request.id, -32603, String(err.message ?? err));
      })
      .finally(() => {
        inFlight--;
        exitWhenIdle();
      });
  }
});

process.stdin.on("end", () => {
  stdinClosed = true;
  exitWhenIdle();
});
