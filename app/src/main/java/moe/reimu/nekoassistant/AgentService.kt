package moe.reimu.nekoassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import moe.reimu.nekoassistant.ai.Action
import moe.reimu.nekoassistant.ai.AgentMessage
import moe.reimu.nekoassistant.ai.ChatMessage
import moe.reimu.nekoassistant.ai.ExecutorAgent
import moe.reimu.nekoassistant.ai.InferenceStatus
import moe.reimu.nekoassistant.ai.createLlmClient
import moe.reimu.nekoassistant.ai.PlannerAgent
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration
import moe.reimu.nekoassistant.data.LlmConfigurationRepository
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration.Companion.milliseconds

class AgentService : LifecycleService(), Shizuku.OnBinderReceivedListener,
    Shizuku.OnRequestPermissionResultListener {

    private val binder = LocalBinder()

    private var currentJob: Job? = null
    private lateinit var llmConfigurationRepository: LlmConfigurationRepository

    inner class LocalBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private var userService: IUserService? = null

    val displayMetrics = DisplayMetrics()
    var screenshotSize = Size(0, 0)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(p0: ComponentName, p1: IBinder) {
            Log.i(TAG, "UserService onServiceConnected")
            userService = IUserService.Stub.asInterface(p1)
        }

        override fun onServiceDisconnected(p0: ComponentName) {
            Log.i(TAG, "UserService onServiceDisconnected")
            userService = null
        }
    }

    private val frameThread = HandlerThread("display-frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    private var imageReader: ImageReader? = null

    private val imageLock = Any()
    private var heldImage: Image? = null
    private var lastPreviewFrameAtMs = 0L

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener

        val previewed = try {
            previewFrame(image)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preview frame", e)
            false
        }

        synchronized(imageLock) {
            if (previewed || previewListeners.isEmpty()) {
                heldImage?.close()
                heldImage = image
            } else {
                // Skip
                image.close()
            }
        }
    }

    /**
     * Hands a captured frame to the preview listeners, at most one per
     * [PREVIEW_FRAME_INTERVAL_MS]. The buffer is already in GPU memory, so the bitmap
     * references it directly and Compose scales it when drawing — no pixels are copied
     * and nothing is uploaded.
     *
     * Returns whether the frame ends a preview interval, which is what makes the
     * previously previewed frame safe to release.
     */
    private fun previewFrame(image: Image): Boolean {
        if (previewListeners.isEmpty()) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPreviewFrameAtMs < PREVIEW_FRAME_INTERVAL_MS) {
            return false
        }
        lastPreviewFrameAtMs = now

        val preview = image.hardwareBuffer?.let { buffer ->
            buffer.use { buffer ->
                Bitmap.wrapHardwareBuffer(buffer, null)
            }
        }
        if (preview == null) {
            return true
        }

        dispatchPreviewFrame(preview)
        return true
    }

    private fun dispatchPreviewFrame(bitmap: Bitmap?) {
        if (previewListeners.isEmpty()) {
            return
        }
        previewListeners.forEach { listener ->
            try {
                listener.onPreviewFrame(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "Preview listener failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        llmConfigurationRepository = LlmConfigurationRepository(this)

        Shizuku.addBinderReceivedListenerSticky(this)
        Shizuku.addRequestPermissionResultListener(this)

        val dm = getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        dm.getDisplay(0).getMetrics(displayMetrics)
        screenshotSize = Size(
            displayMetrics.widthPixels,
            displayMetrics.heightPixels
        ).scaleToMaxDimension(1024)
        println()
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderReceivedListener(this)
        Shizuku.removeRequestPermissionResultListener(this)

        currentJob?.cancel()
        imageReader?.close()
        imageReader = null
        synchronized(imageLock) {
            heldImage?.close()
            heldImage = null
        }
        frameThread.quitSafely()
        previewListeners.clear()
        transcriptListeners.clear()
        inferenceStatusListeners.clear()
        jobStateListeners.clear()
        errorListeners.clear()
        stopJobForeground()
        try {
            userService?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy user service", e)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onBinderReceived() {
        tryBindUserService()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        tryBindUserService()
    }

    fun tryBindUserService() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind user service", e)
        }
    }

    suspend fun startDisplay(): Boolean {
        for (i in 0 until 10) {
            val service = userService
            if (service == null) {
                Log.i(TAG, "Waiting for user service to connect... ($i)")
                delay(1000.milliseconds)
                continue
            }
            val reader = ImageReader.newInstance(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                PixelFormat.RGBA_8888,
                4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
            )
            reader.setOnImageAvailableListener(imageListener, frameHandler)
            if (service.startDisplay(
                    displayMetrics.widthPixels,
                    displayMetrics.heightPixels,
                    displayMetrics.densityDpi,
                    reader.surface
            )) {
                imageReader = reader
                return true
            } else {
                reader.close()
                return false
            }
        }
        Log.e(TAG, "User service did not connect in time")
        return false
    }

    fun stopDisplay(): Boolean {
        val stopped = userService?.stopDisplay() ?: false
        imageReader?.close()
        imageReader = null
        synchronized(imageLock) {
            heldImage?.close()
            heldImage = null
        }
        dispatchPreviewFrame(null)
        return stopped
    }

    fun stopAgent() {
        Log.i(TAG, "stopAgent requested")
        currentJob?.cancel()
    }

    fun isAgentRunning(): Boolean = currentJob?.isActive == true

    fun startActivityRemote(intent: Intent) {
        userService?.startActivity(intent, false)
    }

    fun getBitmap(): Bitmap? = synchronized(imageLock) {
        val image = heldImage ?: return@synchronized null
        val plane = image.planes[0]
        val width = image.width
        val rowPadding = plane.rowStride - plane.pixelStride * width

        val padded = createBitmap(width + rowPadding / plane.pixelStride, image.height)
        padded.copyPixelsFromBuffer(plane.buffer)

        val cropRect = image.cropRect
        Bitmap.createBitmap(
            padded,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height()
        )
    }

    fun getFocusedAppName(): String? {
        val focusedPackageName = userService?.focusedPackageName ?: return null

        return try {
            val appInfo = packageManager.getApplicationInfo(focusedPackageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: $focusedPackageName", e)
            focusedPackageName
        }
    }


    fun mapToScreenCoordinates(x: Float, y: Float): Pair<Float, Float>? {
        if (x < 0 || x > screenshotSize.width || y < 0 || y > screenshotSize.height) {
            return null
        }
        val screenX = x * (displayMetrics.widthPixels.toFloat() / screenshotSize.width.toFloat())
        val screenY = y * (displayMetrics.heightPixels.toFloat() / screenshotSize.height.toFloat())
        Log.i(
            TAG,
            "Mapping logical coordinates ($x, $y) to screen coordinates ($screenX, $screenY)"
        )
        return Pair(screenX, screenY)
    }

    fun mapXToScreen(x: Float): Float? {
        if (x < 0 || x > screenshotSize.width) {
            return null
        }
        return (x * (displayMetrics.widthPixels.toFloat() / screenshotSize.width.toFloat())).coerceIn(
            0f,
            displayMetrics.widthPixels.toFloat() - 1.0f
        )
    }

    fun mapYToScreen(y: Float): Float? {
        if (y < 0 || y > screenshotSize.height) {
            return null
        }
        return y * (displayMetrics.heightPixels.toFloat() / screenshotSize.height.toFloat()).coerceIn(
            0f,
            displayMetrics.heightPixels.toFloat() - 1.0f
        )
    }

    fun injectSwipeEvent(
        x1: Float, y1: Float, x2: Float, y2: Float, duration: Long
    ) = userService?.injectSwipeEvent(x1, y1, x2, y2, duration) ?: false

    fun injectTapEvent(x: Float, y: Float) = userService?.injectTapEvent(x, y) ?: false
    fun injectKeyPressEvent(keyCode: Int) = userService?.injectKeyPressEvent(keyCode) ?: false

    fun getScreenshotImageUrl(): String? {
        val fullBitmap = getBitmap() ?: return null
        val bitmap = fullBitmap.scale(screenshotSize.width, screenshotSize.height)

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        val bytes = outputStream.toByteArray()
        val bytesBase64 = Base64.getEncoder().encodeToString(bytes)

        return "data:image/jpeg;base64,$bytesBase64"
    }

    fun interface PreviewListener {
        fun onPreviewFrame(bitmap: Bitmap?)
    }

    fun interface TranscriptListener {
        fun onTranscriptChanged(messages: List<ChatMessage>)
    }

    fun interface InferenceStatusListener {
        fun onInferenceStatus(agent: String, status: InferenceStatus)
    }

    fun interface JobStateListener {
        fun onJobStateChanged(running: Boolean)
    }

    fun interface ErrorListener {
        fun onError(message: String)
    }

    private val previewListeners = CopyOnWriteArraySet<PreviewListener>()

    // The conversation lives here, not in the screen, so closing the activity mid-run and
    // reopening it resumes the same transcript.
    private val transcript = mutableListOf<ChatMessage>()
    private val transcriptListeners = CopyOnWriteArraySet<TranscriptListener>()

    // What each agent is doing right now, kept here for the same reason: reopening the
    // screen mid-run should show that an inference is in flight, not an idle chat.
    private val inferenceStatuses = mutableMapOf<String, InferenceStatus>()
    private val inferenceStatusListeners = CopyOnWriteArraySet<InferenceStatusListener>()
    private val jobStateListeners = CopyOnWriteArraySet<JobStateListener>()
    private val errorListeners = CopyOnWriteArraySet<ErrorListener>()

    suspend fun mainJob(
        userPrompt: String,
        configuration: ActiveLlmConfiguration,
        client: com.aallam.openai.client.OpenAI,
    ) {
        updateTranscript {
            it.add(
                ChatMessage("You", userPrompt, System.currentTimeMillis(), fromUser = true)
            )
        }

        val plannerAgent = PlannerAgent(
            userPrompt, configuration, client,
            onStatus = { notifyInferenceStatusListeners("Planner", it) },
            onStream = { notifyAgentMessage(AgentMessage("Planner", it)) },
        )
        val executorAgent = ExecutorAgent(
            screenshotSize.width,
            screenshotSize.height,
            configuration,
            client,
            onStatus = { notifyInferenceStatusListeners("Executor", it) },
            onStream = { notifyAgentMessage(AgentMessage("Executor", it)) },
        )

        for (step in 0..<100) {
            Log.i(TAG, "Agent step $step")

            val currentScreenshot = getScreenshotImageUrl()
            val currentApp = getFocusedAppName()

            val planResult = plannerAgent.plan(currentScreenshot, currentApp)
            if (planResult == null) {
                Log.e(TAG, "Failed to plan")
                break
            }
            notifyAgentMessage(AgentMessage("Planner", planResult))

            // Parse action
            val agentResponse = executorAgent.execute(
                planResult, currentScreenshot, currentApp
            )
            if (agentResponse == null) {
                Log.e(TAG, "Failed to parse agent response, retry")
                continue
            }

            Log.i(TAG, "Action: ${agentResponse.action}")

            val executorText = buildString {
                if (agentResponse.think.isNotBlank()) {
                    appendLine("Think: ${agentResponse.think}")
                }
                append("Action: ${agentResponse.action}")
            }
            notifyAgentMessage(AgentMessage("Executor", executorText))

            // Check if we're done
            if (agentResponse.action is Action.Finish) {
                Log.i(TAG, "Agent finished")
                break
            }

            val actionResult = agentResponse.action.execute(this)
            Log.i(TAG, "Action result: $actionResult")
            if (actionResult.stop) {
                Log.i(TAG, "actionResult.stop == true, stopping")
                break
            }
            plannerAgent.addActionResult(actionResult.prompt)
            delay(3000.milliseconds) // Wait for the action to take effect and screen to update
        }

        Log.i(TAG, "Agent loop completed")
        delay((1000 * 10).milliseconds)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            Log.w(TAG, "Intent is null, not starting")
            return START_NOT_STICKY
        }

        if (currentJob != null) {
            Log.w(TAG, "A job is running, not starting")
            return START_NOT_STICKY
        }

        val agentPrompt = intent.getStringExtra(EXTRA_AGENT_PROMPT)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (agentPrompt == null) {
            Log.w(TAG, "Agent prompt is null or blank, not starting")
            return START_NOT_STICKY
        }

        currentJob = lifecycleScope.launch(Dispatchers.IO) {
            supervisorScope {
                try {
                    val configuration = llmConfigurationRepository.activeConfiguration()
                    if (configuration == null) {
                        Log.e(TAG, "No LLM provider and model are selected")
                        return@supervisorScope
                    }
                    if (!startDisplay()) {
                        throw IllegalStateException("Failed to start display")
                    }
                    startJobForeground()
                    createLlmClient(configuration).use { client ->
                        mainJob(agentPrompt, configuration, client)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "mainJob failed", e)
                    notifyError(e.message ?: "Agent job failed")
                } finally {
                    currentJob = null
                    notifyJobState(false)
                    stopJobForeground()
                    if (!stopDisplay()) {
                        Log.e(TAG, "Failed to stop display")
                    }
                }
            }
        }
        notifyJobState(true)

        return START_NOT_STICKY
    }

    private fun startJobForeground() {
        ensureForegroundNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun stopJobForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureForegroundNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(FOREGROUND_CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            FOREGROUND_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = FOREGROUND_CHANNEL_DESCRIPTION
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NekoAssistant is running")
            .setContentText("Agent task is in progress")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun postErrorNotification(message: String) {
        ensureForegroundNotificationChannel()
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NekoAssistant error")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    fun addPreviewListener(listener: PreviewListener) {
        previewListeners.add(listener)
    }

    fun removePreviewListener(listener: PreviewListener) {
        previewListeners.remove(listener)
    }

    fun addTranscriptListener(listener: TranscriptListener) {
        transcriptListeners.add(listener)
    }

    fun removeTranscriptListener(listener: TranscriptListener) {
        transcriptListeners.remove(listener)
    }

    fun addInferenceStatusListener(listener: InferenceStatusListener) {
        inferenceStatusListeners.add(listener)
    }

    fun removeInferenceStatusListener(listener: InferenceStatusListener) {
        inferenceStatusListeners.remove(listener)
    }

    fun addJobStateListener(listener: JobStateListener) {
        jobStateListeners.add(listener)
    }

    fun removeJobStateListener(listener: JobStateListener) {
        jobStateListeners.remove(listener)
    }

    fun addErrorListener(listener: ErrorListener) {
        errorListeners.add(listener)
    }

    fun removeErrorListener(listener: ErrorListener) {
        errorListeners.remove(listener)
    }

    private fun notifyJobState(running: Boolean) {
        jobStateListeners.forEach { listener ->
            try {
                listener.onJobStateChanged(running)
            } catch (e: Exception) {
                Log.w(TAG, "Job state listener failed", e)
            }
        }
    }

    private fun notifyError(message: String) {
        Log.e(TAG, "Agent error: $message")
        val foreground = ProcessLifecycleOwner.get()
            .lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground && errorListeners.isNotEmpty()) {
            errorListeners.forEach { listener ->
                try {
                    listener.onError(message)
                } catch (e: Exception) {
                    Log.w(TAG, "Error listener failed", e)
                }
            }
        } else {
            postErrorNotification(message)
        }
    }

    fun getInferenceStatuses(): Map<String, InferenceStatus> =
        synchronized(inferenceStatuses) { inferenceStatuses.toMap() }

    private fun notifyInferenceStatusListeners(agent: String, status: InferenceStatus) {
        synchronized(inferenceStatuses) { inferenceStatuses[agent] = status }

        inferenceStatusListeners.forEach { listener ->
            try {
                listener.onInferenceStatus(agent, status)
            } catch (e: Exception) {
                Log.w(TAG, "Inference status listener failed", e)
            }
        }
    }

    fun getTranscript(): List<ChatMessage> = synchronized(transcript) { transcript.toList() }

    /**
     * Adds a turn to the transcript. A turn arrives as many streaming updates followed by
     * the final text, all under the same sender, so a message from whoever spoke last
     * replaces their turn; anyone else starts a new one.
     */
    private fun notifyAgentMessage(message: AgentMessage) = updateTranscript { messages ->
        val last = messages.lastOrNull()
        if (last != null && !last.fromUser && last.sender == message.agent) {
            // Same turn still streaming: keep when it started, not when it last grew.
            messages[messages.lastIndex] = ChatMessage(
                message.agent, message.content, last.timestamp
            )
        } else {
            messages.add(
                ChatMessage(message.agent, message.content, System.currentTimeMillis())
            )
        }
    }

    fun clearTranscript() = updateTranscript { it.clear() }

    private fun updateTranscript(block: (MutableList<ChatMessage>) -> Unit) {
        val updated = synchronized(transcript) {
            block(transcript)
            transcript.toList()
        }

        transcriptListeners.forEach { listener ->
            try {
                listener.onTranscriptChanged(updated)
            } catch (e: Exception) {
                Log.w(TAG, "Transcript listener failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "AgentService"
        private const val PREVIEW_FRAME_INTERVAL_MS = 33L
        const val EXTRA_AGENT_PROMPT = "moe.reimu.nekoassistant.extra.AGENT_PROMPT"
        private const val FOREGROUND_CHANNEL_ID = "agent_job"
        private const val FOREGROUND_CHANNEL_NAME = "Agent job"
        private const val FOREGROUND_CHANNEL_DESCRIPTION = "Runs agent automation tasks"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val ERROR_NOTIFICATION_ID = 1002
    }
}
