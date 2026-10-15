//! Astro mode, native half: what the camera needs in the field from one test frame.
//!
//! Every capture below 70 mm carries the panchromatic plane of A2, every one from 70 mm
//! that of C6 (corpus census, 273 .lri). This reads only that plane — 16 MB of a 160 MB
//! file, located through the block index lri-mono already walks — finds the stars in
//! the clean part of the sky with lumina-core, and reports how sharp they are. That is
//! the focus check; the plate solve comes next on the same sources.
//!
//! The plane is turned 180° after unpacking: lumina-core's factory distortion for A2 and
//! its test frame are in that orientation (lightmotiv `light extract`), and lri-mono's
//! raw order is the sensor's (checked on L16_00241: identical after the turn).

#[path = "../../../scripts/lri-mono/src/container.rs"]
#[allow(dead_code)]
mod container;

use container::{LocalSource, Source};
use lri_proto::{lightheader::LightHeader, Message as _};
use lumina_core::matcher::MatchParams;
use lumina_core::prefilter::{screen, ScreenParams, Verdict};
use lumina_core::prior::{scan_sky, Prior, ScanFrame, ScanGrid, Sky};
use lumina_core::sky::{julian_day, lst_deg};
use lumina_core::wcs::{Lens, Radial};
use std::sync::OnceLock;
use std::fmt::Write as _;
use std::time::Instant;

/// Black level of the AR1335 planes as the container records it.
const BLACK: f32 = 42.0;

/// Focal length in pixels from the factory intrinsics: A2 (28 mm group) and C6 (150 mm).
fn focal_px(camera: &str) -> f64 {
    if camera.eq_ignore_ascii_case("C6") { 18_700.0 } else { 3_376.2 }
}

pub struct Star {
    pub x: f64,
    pub y: f64,
    pub flux: f64,
    pub hfr: f64,
    pub elongation: f64,
}

/// Yale Bright Star Catalogue (VizieR V/50, public domain), 9096 stars to about V 7 —
/// enough for the 63° field of A2: the frame holds ~450 of them.
static BSC: &str = include_str!("../data/bsc_v50.tsv");

fn sky() -> &'static Sky {
    static SKY: OnceLock<Sky> = OnceLock::new();
    SKY.get_or_init(|| Sky::parse_tsv(BSC))
}

/// Factory radial distortion of A2, both stages (CRA table and polynomial) folded into
/// one model, ≤ 0.5 px from the factory function. Measured on body C0700105 — the one
/// this was built on; another body needs its own numbers from its .lri calibration.
fn a2_lens() -> Radial {
    Radial {
        centre: (2082.0, 1503.0),
        scale: 4160.0,
        k: [0.025_589_960_6, 1.087_003_21, -4.126_506_95, 5.076_597_04, 0.0],
        n: 4,
    }
}

/// What the frame itself says about when it was taken and which way gravity pointed.
pub struct Context {
    /// UTC as (year, month, day, hour, minute, second).
    pub utc: Option<(i32, u32, u32, u32, u32, f64)>,
    /// Mean accelerometer vector over the exposure, camera axes.
    pub accel: Option<[f64; 3]>,
    pub accel_samples: usize,
}

/// Walk the LightHeader messages — a few small reads — for time stamp and IMU.
pub fn read_context(src: &dyn Source) -> std::io::Result<Context> {
    let mut ctx = Context { utc: None, accel: None, accel_samples: 0 };
    let mut sum = [0f64; 3];
    let mut pos = 0u64;
    loop {
        let h = src.read_at(pos, 32)?;
        if h.len() < 32 || &h[..4] != b"LELR" {
            break;
        }
        let len = u64::from_le_bytes(h[4..12].try_into().unwrap());
        let off = u64::from_le_bytes(h[12..20].try_into().unwrap());
        let mlen = u32::from_le_bytes(h[20..24].try_into().unwrap()) as u64;
        if len == 0 {
            break;
        }
        if h[24] == 0 && mlen > 0 {
            if let Ok(lh) = LightHeader::parse_from_bytes(&src.read_at(pos + off, mlen)?) {
                if ctx.utc.is_none() {
                    if let Some(t) = lh.image_time_stamp.as_ref() {
                        // Local wall time plus its offset in minutes east of UTC.
                        let tz = t.tz_offset.unwrap_or(0) as f64;
                        let jd = julian_day(t.year() as i32, t.month(), t.day(), t.hour(), t.minute(), t.second() as f64)
                            - tz / 1440.0;
                        ctx.utc = Some(from_jd(jd));
                    }
                }
                for imu in &lh.imu_data {
                    for s in &imu.accelerometer {
                        if let Some(p) = s.data.as_ref() {
                            sum[0] += p.x() as f64;
                            sum[1] += p.y() as f64;
                            sum[2] += p.z() as f64;
                            ctx.accel_samples += 1;
                        }
                    }
                }
            }
        }
        pos += len;
    }
    if ctx.accel_samples > 0 {
        let n = ctx.accel_samples as f64;
        ctx.accel = Some([sum[0] / n, sum[1] / n, sum[2] / n]);
    }
    Ok(ctx)
}

