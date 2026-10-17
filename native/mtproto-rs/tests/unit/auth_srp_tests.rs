use super::*;

#[test]
fn ph1_uses_nested_sh_not_sha256_password() {
    let salt1 = [1_u8];
    let salt2 = [2_u8];
    let password = [7_u8];
    // Standard PH1:
    let correct = ph1(&password, &salt1, &salt2);
    // Old incorrect formula we used previously:
    let mut hasher = Sha256::new();
    hasher.update(password);
    let pwd_hash: [u8; 32] = hasher.finalize().into();
    let wrong = h(&[&salt1[..], &pwd_hash, &salt1[..]]);
    assert_ne!(correct, wrong);
}

#[test]
fn small_srp_m1_matches_vector() {
    let salt1 = [1_u8];
    let salt2 = [2_u8];
    let p = pad_to_256(&[47]);
    let g_b = pad_to_256(&[5]);
    let a = pad_to_256(&[6]);
    let password = [7_u8];
    let g = 3_i32;

    let big_p = BigUint::from_bytes_be(&p);
    let g_for_hash = pad_to_256(&[g as u8]);
    let big_g_b = BigUint::from_bytes_be(&g_b);
    let big_g = BigUint::from(g as u32);
    let big_a = BigUint::from_bytes_be(&a);
    let k = h(&[&p, &g_for_hash]);
    let big_k = BigUint::from_bytes_be(&k);
    let g_a = pad_to_256(&big_g.modpow(&big_a, &big_p).to_bytes_be());
    let u = BigUint::from_bytes_be(&h(&[&g_a, &g_b]));
    let x = BigUint::from_bytes_be(&ph2(&password, &salt1, &salt2));
    let big_v = big_g.modpow(&x, &big_p);
    let k_v = (&big_k * &big_v) % &big_p;
    let big_t = if big_g_b >= k_v {
        (&big_g_b - &k_v) % &big_p
    } else {
        (&big_p + &big_g_b - &k_v) % &big_p
    };
    let big_s_a = big_t.modpow(&(&big_a + &u * &x), &big_p);
    let k_a = h(&[&pad_to_256(&big_s_a.to_bytes_be())]);
    let m1 = h(&[
        &xor32(&h(&[&p]), &h(&[&g_for_hash])),
        &h(&[&salt1]),
        &h(&[&salt2]),
        &g_a,
        &g_b,
        &k_a,
    ]);

    let expected_m1 = [
        157u8, 131, 196, 103, 0, 184, 116, 232, 7, 196, 85, 231, 17, 36, 30, 222, 158, 234, 98, 88,
        59, 56, 71, 215, 183, 123, 122, 50, 19, 32, 54, 206,
    ];
    let expected_g_a = {
        let mut v = [0_u8; 256];
        v[255] = 24;
        v
    };
    assert_eq!(m1, expected_m1);
    assert_eq!(g_a, expected_g_a);
}
