package moe.reimu.nekoassistant.wrappers

import android.annotation.SuppressLint
import android.content.IContentProvider
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import java.lang.reflect.Method


@SuppressLint("PrivateApi")
class ActivityManagerWrapper(private val manager: IInterface) {
    private var getContentProviderExternalMethod: Method? = null
    private var getContentProviderExternalMethodNewVersion = true

    private val startActivityAsUserMethod by lazy {
        val iApplicationThreadClass = Class.forName("android.app.IApplicationThread")
        val profilerInfo = Class.forName("android.app.ProfilerInfo")
        manager::class.java.getMethod(
            "startActivityAsUser",
            iApplicationThreadClass,
            String::class.java,
            Intent::class.java,
            String::class.java,
            IBinder::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            profilerInfo,
            Bundle::class.java,
            Int::class.javaPrimitiveType
        )
    }

    fun startActivity(intent: Intent, options: Bundle) {
        startActivityAsUserMethod.invoke(
            /* this */ manager,
            /* caller */ null,
            /* callingPackage */ FakeContext.PACKAGE_NAME,
            /* intent */ intent,
            /* resolvedType */ null,
            /* resultTo */ null,
            /* resultWho */ null,
            /* requestCode */ 0,
            /* startFlags */ 0,
            /* profilerInfo */ null,
            /* bOptions */ options,
            /* userId */ /* UserHandle.USER_CURRENT */ -2,
        )
    }

    private fun getGetContentProviderExternalMethod(): Method {
        if (getContentProviderExternalMethod == null) {
            try {
                getContentProviderExternalMethod = manager.javaClass
                    .getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        IBinder::class.java,
                        String::class.java
                    )
            } catch (e: NoSuchMethodException) {
                // old version
                getContentProviderExternalMethod = manager.javaClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java
                )
                getContentProviderExternalMethodNewVersion = false
            }
        }
        return getContentProviderExternalMethod!!
    }

    fun getContentProviderExternal(name: String?, token: IBinder?): IContentProvider? {
        try {
            val method = getGetContentProviderExternalMethod()
            val args = if (getContentProviderExternalMethodNewVersion) {
                // new version
                arrayOf(name, FakeContext.ROOT_UID, token, null)
            } else {
                // old version
                arrayOf(name, FakeContext.ROOT_UID, token)
            }
            // ContentProviderHolder providerHolder = getContentProviderExternal(...);
            val providerHolder: Any = method.invoke(manager, args) ?: return null
            // IContentProvider provider = providerHolder.provider;
            val providerField = providerHolder.javaClass.getDeclaredField("provider")
            providerField.isAccessible = true
            return providerField.get(providerHolder) as IContentProvider?
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "Could not invoke method", e)
            return null
        }
    }

    companion object {
        private const val TAG = "ActivityManagerWrapper"

        private val instance by lazy {
            val cls = Class.forName("android.app.ActivityManagerNative")
            val getDefaultMethod = cls.getDeclaredMethod("getDefault")
            val am = getDefaultMethod.invoke(null) as IInterface
            ActivityManagerWrapper(am)
        }

        fun get() = instance
    }
}