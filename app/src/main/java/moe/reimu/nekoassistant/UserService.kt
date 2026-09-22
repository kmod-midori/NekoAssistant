package moe.reimu.nekoassistant

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.IInterface
import android.os.Looper
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import androidx.core.graphics.createBitmap
import moe.reimu.nekoassistant.wrappers.ActivityManagerWrapper
import moe.reimu.nekoassistant.wrappers.FakeContext
import moe.reimu.nekoassistant.wrappers.ServiceManager
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.exitProcess

@Keep
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class UserService : IUserService.Stub() {
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    private val lastBitmapLock = Any()
    private var lastBitmap: Bitmap? = null
    private var lastCropRect: Rect? = null

    private var imageReader: ImageReader? = null
    private val imageListener = ImageReader.OnImageAvailableListener { currentReader ->
        val image = currentReader.acquireLatestImage()
        if (image == null) {
            Log.w(TAG, "No image available")
            return@OnImageAvailableListener
        }
        image.use { handleImage(it) }
    }

    private val previewFrameListeners = RemoteCallbackList<IPreviewFrameListener>()

    @Volatile
    private var lastPreviewFrameDispatchAtMs: Long = 0L

    private val previewDispatchExecutor = Executors.newSingleThreadExecutor()

    fun handleImage(image: Image) {
        try {
            val width = image.width
            val bitmapHeight = image.height
            val planes = image.planes

            val buffer = planes[0].buffer

            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmapWidth = width + rowPadding / pixelStride

            val currentBitmap = synchronized(lastBitmapLock) {
                var currentBitmap = lastBitmap

                // Initialize if null or not match
                if (currentBitmap == null || currentBitmap.width != bitmapWidth || currentBitmap.height != bitmapHeight) {
                    currentBitmap = createBitmap(bitmapWidth, bitmapHeight)
                    this.lastBitmap = currentBitmap
                    this.lastCropRect = image.cropRect
                }

                currentBitmap.copyPixelsFromBuffer(buffer)
                currentBitmap
            }

            // Dispatch preview frame asynchronously to avoid blocking ImageReader loop
            val now = SystemClock.elapsedRealtime()
            val shouldDispatch = now - lastPreviewFrameDispatchAtMs >= PREVIEW_FRAME_INTERVAL_MS
            if (shouldDispatch && previewFrameListeners.registeredCallbackCount > 0) {
                lastPreviewFrameDispatchAtMs = now
                previewDispatchExecutor.execute {
                    dispatchPreviewFrame(currentBitmap, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle image", e)
        }
    }

    private fun dispatchPreviewFrame(currentBitmap: Bitmap?, force: Boolean = false) {
        val count = previewFrameListeners.beginBroadcast()

        if (count == 0 && !force) {
            previewFrameListeners.finishBroadcast()
            return
        }

        val previewBitmap = if (currentBitmap != null) {
            scaleBitmapToMaxDimension(currentBitmap, PREVIEW_MAX_DIMENSION)
        } else {
            null
        }

        try {
            for (index in 0 until count) {
                val listener = previewFrameListeners.getBroadcastItem(index)
                try {
                    listener.onPreviewFrame(previewBitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to dispatch preview frame to $listener", e)
                }
            }
        } finally {
            previewFrameListeners.finishBroadcast()
        }
    }

    override fun registerPreviewFrameListener(listener: IPreviewFrameListener?) {
        if (listener == null) {
            return
        }
        previewFrameListeners.register(listener)
    }

    override fun unregisterPreviewFrameListener(listener: IPreviewFrameListener?) {
        if (listener == null) {
            return
        }
        previewFrameListeners.unregister(listener)
    }

    override fun destroy() {
        Log.i(TAG, "destroy")

        stopDisplay()
        previewFrameListeners.kill()
        previewDispatchExecutor.shutdown()

        exitProcess(0)
    }

    @SuppressLint("WrongConstant")
    override fun startDisplay(
        width: Int, height: Int, dpi: Int
    ): Boolean {
        Log.i(TAG, "startDisplay $width x $height @ $dpi DPI")

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

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 16)
            imageReader.setOnImageAvailableListener(imageListener, handler)

            val virtualDisplay = displayManager.createVirtualDisplay(
                "NekoAssistant",
                width, height,
                dpi, imageReader.surface, flags
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
            this.imageReader = imageReader

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
        imageReader?.close()
        imageReader = null
        synchronized(lastBitmapLock) {
            lastBitmap = null
        }

        // Dispatch final null frame asynchronously
        previewDispatchExecutor.execute {
            dispatchPreviewFrame(null, true)
        }

        return true
    }

    override fun getLastBitmap() = synchronized(lastBitmapLock) {
        val bitmap = lastBitmap ?: return@synchronized null
        val cropRect = lastCropRect
        if (cropRect != null) {
            Bitmap.createBitmap(
                bitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height()
            )
        } else {
            Bitmap.createBitmap(bitmap)
        }.asShared()
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

    fun makeKeyEvent(downTime: Long, action: Int, keyCode: Int) = KeyEvent(
        downTime,
        SystemClock.uptimeMillis(),
        action,
        keyCode,
        0,
        0,
        KeyCharacterMap.VIRTUAL_KEYBOARD,
        0,
        0,
        InputDevice.SOURCE_KEYBOARD
    )

    override fun injectKeyPressEvent(keyCode: Int): Boolean {
        return runInputCommand("keyevent $keyCode")
//        try {
//            val downTime = SystemClock.uptimeMillis()
//
//            val downEvent = makeKeyEvent(downTime, KeyEvent.ACTION_DOWN, keyCode)
//            if (!injectInputEvent(downEvent)) {
//                return false
//            }
//
//            Thread.sleep(Random.nextLong(180, 220))
//
//            val upEvent = makeKeyEvent(downTime, KeyEvent.ACTION_UP, keyCode)
//            if (!injectInputEvent(upEvent)) {
//                return false
//            }
//
//            return true
//        } catch (e: Exception) {
//            Log.e(TAG, "injectKeyPressEvent($keyCode) failed", e)
//            return false
//        }
    }

    override fun injectSwipeEvent(
        x1: Float, y1: Float, x2: Float, y2: Float, duration: Long
    ): Boolean {
        return runInputCommand("swipe $x1 $y1 $x2 $y2 $duration")
//        val downTime = System.currentTimeMillis()
//        injectInputEvent(
//            MotionEvent.obtain(
//                downTime, downTime,
//                MotionEvent.ACTION_DOWN,
//                x1, y1, 1.0f, 0.05f,
//                0,
//                1.0f, 1.0f,
//                0, 0
//            ).apply {
//                source = InputDevice.SOURCE_TOUCHSCREEN
//            })
//
//        val swipeEventPeriodMillis = 1000.0f / 120.0f // 120 Hz
//
//        val endTime = downTime + duration
//        var nowTime = System.currentTimeMillis()
//
//        var injectedCount = 1
//
//        while (nowTime < endTime) {
//            var elapsedTime = nowTime - downTime
//
//            // Ensure that we inject at most at the frequency of SWIPE_EVENT_HZ_DEFAULT
//            // by waiting an additional delta between the actual time and expected time.
//            val deviationMillis = floor(
//                injectedCount * swipeEventPeriodMillis - elapsedTime
//            ).toLong()
//            if (deviationMillis > 0) {
//                // Make sure not to exceed the duration and inject an extra event
//                if (deviationMillis > endTime - nowTime) {
//                    Thread.sleep(endTime - nowTime)
//                    break
//                }
//                Thread.sleep(deviationMillis)
//            }
//
//            nowTime = System.currentTimeMillis()
//            elapsedTime = nowTime - downTime
//            val alpha = elapsedTime.toFloat() / duration.toFloat()
//
//            val currentX = x1 + (x2 - x1) * alpha
//            val currentY = y1 + (y2 - y1) * alpha
//            injectInputEvent(
//                MotionEvent.obtain(
//                    downTime, nowTime,
//                    MotionEvent.ACTION_MOVE,
//                    currentX, currentY, 1.0f, 0.05f,
//                    0,
//                    1.0f, 1.0f,
//                    0, 0
//                ).apply {
//                    source = InputDevice.SOURCE_TOUCHSCREEN
//                })
//
//            injectedCount += 1
//            nowTime = System.currentTimeMillis()
//        }
//
//        injectInputEvent(
//            MotionEvent.obtain(
//                downTime, nowTime,
//                MotionEvent.ACTION_UP,
//                x2, y2, 0.0f, 0.0f,
//                0,
//                1.0f, 1.0f,
//                0, 0
//            ).apply {
//                source = InputDevice.SOURCE_TOUCHSCREEN
//            })
//
//        return true
    }

    companion object {
        private const val TAG = "UserService"
        private const val PREVIEW_FRAME_INTERVAL_MS = 100L
        private const val PREVIEW_MAX_DIMENSION = 1024


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
