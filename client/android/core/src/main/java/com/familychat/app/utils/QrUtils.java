package com.familychat.app.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.oned.Code128Writer;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码/条形码生成工具（收付款码用）。生成逻辑与 MyQrCodeActivity 一致：zxing → BitMatrix → Bitmap。
 */
public class QrUtils {

    /** 生成二维码 Bitmap（正方形，size 像素）。 */
    public static Bitmap generateQr(String content, int size) {
        return generateQr(content, size, null);
    }

    /**
     * 生成二维码 Bitmap，可指定纠错级别。
     *
     * 密钥迁移载荷约 380 字符（远长于加好友/收款码），纠错级别直接决定
     * 版本号与模块密度，进而决定屏幕扫码成功率，因此需要能显式指定：
     *   - 级别越高纠错越强，但模块越密、越难扫；
     *   - 屏幕对屏幕的场景推荐 M（约 15% 纠错），是清晰度与鲁棒性的平衡点。
     *
     * @param errorCorrectionLevel zxing 纠错级别；null 表示用 zxing 默认（L）
     * @return Bitmap；生成失败返回 null
     */
    public static Bitmap generateQr(String content, int size,
                                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel errorCorrectionLevel) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = null;
            if (errorCorrectionLevel != null) {
                hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
                hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, errorCorrectionLevel);
                // 默认边距 4 模块，显式写出以免依赖库默认值变化
                hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            }
            BitMatrix matrix = errorCorrectionLevel == null
                    ? writer.encode(content == null ? "" : content, BarcodeFormat.QR_CODE, size, size)
                    : writer.encode(content == null ? "" : content, BarcodeFormat.QR_CODE, size, size, hints);

            // 用 int[] + setPixels 一次性写入：比逐像素 setPixel 快一个量级，
            // 密钥二维码尺寸较大（长载荷需要更多模块），逐像素写会有明显卡顿。
            int[] pixels = new int[size * size];
            int black = Color.BLACK, white = Color.WHITE;
            for (int y = 0; y < size; y++) {
                int rowStart = y * size;
                for (int x = 0; x < size; x++) {
                    pixels[rowStart + x] = matrix.get(x, y) ? black : white;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bmp.setPixels(pixels, 0, size, 0, 0, size, size);
            return bmp;
        } catch (Exception e) {
            android.util.Log.e("QrUtils", "generateQr error", e);
            return null;
        }
    }

    /** 识别 Bitmap 里的二维码/条形码，返回文本；无码或失败返回 null（相册选图扫码用）。 */
    public static String decodeBitmap(Bitmap bmp) {
        if (bmp == null) return null;
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            RGBLuminanceSource src = new RGBLuminanceSource(w, h, px);
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
            MultiFormatReader reader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,
                    java.util.Arrays.asList(BarcodeFormat.QR_CODE,
                            BarcodeFormat.CODE_128, BarcodeFormat.EAN_13, BarcodeFormat.CODE_39));
            reader.setHints(hints);
            Result r = reader.decode(bb);
            return r == null ? null : r.getText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成 CODE-128 条形码 Bitmap（付款码下方条形码）。 */
    public static Bitmap generateBarcode128(String content, int width, int height) {
        try {
            Code128Writer writer = new Code128Writer();
            BitMatrix matrix = writer.encode(content == null ? "" : content,
                    BarcodeFormat.CODE_128, width, height);
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            android.util.Log.e("QrUtils", "generateBarcode128 error", e);
            return null;
        }
    }
}
