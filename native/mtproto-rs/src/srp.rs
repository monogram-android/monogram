//! Telegram 2FA SRP (`passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow`).
//!
//! Algorithm matches <https://core.telegram.org/api/srp>
//! (PH1 = SH(SH(password, salt1), salt2)).

use num_bigint::BigUint;
use num_traits::Zero;
use pbkdf2::pbkdf2_hmac;
use sha2::{Digest, Sha256, Sha512};
use tellers_mtproto::latest::api::{
    InputCheckPasswordSrp, InputCheckPasswordSrpConstructor,
    PasswordKdfAlgoSha256sha256pbkdf2hmacsha512iter100000Sha256ModPowConstructor,
};
use tellers_mtproto_crypto::fill_random;

use crate::MtprotoError;

fn h(parts: &[&[u8]]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    for part in parts {
        hasher.update(part);
    }
    hasher.finalize().into()
}

/// SH(data, salt) := H(salt | data | salt)
fn sh(data: &[u8], salt: &[u8]) -> [u8; 32] {
    h(&[salt, data, salt])
}

/// PH1(password, salt1, salt2) := SH(SH(password, salt1), salt2)
fn ph1(password: &[u8], salt1: &[u8], salt2: &[u8]) -> [u8; 32] {
    sh(&sh(password, salt1), salt2)
}

/// PH2(password, salt1, salt2) := SH(pbkdf2(sha512, PH1(...), salt1, 100000), salt2)
fn ph2(password: &[u8], salt1: &[u8], salt2: &[u8]) -> [u8; 32] {
    let hash1 = ph1(password, salt1, salt2);
    let mut dk = [0_u8; 64];
    pbkdf2_hmac::<Sha512>(&hash1, salt1, 100_000, &mut dk);
    sh(&dk, salt2)
}

fn pad_to_256(data: &[u8]) -> [u8; 256] {
    let mut out = [0_u8; 256];
    let data = if data.len() > 256 {
        &data[data.len() - 256..]
    } else {
        data
    };
    out[256 - data.len()..].copy_from_slice(data);
    out
}

fn xor32(left: &[u8; 32], right: &[u8; 32]) -> [u8; 32] {
    let mut out = [0_u8; 32];
    for i in 0..32 {
        out[i] = left[i] ^ right[i];
    }
    out
}

/// Build `inputCheckPasswordSRP` for `auth.checkPassword`.
pub fn input_check_password(
    password: &str,
    algo: &PasswordKdfAlgoSha256sha256pbkdf2hmacsha512iter100000Sha256ModPowConstructor,
    srp_b: &[u8],
    srp_id: i64,
) -> Result<InputCheckPasswordSrp, MtprotoError> {
    if algo.p.len() != 256 {
        return Err(MtprotoError::Message(format!(
            "unexpected SRP p length {}",
            algo.p.len()
        )));
    }
    let big_p = BigUint::from_bytes_be(&algo.p);
    if big_p.is_zero() || algo.g <= 0 {
        return Err(MtprotoError::Message("invalid SRP modulus".into()));
    }

    let g_b = pad_to_256(srp_b);
    let mut a_bytes = [0_u8; 256];
    fill_random(&mut a_bytes).map_err(|e| MtprotoError::Message(e.to_string()))?;
    let a = pad_to_256(&a_bytes);

    let g_for_hash = pad_to_256(&[algo.g as u8]);
    let big_g_b = BigUint::from_bytes_be(&g_b);
    let big_g = BigUint::from(algo.g as u32);
    let big_a = BigUint::from_bytes_be(&a);

    // k := H(p | g)
    let k = h(&[&algo.p, &g_for_hash]);
    let big_k = BigUint::from_bytes_be(&k);

    // g_a := pow(g, a) mod p
    let g_a = pad_to_256(&big_g.modpow(&big_a, &big_p).to_bytes_be());

    // u := H(g_a | g_b)
    let u = BigUint::from_bytes_be(&h(&[&g_a, &g_b]));
    if u.is_zero() {
        return Err(MtprotoError::Message("SRP u is zero".into()));
    }

    // x := PH2(password, salt1, salt2)
    let x = BigUint::from_bytes_be(&ph2(password.as_bytes(), &algo.salt1, &algo.salt2));

    // v := pow(g, x) mod p
    let big_v = big_g.modpow(&x, &big_p);
    let k_v = (&big_k * &big_v) % &big_p;

    // t := (g_b - k_v) mod p (positive)
    let big_t = if big_g_b >= k_v {
        (&big_g_b - &k_v) % &big_p
    } else {
        (&big_p + &big_g_b - &k_v) % &big_p
    };
    if big_t.is_zero() {
        return Err(MtprotoError::Message("SRP t is zero".into()));
    }

    // s_a := pow(t, a + u * x) mod p
    let big_s_a = big_t.modpow(&(&big_a + &u * &x), &big_p);
    let k_a = h(&[&pad_to_256(&big_s_a.to_bytes_be())]);

    // M1 := H(H(p) xor H(g) | H(salt1) | H(salt2) | g_a | g_b | k_a)
    let m1 = h(&[
        &xor32(&h(&[&algo.p]), &h(&[&g_for_hash])),
        &h(&[&algo.salt1]),
        &h(&[&algo.salt2]),
        &g_a,
        &g_b,
        &k_a,
    ]);

    Ok(InputCheckPasswordSrp::InputCheckPasswordSrp(
        InputCheckPasswordSrpConstructor {
            srp_id,
            a: g_a.to_vec(),
            m1: m1.to_vec(),
        },
    ))
}

#[cfg(test)]
mod tests {
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
            157u8, 131, 196, 103, 0, 184, 116, 232, 7, 196, 85, 231, 17, 36, 30, 222, 158, 234, 98,
            88, 59, 56, 71, 215, 183, 123, 122, 50, 19, 32, 54, 206,
        ];
        let expected_g_a = {
            let mut v = [0_u8; 256];
            v[255] = 24;
            v
        };
        assert_eq!(m1, expected_m1);
        assert_eq!(g_a, expected_g_a);
    }
}
