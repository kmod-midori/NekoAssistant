package moe.reimu.nekoassistant.wrappers

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.IInterface
import java.lang.reflect.Method


@SuppressLint("PrivateApi,DiscouragedPrivateApi")
object ServiceManager {
    private val getServiceMethod: Method = Class
        .forName("android.os.ServiceManager")
        .getDeclaredMethod("getService", String::class.java)

    fun getService(service: String, type: String): IInterface {
        val binder = getServiceMethod.invoke(null, service) as IBinder
        val asInterfaceMethod = Class
            .forName($$"$$type$Stub")
            .getMethod("asInterface", IBinder::class.java)
        return asInterfaceMethod.invoke(null, binder) as IInterface
    }

    val displayManager by lazy {
        DisplayManager.create()
    }

    val activityManager by lazy {
        val cls = Class.forName("android.app.ActivityManagerNative")
        val getDefaultMethod = cls.getDeclaredMethod("getDefault")
        val am = getDefaultMethod.invoke(null) as IInterface
        am
    }

    val windowManager by lazy {
        WindowManagerWrapper(getService("window", "android.view.IWindowManager"))
    }
}