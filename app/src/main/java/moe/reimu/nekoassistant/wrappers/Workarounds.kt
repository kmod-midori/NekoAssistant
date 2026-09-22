package moe.reimu.nekoassistant.wrappers

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Looper
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method


@SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "BlockedPrivateApi")
class Workarounds {
    private val activityThreadClass: Class<*> = Class.forName("android.app.ActivityThread")
    private val activityThread: Any

    init {
        Looper.prepare()

        // ActivityThread activityThread = new ActivityThread();
        val activityThreadConstructor: Constructor<*> = activityThreadClass.getDeclaredConstructor()
        activityThreadConstructor.isAccessible = true
        activityThread = activityThreadConstructor.newInstance()


        // ActivityThread.sCurrentActivityThread = activityThread;
        val sCurrentActivityThreadField =
            activityThreadClass.getDeclaredField("sCurrentActivityThread")
        sCurrentActivityThreadField.isAccessible = true
        sCurrentActivityThreadField.set(null, activityThread)


        // activityThread.mSystemThread = true;
        val mSystemThreadField = activityThreadClass.getDeclaredField("mSystemThread")
        mSystemThreadField.isAccessible = true
        mSystemThreadField.setBoolean(activityThread, true)

        fillConfigurationController()
        fillAppInfo()
        fillAppContext()
    }

    fun getSystemContext(): Context {
        val getSystemContextMethod: Method =
            activityThreadClass.getDeclaredMethod("getSystemContext")
        return getSystemContextMethod.invoke(activityThread) as Context
    }

    private fun fillConfigurationController() {
        val configurationControllerClass = Class.forName("android.app.ConfigurationController")
        val activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal")

        // configurationController = new ConfigurationController(ACTIVITY_THREAD);
        val configurationControllerConstructor: Constructor<*> =
            configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass)
        configurationControllerConstructor.isAccessible = true
        val configurationController: Any? =
            configurationControllerConstructor.newInstance(activityThread)

        // ACTIVITY_THREAD.mConfigurationController = configurationController;
        val configurationControllerField =
            activityThreadClass.getDeclaredField("mConfigurationController")
        configurationControllerField.setAccessible(true)
        configurationControllerField.set(activityThread, configurationController)
    }

    private fun fillAppInfo() {
        try {
            // ActivityThread.AppBindData appBindData = new ActivityThread.AppBindData();
            val appBindDataClass = Class.forName($$"android.app.ActivityThread$AppBindData")
            val appBindDataConstructor: Constructor<*> = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData: Any = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = FakeContext.PACKAGE_NAME

            // appBindData.appInfo = applicationInfo;
            val appInfoField = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(appBindData, applicationInfo)

            // activityThread.mBoundApplication = appBindData;
            val mBoundApplicationField: Field =
                activityThreadClass.getDeclaredField("mBoundApplication")
            mBoundApplicationField.isAccessible = true
            mBoundApplicationField.set(activityThread, appBindData)
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
        }
    }

    private fun fillAppContext() {
        try {
            val app = Application()
            val baseField = ContextWrapper::class.java.getDeclaredField("mBase")
            baseField.isAccessible = true
            baseField.set(app, FakeContext.getInstance())

            // activityThread.mInitialApplication = app;
            val mInitialApplicationField: Field =
                activityThreadClass.getDeclaredField("mInitialApplication")
            mInitialApplicationField.isAccessible = true
            mInitialApplicationField.set(activityThread, app)
        } catch (throwable: Throwable) {
            throwable.printStackTrace()
        }
    }

    companion object {
        private val LOCK = Any()
        private var INSTANCE: Workarounds? = null

        fun getInstance() = synchronized(LOCK) {
            if (INSTANCE == null) INSTANCE = Workarounds()
            INSTANCE!!
        }
    }
}