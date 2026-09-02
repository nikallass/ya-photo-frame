package ru.dvedev.me.yaphotoframe.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Квадратик со ссылкой — чтобы не набирать адрес пультом или по памяти.
 *
 * Телефон наводят на экран телевизора с двух-трёх метров, поэтому модуль кода
 * должен быть крупным: берём небольшую матрицу и растягиваем её без сглаживания.
 */
object QrCode {

    fun render(text: String, sizePixels: Int): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)

        val modules = matrix.width
        val small = Bitmap.createBitmap(modules, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until modules) {
                small.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        // Без сглаживания: размытые границы модулей телефон читает хуже.
        Bitmap.createScaledBitmap(small, sizePixels, sizePixels, false)
            .also { if (it !== small) small.recycle() }
    } catch (e: Exception) {
        null
    }

    private const val QUIET_ZONE = 2
}
