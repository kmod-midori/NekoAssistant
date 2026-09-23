package moe.reimu.nekoassistant

import android.util.Size

fun Size.scaleToMaxDimension(maxDimension: Int): Size {
    val longestSide = maxOf(width, height)
    if (longestSide <= maxDimension) {
        return Size(width, height)
    }

    val scale = maxDimension.toFloat() / longestSide.toFloat()
    return Size((width * scale).toInt(), (height * scale).toInt())
}