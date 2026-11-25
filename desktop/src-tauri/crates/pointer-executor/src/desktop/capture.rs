use std::fs;
use std::io::Cursor;
use std::path::Path;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use image::codecs::jpeg::JpegEncoder;
use image::imageops::FilterType;
use image::{DynamicImage, ImageFormat};
use serde_json::{Value, json};

use super::{Desktop, Screen, system};
use crate::{ExecError, Request};

const MAX_ZOOM: f64 = 4.0;
const JPEG_QUALITY: u8 = 80;

#[derive(Clone, Copy)]
pub struct Rect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

pub fn scaled_size(width: u32, height: u32, max_width: u32, max_height: u32) -> (u32, u32) {
    let scale = scale_to_fit(width, height, max_width, max_height, 1.0);
    scaled(width, height, scale)
}

fn scale_to_fit(width: u32, height: u32, max_width: u32, max_height: u32, max_scale: f64) -> f64 {
    let horizontal = f64::from(max_width) / f64::from(width);
    let vertical = f64::from(max_height) / f64::from(height);
    max_scale.min(horizontal).min(vertical)
}

fn scaled(width: u32, height: u32, scale: f64) -> (u32, u32) {
    let width = (f64::from(width) * scale).round().max(1.0) as u32;
    let height = (f64::from(height) * scale).round().max(1.0) as u32;
    (width, height)
}

pub fn capture(
    screen: &Screen,
    runtime_dir: &Path,
    zoom: Option<Rect>,
) -> Result<Value, ExecError> {
    let file = runtime_dir.join(format!("shot-{}.png", uuid::Uuid::now_v7()));
    let mut command = system::tool(screen, "scrot");
    command.arg("--overwrite").arg(&file);
    let taken = system::run(command, "capture_failed");
    let decoded = taken.and_then(|_| {
        image::open(&file).map_err(|error| ExecError::failed("capture_failed", error.to_string()))
    });
    let _ = fs::remove_file(&file);
    let full = decoded?;
    let (source, region_width, region_height, max_scale) = match zoom {
        Some(rect) => (
            full.crop_imm(rect.x, rect.y, rect.width, rect.height),
            rect.width,
            rect.height,
            MAX_ZOOM,
        ),
        None => (full, screen.width, screen.height, 1.0),
    };
    let scale = scale_to_fit(
        region_width,
        region_height,
        screen.max_image_width,
        screen.max_image_height,
        max_scale,
    );
    let (width, height) = scaled(region_width, region_height, scale);
    let image = if (width, height) == (source.width(), source.height()) {
        source
    } else {
        source.resize_exact(width, height, FilterType::Triangle)
    };
    let (media_type, bytes) = encode(&image, screen.max_image_bytes)?;
    let mut result = json!({
        "mediaType": media_type,
        "width": width,
        "height": height,
        "screenWidth": region_width,
        "screenHeight": region_height,
        "data": STANDARD.encode(bytes),
    });
    if let Some(rect) = zoom {
        result["zoom"] =
            json!({ "x": rect.x, "y": rect.y, "width": rect.width, "height": rect.height });
    }
    Ok(result)
}

fn encode(image: &DynamicImage, max_bytes: usize) -> Result<(&'static str, Vec<u8>), ExecError> {
    let mut png = Vec::new();
    image
        .write_to(&mut Cursor::new(&mut png), ImageFormat::Png)
        .map_err(|error| ExecError::failed("capture_failed", error.to_string()))?;
    if png.len() <= max_bytes {
        return Ok(("image/png", png));
    }
    let mut jpeg = Vec::new();
    JpegEncoder::new_with_quality(&mut jpeg, JPEG_QUALITY)
        .encode_image(&image.to_rgb8())
        .map_err(|error| ExecError::failed("capture_failed", error.to_string()))?;
    if jpeg.len() <= max_bytes {
        return Ok(("image/jpeg", jpeg));
    }
    Err(ExecError::failed(
        "image_too_large",
        format!(
            "The screenshot is {} bytes as JPEG, over the limit of {max_bytes}",
            jpeg.len()
        ),
    ))
}

