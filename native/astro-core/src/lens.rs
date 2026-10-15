//! A module's factory distortion, read from the frame itself and folded into one
//! radial model for the solver.
//!
//! Every `.lri` carries the factory calibration of each module
//! (`LightHeader.module_calibration[].geometry.distortion`) in two stages, applied one
//! after the other from an ideal point to where it lands on the sensor:
//!
//! 1. **CRA** (chief ray angle): a table radius (mm) → radial shift (mm) about its own
//!    centre; radius in mm is pixels × `pixel_size`.
//! 2. **Polynomial**: in coordinates normalised by its centre and `normalization`, the
//!    model `x_d = x_n · (1 + Σ kᵢ r_n^{2i})`, applied inverted (found by iteration).
//!
//! That composite — CRA, then the inverted polynomial — is a resampling map: for an
//! output pixel of the corrected image it says where to read the sensor. As a map of
//! points it runs **measured → ideal**. lumina-core's `Radial` stores the other
//! direction, ideal → measured, as `r · (1 + Σ kᵢ (r/scale)^{2i})` about one centre. So
//! the composite is profiled along the radius and `Radial` is fitted to its inverse.
//!
//! Checked against the A2 model lumina-core fitted by hand and validated by plate solve
//! (body C0700105, `tests/l16.rs`: k = [0.0256, 1.0870, −4.1265, 5.0766], scale 4160):
//! from `L16_00241`'s own calibration this gives k = [0.0365, 1.0403, −4.1564, 5.3051],
//! the frame corner lands 1.4 px apart, and the fitted direction the other way round has
//! every sign flipped — the check that settled the direction.

use crate::container::Source;
use lri_proto::{lightheader::LightHeader, Message as _};
use lumina_core::wcs::Radial;

struct Poly {
	centre: (f64, f64),
	norm: (f64, f64),
	k: Vec<f64>,
}

struct Cra {
	centre: (f64, f64),
	pixel_mm: f64,
	/// (radius mm, shift mm), ascending in radius.
	table: Vec<(f64, f64)>,
}

/// The factory map for one module: ideal pixel → measured pixel.
pub struct FactoryDistortion {
	poly: Option<Poly>,
	cra: Option<Cra>,
}

impl FactoryDistortion {
	fn cra_shift(c: &Cra, r_mm: f64) -> f64 {
		let t = &c.table;
		if t.is_empty() {
			return 0.0;
		}
		if r_mm <= t[0].0 {
			return t[0].1;
		}
		for w in t.windows(2) {
			if r_mm <= w[1].0 {
				let f = (r_mm - w[0].0) / (w[1].0 - w[0].0).max(1e-12);
				return w[0].1 + f * (w[1].1 - w[0].1);
			}
		}
		t[t.len() - 1].1
	}

	/// The factory composite, CRA then the inverted polynomial: measured → ideal.
	pub fn factory_map(&self, u: f64, v: f64) -> (f64, f64) {
		let (mut x, mut y) = (u, v);
		if let Some(c) = &self.cra {
			let (dx, dy) = (x - c.centre.0, y - c.centre.1);
			let r = dx.hypot(dy);
			if r > 1e-6 {
				let r_mm = r * c.pixel_mm;
				let s = ((r_mm + Self::cra_shift(c, r_mm)).max(0.0) / r_mm).clamp(0.5, 2.0);
				x = c.centre.0 + dx * s;
				y = c.centre.1 + dy * s;
			}
		}
		if let Some(p) = &self.poly {
			let (xd, yd) = ((x - p.centre.0) / p.norm.0, (y - p.centre.1) / p.norm.1);
			let (mut xn, mut yn) = (xd, yd);
			for _ in 0..20 {
				let r2 = xn * xn + yn * yn;
				let (mut f, mut rp) = (1.0, r2);
				for k in &p.k {
					f += k * rp;
					rp *= r2;
				}
				xn += xd - xn * f;
				yn += yd - yn * f;
			}
			x = p.centre.0 + xn * p.norm.0;
			y = p.centre.1 + yn * p.norm.1;
		}
		(x, y)
	}

	/// Fold into one `Radial` about the polynomial's centre (CRA's when there is no
	/// polynomial), `terms` coefficients, radius normalised by `scale`. Returns the model
	/// and its largest deviation from the factory map over the frame, in px.
	pub fn to_radial(&self, width: f64, height: f64, scale: f64, terms: usize) -> Option<(Radial, f64)> {
		self.to_radial_dir(width, height, scale, terms, true)
	}

