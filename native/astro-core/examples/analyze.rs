//! Run the camera's frame analysis outside the app: on the Mac, or on the camera over adb.
//!
//!   cargo run --release --example analyze -- L16_00241.lri [top] [lat lon]
//!
//! With a place it also plate-solves; the time and gravity come from the frame.
fn main() {
    let mut a = std::env::args().skip(1);
    let path = a.next().expect("path to .lri");
    let top = a.next().and_then(|s| s.parse().ok()).unwrap_or(10);
    let lat: Option<f64> = a.next().and_then(|s| s.parse().ok());
    let lon: Option<f64> = a.next().and_then(|s| s.parse().ok());
    match (lat, lon) {
        (Some(lat), Some(lon)) => println!("{}", openlight_astro::solve_json(&path, top, lat, lon)),
        _ => println!("{}", openlight_astro::analyze_json(&path, top)),
    }
}
