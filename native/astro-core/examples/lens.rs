//! The factory distortion of a module, folded into lumina-core's radial model.
//!
//!   cargo run --release --example lens -- L16_00241.lri [A2]
fn main() {
    let mut a = std::env::args().skip(1);
    let path = a.next().expect("path to .lri");
    let cam = a.next().unwrap_or_else(|| "A2".into());
    let src = openlight_astro::local_source(&path);
    let f = openlight_astro::lens::read_factory(&*src, &cam).expect("read").expect("no distortion for this module");
    for swap in [false, true] {
        for terms in [4] {
            let (r, worst) = f.to_radial_dir(4160.0, 3120.0, 4160.0, terms, swap).expect("fit");
            let (cx, cy) = r.undo(0.0, 0.0);
            println!("{cam} swap {swap} terms {terms}: centre ({:.1}, {:.1}) k {:?} worst {worst:.2} px; corner (0,0) → ({cx:.1}, {cy:.1})", r.centre.0, r.centre.1, &r.k[..terms]);
        }
    }
}
