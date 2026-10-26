use crate::MtprotoError;
use rxing::{
    BarcodeFormat, BinaryBitmap, DecodeHints, EncodeHints, ImmutableReader, Luma8LuminanceSource,
    LuminanceSource, Writer,
    common::HybridBinarizer,
    qrcode::{QRCodeReader, QRCodeWriter},
};

const MAX_SIDE: u32 = 2048;

#[derive(uniffi::Record)]
pub struct QrCodeMatrix {
    pub size: u32,
    pub luma: Vec<u8>,
}

pub fn generate(text: &str, size: u32) -> Result<QrCodeMatrix, MtprotoError> {
    if text.is_empty() || text.len() > 4096 || size == 0 || size > MAX_SIDE {
        return Err(MtprotoError::Message("invalid QR input".into()));
    }
    let hints = EncodeHints {
        CharacterSet: (!text.is_ascii()).then(|| "UTF-8".into()),
        ..Default::default()
    };
    let matrix = QRCodeWriter
        .encode_with_hints(
            text,
            &BarcodeFormat::QR_CODE,
            size as i32,
            size as i32,
            &hints,
        )
        .map_err(|_| MtprotoError::Message("QR encoding failed".into()))?;
    let size = matrix.width();
    let luma = (0..size)
        .flat_map(|y| (0..size).map(move |x| (x, y)))
        .map(|(x, y)| if matrix.get(x, y) { 0 } else { 255 })
        .collect();
    Ok(QrCodeMatrix { size, luma })
}

pub fn decode(luma: Vec<u8>, width: u32, height: u32) -> Result<Option<String>, MtprotoError> {
    if width == 0
        || height == 0
        || width > MAX_SIDE
        || height > MAX_SIDE
        || luma.len() != width as usize * height as usize
    {
        return Err(MtprotoError::Message("invalid QR image dimensions".into()));
    }
    let mut source = Luma8LuminanceSource::new(luma, width, height)
        .map_err(|_| MtprotoError::Message("invalid QR image".into()))?;
    let hints = DecodeHints {
        TryHarder: Some(true),
        ..Default::default()
    };
    for _ in 0..2 {
        let mut bitmap = BinaryBitmap::new(HybridBinarizer::new(source.clone()));
        if let Ok(result) = QRCodeReader::new().immutable_decode_with_hints(&mut bitmap, &hints) {
            return Ok(Some(result.getText().to_owned()));
        }
        source.invert();
    }
    Ok(None)
}