/// Julian day back to a calendar UTC, for julian_day/lst_deg which take components.
fn from_jd(jd: f64) -> (i32, u32, u32, u32, u32, f64) {
    let z = (jd + 0.5).floor();
    let f = jd + 0.5 - z;
    let a = if z < 2_299_161.0 {
        z
    } else {
        let alpha = ((z - 1_867_216.25) / 36_524.25).floor();
        z + 1.0 + alpha - (alpha / 4.0).floor()
    };
    let b = a + 1524.0;
    let c = ((b - 122.1) / 365.25).floor();
    let d = (365.25 * c).floor();
    let e = ((b - d) / 30.6001).floor();
    let day = (b - d - (30.6001 * e).floor()) as u32;
    let month = if e < 14.0 { e - 1.0 } else { e - 13.0 } as u32;
    let year = if month > 2 { c - 4716.0 } else { c - 4715.0 } as i32;
    let secs = f * 86_400.0;
    let hour = (secs / 3600.0).floor() as u32;
    let minute = ((secs - hour as f64 * 3600.0) / 60.0).floor() as u32;
    let second = secs - hour as f64 * 3600.0 - minute as f64 * 60.0;
    (year, month, day, hour, minute, second)
}

/// Where the frame points, if it could be found.
pub struct Solution {
    pub ra: f64,
    pub dec: f64,
    pub rotation: f64,
    pub azimuth: f64,
    pub altitude: f64,
    pub pairs: usize,
    pub residual_px: f64,
    pub scale_arcsec_px: f64,
    pub tries: usize,
    pub ms: u128,
}

pub struct Report {
    pub camera: String,
    pub width: usize,
    pub height: usize,
    pub exposure_ms: f64,
    pub analog_gain: f32,
    pub temperature: i32,
    pub verdict: &'static str,
    pub n_stars: usize,
    pub density: f64,
    pub rect: (usize, usize, usize, usize),
    /// Median half-flux radius, px. For a Gaussian star FWHM = 2·HFR.
    pub hfr_median: f64,
    pub elongation_median: f64,
    /// Brightest stars first.
    pub stars: Vec<Star>,
    pub read_ms: u128,
    pub screen_ms: u128,
    /// All sources, brightest first, raw pixel coordinates — the solver's input.
    pub sources: Vec<(f64, f64)>,
    pub focal_px: f64,
    pub solution: Option<Solution>,
    pub solve_note: Option<String>,
}

fn median(mut v: Vec<f64>) -> f64 {
    if v.is_empty() {
        return f64::NAN;
    }
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    v[v.len() / 2]
}

