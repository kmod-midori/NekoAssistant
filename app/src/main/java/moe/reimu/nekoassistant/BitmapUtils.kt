package moe.reimu.nekoassistant

import android.graphics.Bitmap
import android.util.Size
import androidx.core.graphics.scale

/**
 * Scales a bitmap so that its longest side is less than the specified maximum dimension.
 * If the bitmap is already smaller, it is returned unchanged.
 *
 * @param bitmap The bitmap to scale
 * @param maxDimension The maximum dimension for the longest side (default: 1080)
 * @return The scaled bitmap, or the original if no scaling is needed
 */
fun scaleBitmapToMaxDimension(bitmap: Bitmap, maxDimension: Int = 1080): Bitmap {
    val width = bitmap.width
    val height = bitmap.height

    // Find the longest side
    val longestSide = maxOf(width, height)

    // If already smaller than max, return the original
    if (longestSide <= maxDimension) {
        return bitmap
    }

    // Calculate scaling factor
    val scale = maxDimension.toFloat() / longestSide.toFloat()

    // Calculate new dimensions
    val newWidth = (width * scale).toInt()
    val newHeight = (height * scale).toInt()

    // Create and return scaled bitmap
    return bitmap.scale(newWidth, newHeight)
}

fun Size.scaleToMaxDimension(maxDimension: Int): Size {
    val longestSide = maxOf(width, height)
    if (longestSide <= maxDimension) {
        return Size(width, height)
    }

    val scale = maxDimension.toFloat() / longestSide.toFloat()
    return Size((width * scale).toInt(), (height * scale).toInt())
}