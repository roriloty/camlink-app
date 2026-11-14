import Foundation
import ImageIO
import CoreGraphics
import UniformTypeIdentifiers
import CamLinkCore

/// Applies `AutoEditCore` to a saved JPEG and writes an edited copy (the original is never touched).
/// Port of AutoEditor.kt: plan from a ~900 px copy, render the full image band by band, keep the camera's metadata.
enum AutoEditor {
    static let PLAN_LONG_SIDE = 900
    static let BAND_ROWS = 256

    /// Returns true when `out` holds the edited JPEG.
    static func edit(src: URL, out: URL) -> Bool {
        do {
            guard let source = CGImageSourceCreateWithURL(src as CFURL, nil) else { throw err("cannot open") }
            // 1. Plan from a ~900 px copy (same size the model was trained at). Raw sensor orientation, like
            //    Android's BitmapFactory (the Orientation tag is copied to the result).
            let thumbOpts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                              kCGImageSourceCreateThumbnailWithTransform: false,
                                              kCGImageSourceThumbnailMaxPixelSize: PLAN_LONG_SIDE]
            guard let smallImg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOpts as CFDictionary) else { throw err("cannot decode") }
            let (sp, sw, sh) = try pixels(smallImg)
            let plan = AutoEditCore.plan(small: sp, w: sw, h: sh)

            // 2. Render the full-size image, bands in parallel.
            guard let fullImg = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw err("cannot decode full image") }
            let w = fullImg.width, h = fullImg.height
            guard let ctx = context(w, h) else { throw err("out of memory") }
            ctx.draw(fullImg, in: CGRect(x: 0, y: 0, width: w, height: h))
            guard let base = ctx.data else { throw err("no pixels") }
            let px = base.bindMemory(to: UInt32.self, capacity: w * h)
            let stride = ctx.bytesPerRow / 4
            let bands = (h + BAND_ROWS - 1) / BAND_ROWS
            if stride == w {
                DispatchQueue.concurrentPerform(iterations: bands) { b in
                    let y = b * BAND_ROWS
                    AutoEditCore.render(px + y * w, width: w, rows: min(BAND_ROWS, h - y), y0: y, fullH: h, plan: plan)
                }
            } else {
                DispatchQueue.concurrentPerform(iterations: h) { y in
                    AutoEditCore.render(px + y * stride, width: w, rows: 1, y0: y, fullH: h, plan: plan)
                }
            }
            guard let edited = ctx.makeImage() else { throw err("encode failed") }

            // 3. JPEG at quality 95 with the camera's metadata (orientation, exposure, lens, date, GPS…).
            try? FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard let dest = CGImageDestinationCreateWithURL(out as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { throw err("cannot write") }
            var props = (CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]) ?? [:]
            var tiff = (props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]) ?? [:]
            tiff[kCGImagePropertyTIFFSoftware] = "CamLink auto-edit"
            props[kCGImagePropertyTIFFDictionary] = tiff
            props[kCGImageDestinationLossyCompressionQuality] = 0.95
            // Pixel sizes come from the new image; drop the old embedded thumbnail info.
            props.removeValue(forKey: kCGImagePropertyPixelWidth); props.removeValue(forKey: kCGImagePropertyPixelHeight)
            CGImageDestinationAddImage(dest, edited, props as CFDictionary)
            guard CGImageDestinationFinalize(dest) else { throw err("encode failed") }
            return true
        } catch {
            DebugLog.d("Auto-edit failed, uploading the original: \(error.localizedDescription)")
            try? FileManager.default.removeItem(at: out)
            return false
        }
    }

    /// 8-bit sRGB, alpha first, little-endian 32-bit words = 0xAARRGGBB, the same layout as an Android ARGB int.
    private static func context(_ w: Int, _ h: Int) -> CGContext? {
        CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                  bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
    }

    private static func pixels(_ img: CGImage) throws -> ([UInt32], Int, Int) {
        let w = img.width, h = img.height
        guard let ctx = context(w, h) else { throw err("out of memory") }
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let d = ctx.data else { throw err("no pixels") }
        let stride = ctx.bytesPerRow / 4
        let p = d.bindMemory(to: UInt32.self, capacity: stride * h)
        var out = [UInt32](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w { out[y * w + x] = p[y * stride + x] | 0xFF00_0000 } }
        return (out, w, h)
    }

    private static func err(_ s: String) -> NSError { NSError(domain: "AutoEdit", code: 1, userInfo: [NSLocalizedDescriptionKey: s]) }
}
