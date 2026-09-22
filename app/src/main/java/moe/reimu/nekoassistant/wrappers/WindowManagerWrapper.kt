package moe.reimu.nekoassistant.wrappers

import android.os.Build
import android.os.IInterface


class WindowManagerWrapper(private val manager: IInterface) {
    private val setDisplayImePolicyMethod by lazy {
        manager.javaClass.getMethod(
            "setDisplayImePolicy",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
    }

    fun setDisplayImePolicy(displayId: Int, displayImePolicy: Int) {
        setDisplayImePolicyMethod.invoke(manager, displayId, displayImePolicy)
    }
}