/// Read the panchromatic plane of `path` and measure its stars.
pub fn analyze(path: &str, top: usize) -> Result<Report, String> {
    let t = Instant::now();
    let src = LocalSource::new(path.to_string());
    let (planes, _) = container::index(&src).map_err(|e| format!("index: {e}"))?;
    let plane = planes
        .iter()
        .find(|p| p.cfa().is_none())
        .ok_or("no panchromatic module in this frame")?;
    let bytes = src.read_at(plane.offset, plane.length).map_err(|e| format!("read: {e}"))?;
    if (bytes.len() as u64) < plane.length {
        return Err(format!("plane short: {} of {}", bytes.len(), plane.length));
    }
    let (w, h) = (plane.width, plane.height);
    let raw = container::unpack_tenbit(&bytes, w * h);
    // 180° turn = reversed pixel order.
    let data: Vec<f32> = raw.iter().rev().map(|&v| v as f32 - BLACK).collect();
    let read_ms = t.elapsed().as_millis();

    let t = Instant::now();
    let f = focal_px(&plane.camera);
    let fov_w = 2.0 * (w as f64 / 2.0 / f).atan().to_degrees();
    let fov_h = 2.0 * (h as f64 / 2.0 / f).atan().to_degrees();
    let s = screen(&data, w, h, fov_w, fov_h, &ScreenParams::default());
    let screen_ms = t.elapsed().as_millis();

    let mut sources = s.sources.clone();
    sources.sort_by(|a, b| b.flux.partial_cmp(&a.flux).unwrap());
    Ok(Report {
        camera: plane.camera.clone(),
        width: w,
        height: h,
        exposure_ms: plane.exposure_ns as f64 / 1.0e6,
        analog_gain: plane.analog_gain,
        temperature: plane.temperature,
        verdict: match s.verdict {
            Verdict::Solve => "solve",
            Verdict::NoStars => "no_stars",
            Verdict::TooSparse => "too_sparse",
        },
        n_stars: s.n_stars,
        density: s.density,
        rect: (s.rect.x0, s.rect.y0, s.rect.x1, s.rect.y1),
        hfr_median: median(sources.iter().map(|s| s.hfr).collect()),
        elongation_median: median(sources.iter().map(|s| s.elongation).collect()),
        stars: sources
            .iter()
            .take(top)
            .map(|s| Star { x: s.x, y: s.y, flux: s.flux, hfr: s.hfr, elongation: s.elongation })
            .collect(),
        read_ms,
        screen_ms,
        sources: sources.iter().map(|s| (s.x, s.y)).collect(),
        focal_px: f,
        solution: None,
        solve_note: None,
    })
}

/// Analyse, then plate-solve from the frame's own time and gravity plus the place the
/// phone knows. A2 only: C6 has no lens model here yet, and its field is too narrow for
/// the bright-star catalogue.
pub fn analyze_and_solve(path: &str, top: usize, lat: f64, lon: f64) -> Result<Report, String> {
    let mut r = analyze(path, top)?;
    if r.camera != "A2" {
        r.solve_note = Some(format!("solve needs A2, frame has {}", r.camera));
        return Ok(r);
    }
    if r.verdict != "solve" {
        r.solve_note = Some(format!("not enough stars ({})", r.verdict));
        return Ok(r);
    }
    if !lat.is_finite() || !lon.is_finite() {
        r.solve_note = Some("no location".into());
        return Ok(r);
    }
    let src = LocalSource::new(path.to_string());
    let ctx = read_context(&src).map_err(|e| format!("context: {e}"))?;
    let (Some((y, mo, d, h, mi, s)), Some(accel)) = (ctx.utc, ctx.accel) else {
        r.solve_note = Some(format!(
            "frame lacks {}",
            if ctx.utc.is_none() { "time stamp" } else { "accelerometer samples" }
        ));
        return Ok(r);
    };
    let (w, hgt) = (r.width as f64, r.height as f64);
    let c = ((w + 1.0) / 2.0, (hgt + 1.0) / 2.0);
    let scale = 206_264.806 / r.focal_px;
    let mut p = MatchParams::for_prior(1.5, 3.0, scale, c.0.hypot(c.1));
    p.frame = (w, hgt);
    p.final_tolerance_px = 12.0;
    let prior = Prior { lat, lst: lst_deg(julian_day(y, mo, d, h, mi, s), lon), accel };
    let frame = ScanFrame { centre: c, scale_arcsec_px: scale, flipped: false, lens: Some(Lens::Radial(a2_lens())) };
    let radius = c.0.hypot(c.1) * scale / 3600.0 + 3.0;
    let t = Instant::now();
    let hit = scan_sky(&r.sources, sky(), radius, &frame, &prior, &ScanGrid::default(), &p);
    let ms = t.elapsed().as_millis();
    match hit {
        Some(hit) => {
            let (ra, dec) = hit.result.wcs.pixel_to_world(c.0, c.1);
            r.solution = Some(Solution {
                ra,
                dec,
                rotation: hit.rotation,
                azimuth: hit.azimuth,
                altitude: hit.altitude,
                pairs: hit.result.pairs.len(),
                residual_px: hit.result.residual_median_px,
                scale_arcsec_px: hit.result.wcs.pixel_scale_arcsec(),
                tries: hit.tries,
                ms,
            });
        }
        None => r.solve_note = Some(format!("no solution in {ms} ms")),
    }
    Ok(r)
}

