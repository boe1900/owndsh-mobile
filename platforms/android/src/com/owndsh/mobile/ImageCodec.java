/**
 * [INPUT]: 依赖 Android ImageDecoder、Bitmap 与 JNI 交付的只读图片字节
 * [OUTPUT]: 有界解码 JPEG、PNG、WebP、GIF 的首帧，按目标尺寸输出重新编码的 sRGB 位图
 * [POS]: Android 引擎的图片编解码适配，附件准入、存储和模型请求留给 DSH
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package com.owndsh.mobile;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

final class ImageCodec {
    private static final int MAX_SOURCE_PIXELS = 24_000_000;
    private static final int MAX_SOURCE_SIDE = 16_384;
    private static final int MAX_OUTPUT_PIXELS = 4_194_304;

    private ImageCodec() {}

    static final class Facts {
        final String mediaType;
        final int width;
        final int height;
        final boolean hasAlpha;

        Facts(String mediaType, Bitmap bitmap) {
            this.mediaType = mediaType;
            width = bitmap.getWidth();
            height = bitmap.getHeight();
            hasAlpha = bitmap.hasAlpha();
        }
    }

    static Facts inspect(ByteBuffer source, int maxPixels, int maxSide) throws IOException {
        String[] mediaType = new String[1];
        Bitmap bitmap = decode(source, maxPixels, maxSide, 0, 0, mediaType);
        try {
            return new Facts(mediaType[0], bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    static byte[] encode(ByteBuffer source, int width, int height, String mediaType, int quality) throws IOException {
        Bitmap.CompressFormat format;
        switch (mediaType) {
            case "image/jpeg": format = Bitmap.CompressFormat.JPEG; break;
            case "image/png": format = Bitmap.CompressFormat.PNG; break;
            case "image/webp": format = Bitmap.CompressFormat.WEBP; break;
            default: throw new IllegalArgumentException("INVALID_IMAGE");
        }
        Bitmap bitmap = decode(source, MAX_SOURCE_PIXELS, MAX_SOURCE_SIDE, width, height, null);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if ((format == Bitmap.CompressFormat.JPEG && bitmap.hasAlpha()) || !bitmap.compress(format, quality, output)) {
                throw new IOException("Image encoding failed");
            }
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap decode(ByteBuffer source, int maxPixels, int maxSide, int targetWidth, int targetHeight,
            String[] mediaType) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(source.asReadOnlyBuffer()), (decoder, info, input) -> {
            Size size = info.getSize();
            int width = size.getWidth();
            int height = size.getHeight();
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("INVALID_IMAGE");
            if (Math.max(width, height) > Math.min(maxSide, MAX_SOURCE_SIDE)) {
                throw new IllegalArgumentException("IMAGE_DIMENSION_TOO_LARGE");
            }
            if ((long) width * height > Math.min(maxPixels, MAX_SOURCE_PIXELS)) {
                throw new IllegalArgumentException("IMAGE_TOO_MANY_PIXELS");
            }
            String mime = info.getMimeType();
            if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)
                    && !"image/webp".equals(mime) && !"image/gif".equals(mime)) {
                throw new IllegalArgumentException("UNSUPPORTED_IMAGE_TYPE");
            }
            if (mediaType != null) mediaType[0] = mime;
            if (targetWidth != 0) {
                if (targetWidth > width || targetHeight > height || (long) targetWidth * targetHeight > MAX_OUTPUT_PIXELS) {
                    throw new IllegalArgumentException("IMAGE_DIMENSION_TOO_LARGE");
                }
                decoder.setTargetSize(targetWidth, targetHeight);
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            decoder.setOnPartialImageListener(error -> false);
        });
    }
}