impl Desktop {
    pub(super) fn screenshot(&self, request: &Request) -> Result<Value, ExecError> {
        let screen = self.screen()?;
        let zoom = match request.args.get("zoom").filter(|zoom| !zoom.is_null()) {
            Some(zoom) => Some(zoom_rect(&screen, zoom)?),
            None => None,
        };
        let image = capture(&screen, &self.runtime_dir, zoom)?;
        let (image_width, image_height) = screen.image_size();
        let mut result = json!({
            "width": image_width,
            "height": image_height,
            "screenWidth": screen.width,
            "screenHeight": screen.height,
            "images": [image],
        });
        if let Some(rect) = zoom {
            result["zoom"] =
                json!({ "x": rect.x, "y": rect.y, "width": rect.width, "height": rect.height });
        }
        Ok(result)
    }
}

fn zoom_rect(screen: &Screen, zoom: &Value) -> Result<Rect, ExecError> {
    let field = |name: &str| -> Result<u32, ExecError> {
        zoom.get(name)
            .and_then(Value::as_f64)
            .filter(|value| value.is_finite() && *value >= 0.0)
            .map(|value| value.round() as u32)
            .ok_or_else(|| {
                ExecError::rejected(
                    "invalid_coordinates",
                    format!("zoom.{name} must be a number"),
                )
            })
    };
    let rect = Rect {
        x: field("x")?,
        y: field("y")?,
        width: field("width")?,
        height: field("height")?,
    };
    let fits = rect.width > 0
        && rect.height > 0
        && rect.x.saturating_add(rect.width) <= screen.width
        && rect.y.saturating_add(rect.height) <= screen.height;
    if !fits {
        return Err(ExecError::rejected(
            "invalid_coordinates",
            format!(
                "The zoom rectangle must lie inside the {}x{} screen",
                screen.width, screen.height
            ),
        ));
    }
    Ok(rect)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_the_default_display_unscaled() {
        assert_eq!(scaled_size(1280, 800, 1280, 800), (1280, 800));
        assert_eq!(scaled_size(1024, 768, 1280, 800), (1024, 768));
    }

    #[test]
    fn scales_large_displays_down_with_the_aspect_ratio() {
        assert_eq!(scaled_size(1920, 1200, 1280, 800), (1280, 800));
        assert_eq!(scaled_size(1920, 1080, 1280, 800), (1280, 720));
    }

    #[test]
    fn zooms_small_regions_at_most_fourfold() {
        let scale = scale_to_fit(100, 50, 1280, 800, MAX_ZOOM);
        assert_eq!(scaled(100, 50, scale), (400, 200));
        let scale = scale_to_fit(640, 200, 1280, 800, MAX_ZOOM);
        assert_eq!(scaled(640, 200, scale), (1280, 400));
    }

    #[test]
    fn falls_back_to_jpeg_and_then_fails_over_the_limit() {
        let noise = image::RgbImage::from_fn(400, 300, |x, y| {
            let value = (x.wrapping_mul(7919) ^ y.wrapping_mul(104_729)) as u8;
            image::Rgb([value, value.wrapping_mul(3), value.wrapping_add(91)])
        });
        let image = DynamicImage::ImageRgb8(noise);
        let (media_type, _) = encode(&image, usize::MAX).unwrap();
        assert_eq!(media_type, "image/png");
        let mut png = Vec::new();
        image
            .write_to(&mut Cursor::new(&mut png), ImageFormat::Png)
            .unwrap();
        let (media_type, _) = encode(&image, png.len() - 1).unwrap();
        assert_eq!(media_type, "image/jpeg");
        let error = encode(&image, 10).unwrap_err();
        assert_eq!(error.code, "image_too_large");
    }
}