fn num(v: f64) -> String {
    if v.is_finite() { format!("{v:.3}") } else { "null".into() }
}

impl Report {
    pub fn to_json(&self) -> String {
        let mut o = String::new();
        let _ = write!(
            o,
            "{{\"ok\":true,\"camera\":\"{}\",\"width\":{},\"height\":{},\"exposure_ms\":{},\
             \"analog_gain\":{},\"temperature\":{},\"verdict\":\"{}\",\"n_stars\":{},\
             \"density\":{},\"rect\":[{},{},{},{}],\"hfr_median\":{},\"fwhm_median\":{},\
             \"elongation_median\":{},\"read_ms\":{},\"screen_ms\":{},\"stars\":[",
            self.camera, self.width, self.height, num(self.exposure_ms), num(self.analog_gain as f64),
            self.temperature, self.verdict, self.n_stars, num(self.density),
            self.rect.0, self.rect.1, self.rect.2, self.rect.3,
            num(self.hfr_median), num(2.0 * self.hfr_median), num(self.elongation_median),
            self.read_ms, self.screen_ms
        );
        for (i, s) in self.stars.iter().enumerate() {
            let _ = write!(
                o,
                "{}[{},{},{},{},{}]",
                if i > 0 { "," } else { "" },
                num(s.x), num(s.y), num(s.flux), num(s.hfr), num(s.elongation)
            );
        }
        o.push(']');
        if let Some(s) = &self.solution {
            let _ = write!(
                o,
                ",\"solve\":{{\"ra\":{},\"dec\":{},\"rotation\":{},\"azimuth\":{},\"altitude\":{},\
                 \"pairs\":{},\"residual_px\":{},\"scale_arcsec_px\":{},\"tries\":{},\"ms\":{}}}",
                num(s.ra), num(s.dec), num(s.rotation), num(s.azimuth), num(s.altitude),
                s.pairs, num(s.residual_px), num(s.scale_arcsec_px), s.tries, s.ms
            );
        }
        if let Some(n) = &self.solve_note {
            let _ = write!(o, ",\"solve_note\":\"{}\"", n.replace('"', "'"));
        }
        o.push('}');
        o
    }
}

fn error_json(e: &str) -> String {
    format!("{{\"ok\":false,\"error\":\"{}\"}}", e.replace('\\', "\\\\").replace('"', "\\\""))
}

/// Analysis plus plate solve, as JSON. `lat`/`lon` NaN when the phone has no fix.
pub fn solve_json(path: &str, top: usize, lat: f64, lon: f64) -> String {
    match analyze_and_solve(path, top, lat, lon) {
        Ok(r) => r.to_json(),
        Err(e) => error_json(&e),
    }
}

/// JSON for one frame: the whole result or `{"ok":false,"error":…}`.
pub fn analyze_json(path: &str, top: usize) -> String {
    match analyze(path, top) {
        Ok(r) => r.to_json(),
        Err(e) => error_json(&e),
    }
}

mod jni_entry {
    use jni::objects::{JClass, JString};
    use jni::sys::{jdouble, jint, jstring};
    use jni::JNIEnv;

    /// `openlight.co.camera.astro.AstroNative.analyzeA2(String path, int top)`.
    #[no_mangle]
    pub extern "system" fn Java_openlight_co_camera_astro_AstroNative_analyzeA2(
        mut env: JNIEnv,
        _class: JClass,
        path: JString,
        top: jint,
    ) -> jstring {
        let json = match env.get_string(&path) {
            Ok(p) => super::analyze_json(&String::from(p), top.max(0) as usize),
            Err(e) => super::error_json(&format!("bad path: {e}")),
        };
        env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    /// `AstroNative.solveA2(String path, int top, double lat, double lon)`.
    #[no_mangle]
    pub extern "system" fn Java_openlight_co_camera_astro_AstroNative_solveA2(
        mut env: JNIEnv,
        _class: JClass,
        path: JString,
        top: jint,
        lat: jdouble,
        lon: jdouble,
    ) -> jstring {
        let json = match env.get_string(&path) {
            Ok(p) => super::solve_json(&String::from(p), top.max(0) as usize, lat, lon),
            Err(e) => super::error_json(&format!("bad path: {e}")),
        };
        env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }
}
