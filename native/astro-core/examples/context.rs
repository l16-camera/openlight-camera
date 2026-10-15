//! What a frame carries for the plate-solve prior: time, place, gravity.
//!
//!   cargo run --release --example context -- L16_00241.lri
use lri_proto::{lightheader::LightHeader, Message as _};

fn main() {
    let path = std::env::args().nth(1).expect("path");
    let data = std::fs::read(&path).expect("read");
    let mut cur = &data[..];
    let mut n = 0;
    while cur.len() >= 32 && &cur[0..4] == b"LELR" {
        let len = u64::from_le_bytes(cur[4..12].try_into().unwrap()) as usize;
        let off = u64::from_le_bytes(cur[12..20].try_into().unwrap()) as usize;
        let mlen = u32::from_le_bytes(cur[20..24].try_into().unwrap()) as usize;
        if cur[24] == 0 {
            if let Ok(lh) = LightHeader::parse_from_bytes(&cur[off..off + mlen]) {
                n += 1;
                if let Some(t) = lh.image_time_stamp.as_ref() {
                    println!("block {n}: time {}-{:02}-{:02} {:02}:{:02}:{:02} tz {:?}", t.year(), t.month(), t.day(), t.hour(), t.minute(), t.second(), t.tz_offset);
                }
                if let Some(g) = lh.gps_data.as_ref() {
                    println!("block {n}: gps {:?} {:?} ts {:?}", g.latitude, g.longitude, g.timestamp);
                }
                for imu in &lh.imu_data {
                    let k = imu.accelerometer.len();
                    if k > 0 {
                        let mut m = [0f64; 3];
                        for s in &imu.accelerometer {
                            let p = s.data.as_ref().unwrap();
                            m[0] += p.x() as f64; m[1] += p.y() as f64; m[2] += p.z() as f64;
                        }
                        println!("block {n}: accel mean of {k}: [{:.4} {:.4} {:.4}]", m[0] / k as f64, m[1] / k as f64, m[2] / k as f64);
                    }
                }
            }
        }
        if len == 0 || len > cur.len() { break; }
        cur = &cur[len..];
    }
}