	/// `swap = true` fits `Radial` (ideal → measured) to the inverse of the factory map;
	/// `false` fits the factory map itself, kept for the direction check.
	pub fn to_radial_dir(&self, width: f64, height: f64, scale: f64, terms: usize, swap: bool) -> Option<(Radial, f64)> {
		let centre = self.poly.as_ref().map(|p| p.centre).or(self.cra.as_ref().map(|c| c.centre))?;
		let terms = terms.clamp(1, 5);
		// Profile the composite along eight directions, out to the farthest corner.
		let rmax = [(0.0, 0.0), (width, 0.0), (0.0, height), (width, height)]
			.iter()
			.map(|&(x, y)| (x - centre.0).hypot(y - centre.1))
			.fold(0.0, f64::max);
		let mut rows: Vec<(f64, f64)> = Vec::new(); // (r²/scale², t = factor − 1)
		let mut samples: Vec<(f64, f64, f64, f64)> = Vec::new();
		for d in 0..8 {
			let a = d as f64 * std::f64::consts::FRAC_PI_4 + 0.3;
			let (ca, sa) = (a.cos(), a.sin());
			let mut r = 20.0;
			while r <= rmax {
				let (u, v) = (centre.0 + r * ca, centre.1 + r * sa);
				let (mx, my) = self.factory_map(u, v);
				let (u, v, mx, my) = if swap { (mx, my, u, v) } else { (u, v, mx, my) };
				let ru = (u - centre.0).hypot(v - centre.1);
				let t = ((mx - centre.0) * (u - centre.0) + (my - centre.1) * (v - centre.1)) / (ru * ru) - 1.0;
				rows.push(((ru / scale).powi(2), t));
				samples.push((u, v, mx, my));
				r += 20.0;
			}
		}
		// Normal equations for t = Σ kᵢ (r²)^i, i = 1..terms.
		let n = terms;
		let mut ata = vec![vec![0.0f64; n]; n];
		let mut atb = vec![0.0f64; n];
		for &(q, t) in &rows {
			let pw: Vec<f64> = (1..=n).map(|i| q.powi(i as i32)).collect();
			for i in 0..n {
				atb[i] += pw[i] * t;
				for j in 0..n {
					ata[i][j] += pw[i] * pw[j];
				}
			}
		}
		let k = solve(ata, atb)?;
		let mut kk = [0.0f64; 5];
		kk[..n].copy_from_slice(&k);
		let radial = Radial { centre, scale, k: kk, n: n as u8 };
		let worst = samples
			.iter()
			.filter(|s| s.0 >= 0.0 && s.1 >= 0.0 && s.0 <= width && s.1 <= height)
			.map(|&(u, v, mx, my)| {
				let (rx, ry) = radial.undo(u, v);
				(rx - mx).hypot(ry - my)
			})
			.fold(0.0, f64::max);
		Some((radial, worst))
	}
}

/// Gaussian elimination with partial pivoting; tiny systems only.
fn solve(mut a: Vec<Vec<f64>>, mut b: Vec<f64>) -> Option<Vec<f64>> {
	let n = b.len();
	for c in 0..n {
		let p = (c..n).max_by(|&i, &j| a[i][c].abs().partial_cmp(&a[j][c].abs()).unwrap())?;
		if a[p][c].abs() < 1e-300 {
			return None;
		}
		a.swap(c, p);
		b.swap(c, p);
		for r in c + 1..n {
			let f = a[r][c] / a[c][c];
			for k in c..n {
				a[r][k] -= f * a[c][k];
			}
			b[r] -= f * b[c];
		}
	}
	let mut x = vec![0.0; n];
	for r in (0..n).rev() {
		let s: f64 = (r + 1..n).map(|k| a[r][k] * x[k]).sum();
		x[r] = (b[r] - s) / a[r][r];
	}
	Some(x)
}

/// Read the factory distortion of `camera` (e.g. "A2") from the frame's calibration.
pub fn read_factory(src: &dyn Source, camera: &str) -> std::io::Result<Option<FactoryDistortion>> {
	let mut pos = 0u64;
	loop {
		let h = src.read_at(pos, 32)?;
		if h.len() < 32 || &h[..4] != b"LELR" {
			return Ok(None);
		}
		let len = u64::from_le_bytes(h[4..12].try_into().unwrap());
		let off = u64::from_le_bytes(h[12..20].try_into().unwrap());
		let mlen = u32::from_le_bytes(h[20..24].try_into().unwrap()) as u64;
		if len == 0 {
			return Ok(None);
		}
		if h[24] == 0 && mlen > 0 {
			if let Ok(lh) = LightHeader::parse_from_bytes(&src.read_at(pos + off, mlen)?) {
				for mc in &lh.module_calibration {
					if !format!("{:?}", mc.camera_id()).eq_ignore_ascii_case(camera) {
						continue;
					}
					let Some(d) = mc.geometry.as_ref().and_then(|g| g.distortion.as_ref()) else {
						continue;
					};
					let poly = d.polynomial.as_ref().map(|p| Poly {
						centre: p.distortion_center.as_ref().map(|c| (c.x() as f64, c.y() as f64)).unwrap_or_default(),
						norm: p
							.normalization
							.as_ref()
							.map(|c| (c.x().max(1e-6) as f64, c.y().max(1e-6) as f64))
							.unwrap_or((1.0, 1.0)),
						k: p.coeffs.iter().map(|&k| k as f64).collect(),
					});
					let cra = d.cra.as_ref().map(|c| {
						let mut table: Vec<(f64, f64)> = c.coeffs.iter().map(|p| (p.x() as f64, p.y() as f64)).collect();
						table.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap());
						Cra {
							centre: c.distortion_center.as_ref().map(|p| (p.x() as f64, p.y() as f64)).unwrap_or_default(),
							pixel_mm: c.pixel_size() as f64,
							table,
						}
					});
					if poly.is_some() || cra.is_some() {
						return Ok(Some(FactoryDistortion { poly, cra }));
					}
				}
			}
		}
		pos += len;
	}
}
