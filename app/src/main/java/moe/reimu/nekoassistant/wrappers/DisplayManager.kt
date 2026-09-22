package moe.reimu.nekoassistant.wrappers

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class DisplayManager(private val manager: Any) {
    private val createVirtualDisplayMethod by lazy {
        DisplayManager::class.java
            .getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java
            )
    }

    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        displayIdToMirror: Int,
        surface: Surface?
    ): VirtualDisplay? {
        return createVirtualDisplayMethod.invoke(
            null,
            name,
            width,
            height,
            displayIdToMirror,
            surface
        ) as VirtualDisplay?
    }

    fun createNewVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int
    ): VirtualDisplay? {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true

        val dm = ctor.newInstance(FakeContext.getInstance())
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
    }

    companion object {
        fun create(): moe.reimu.nekoassistant.wrappers.DisplayManager {
            val clazz = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstanceMethod = clazz.getDeclaredMethod("getInstance")
            val dmg = getInstanceMethod.invoke(null)!!
            return DisplayManager(dmg)
        }
    }
}