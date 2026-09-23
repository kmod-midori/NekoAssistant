package moe.reimu.nekoassistant

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.IInterface
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import moe.reimu.nekoassistant.wrappers.ActivityManagerWrapper
import moe.reimu.nekoassistant.wrappers.FakeContext
import moe.reimu.nekoassistant.wrappers.ServiceManager
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.exitProcess

@Keep
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class UserService : IUserService.Stub() {
    private var virtualDisplay: VirtualDisplay? = null

    override fun destroy() {
        Log.i(TAG, "destroy")

        stopDisplay()

        exitProcess(0)
    }

    override fun startDisplay(
        width: Int, height: Int, dpi: Int, surface: Surface?
    ): Boolean {
        Log.i(TAG, "startDisplay $width x $height @ $dpi DPI")

        if (surface == null) {
            Log.e(TAG, "startDisplay called with null surface")
            return false
        }

        var flags = (DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                or VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS)
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                    or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP)
        }

        try {
            val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            val displayManager = ctor.newInstance(FakeContext.getInstance())

            @SuppressLint("WrongConstant")
            val virtualDisplay = displayManager.createVirtualDisplay(
                "NekoAssistant",
                width, height,
                dpi, surface, flags
            )

            Log.i(TAG, "Created virtual display $virtualDisplay")

            try {
                ServiceManager.windowManager.setDisplayImePolicy(
                    virtualDisplay.display.displayId,
                    2, /* DISPLAY_IME_POLICY_HIDE */
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set DISPLAY_IME_POLICY_HIDE", e)
            }

            this.virtualDisplay = virtualDisplay

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create display", e)
            return false
        }
    }

    override fun stopDisplay(): Boolean {
        Log.i(TAG, "stopDisplay")

        virtualDisplay?.release()
        virtualDisplay = null

        return true
    }

    private fun getGetTasksMethod(manager: IInterface): Method {
        return manager::class.java.getMethod("getTasks", Int::class.javaPrimitiveType)
    }

    override fun startActivity(
        intent: Intent,
        forceStop: Boolean
    ) {
        val options = ActivityOptions.makeBasic()
        val displayId = virtualDisplay?.display?.displayId
        if (displayId != null) {
            options.launchDisplayId = displayId
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            val am = ActivityManagerWrapper.get()
            am.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startActivity $intent", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getFocusedPackageName(): String? {
        val virtualDisplayId = virtualDisplay?.display?.displayId ?: return null

        try {
            val am = ServiceManager.activityManager
            val displayIdField = ActivityManager.RunningTaskInfo::class.java.getField("displayId")
            val isFocusedField = ActivityManager.RunningTaskInfo::class.java.getField("isFocused")

            val tasks = getGetTasksMethod(am).invoke(
                am, 1000
            ) as List<ActivityManager.RunningTaskInfo>

            for (task in tasks) {
                val displayId = displayIdField.getInt(task)
                val isFocused = isFocusedField.getBoolean(task)

                if (displayId == virtualDisplayId && isFocused) {
                    return task.topActivity?.packageName
                }
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get focused package", e)
            return null
        }
    }

    private val setDisplayIdMethod by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private val injectInputMethod by lazy {
        InputManager::class.java.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
    }

    val inputManager by lazy {
        FakeContext.getInstance().getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    override fun injectInputEvent(input: InputEvent): Boolean {
        val displayId = virtualDisplay?.display?.displayId ?: return false

        try {
            setDisplayIdMethod.invoke(input, displayId)
            Log.d(TAG, "injectInputEvent $input")
            return injectInputMethod.invoke(inputManager, input, 2) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input event $input", e)
            return false
        }
    }

    fun runInputCommand(command: String): Boolean {
        val displayId = virtualDisplay?.display?.displayId ?: return false

        val runtime = Runtime.getRuntime()
        val fullCommand = "input -d $displayId $command"
        Log.i(TAG, "Execute input command: $fullCommand")
        val process = runtime.exec(fullCommand)

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }

        return process.waitFor() == 0
    }

    private fun makeTapEvent(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent = MotionEvent.obtain(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        x,
        y,
        1.0f,
        0.05f, 0, 1.0f, 1.0f, 0, 0
    ).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }


    override fun injectTapEvent(x: Float, y: Float): Boolean {
        try {
            val downTime = SystemClock.uptimeMillis()

            val eventDown = makeTapEvent(downTime, MotionEvent.ACTION_DOWN, x, y)
            if (!injectInputEvent(eventDown)) {
                Log.e(TAG, "Failed to inject ACTION_DOWN event at ($x, $y)")
                return false
            }

            Thread.sleep(Random.nextLong(180, 220))

            val eventUp = makeTapEvent(downTime, MotionEvent.ACTION_UP, x, y)
            if (!injectInputEvent(eventUp)) {
                Log.e(TAG, "Failed to inject ACTION_UP event at ($x, $y)")
                return false
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "injectTapEvent($x, $y) failed", e)
            return false
        }
    }

    override fun injectKeyPressEvent(keyCode: Int): Boolean {
        return runInputCommand("keyevent $keyCode")
    }

    override fun injectSwipeEvent(
        x1: Float, y1: Float, x2: Float, y2: Float, duration: Long
    ): Boolean {
        return runInputCommand("swipe $x1 $y1 $x2 $y2 $duration")
    }

    companion object {
        private const val TAG = "UserService"


        // Internal fields copied from android.hardware.display.DisplayManager
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14
        private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}
