package moe.reimu.nekoassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.graphics.scale
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import moe.reimu.nekoassistant.ai.Action
import moe.reimu.nekoassistant.ai.AgentChatMessage
import moe.reimu.nekoassistant.ai.ExecutorAgent
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
            updatePreviewFrameBridgeRegistration()
        }

        override fun onServiceDisconnected(p0: ComponentName) {
            Log.i(TAG, "UserService onServiceDisconnected")
            userService = null
            updatePreviewFrameBridgeRegistration()
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
        unregisterPreviewFrameBridge()
        previewListeners.clear()
        conversationListeners.clear()
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
            if (service != null) {
                return service.startDisplay(
                    displayMetrics.widthPixels,
                    displayMetrics.heightPixels,
                    displayMetrics.densityDpi
                )
            }
            Log.i(TAG, "Waiting for user service to connect... ($i)")
            delay(1000)
        }
        Log.e(TAG, "User service did not connect in time")
        return false
    }

    fun stopDisplay() = userService?.stopDisplay() ?: false

    fun startActivityRemote(intent: Intent) {
        userService?.startActivity(intent, false)
    }

    fun getBitmap(): Bitmap? {
        return userService?.lastBitmap
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

    fun interface ConversationListener {
        fun onConversationUpdate(message: AgentChatMessage)
    }

    private val previewListeners = CopyOnWriteArraySet<PreviewListener>()
    private val conversationListeners = CopyOnWriteArraySet<ConversationListener>()
    private var previewListenerRegistered = false

    private val previewFrameBridge = object : IPreviewFrameListener.Stub() {
        override fun onPreviewFrame(bitmap: Bitmap?) {
            previewListeners.forEach { listener ->
                try {
                    listener.onPreviewFrame(bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "Preview listener failed", e)
                }
            }
        }
    }

    suspend fun mainJob(
        userPrompt: String,
        configuration: ActiveLlmConfiguration,
        client: com.aallam.openai.client.OpenAI,
    ) {
        val plannerAgent = PlannerAgent(userPrompt, configuration, client)
        val executorAgent = ExecutorAgent(
            screenshotSize.width,
            screenshotSize.height,
            configuration,
            client,
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

            // Parse action
            val agentResponse = executorAgent.execute(
                planResult, currentScreenshot, currentApp
            )
            if (agentResponse == null) {
                Log.e(TAG, "Failed to parse agent response, retry")
                continue
            }

            Log.i(TAG, "Action: ${agentResponse.action}")

            // Notify conversation listeners with updated history
            notifyConversationListeners(AgentChatMessage(planResult, agentResponse))

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
                } catch (e: Exception) {
                    Log.e(TAG, "mainJob failed", e)
                } finally {
                    currentJob = null
                    stopJobForeground()
                    if (!stopDisplay()) {
                        Log.e(TAG, "Failed to stop display")
                    }
                }
            }
        }

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

    fun addPreviewListener(listener: PreviewListener) {
        if (previewListeners.add(listener)) {
            updatePreviewFrameBridgeRegistration()
        }
    }

    fun removePreviewListener(listener: PreviewListener) {
        if (previewListeners.remove(listener)) {
            updatePreviewFrameBridgeRegistration()
        }
    }

    fun addConversationListener(listener: ConversationListener) {
        conversationListeners.add(listener)
    }

    fun removeConversationListener(listener: ConversationListener) {
        conversationListeners.remove(listener)
    }

    private fun notifyConversationListeners(message: AgentChatMessage) {
        conversationListeners.forEach { listener ->
            try {
                listener.onConversationUpdate(message)
            } catch (e: Exception) {
                Log.w(TAG, "Conversation listener failed", e)
            }
        }
    }

    @Synchronized
    private fun updatePreviewFrameBridgeRegistration() {
        val shouldRegister = previewListeners.isNotEmpty() && userService != null
        when {
            shouldRegister && !previewListenerRegistered -> registerPreviewFrameBridge()
            !shouldRegister && previewListenerRegistered -> unregisterPreviewFrameBridge()
        }
    }

    private fun registerPreviewFrameBridge() {
        val service = userService ?: return
        if (previewListenerRegistered) {
            return
        }
        try {
            service.registerPreviewFrameListener(previewFrameBridge)
            previewListenerRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register preview frame listener", e)
        }
    }

    private fun unregisterPreviewFrameBridge() {
        val service = userService
        if (!previewListenerRegistered || service == null) {
            previewListenerRegistered = false
            return
        }
        try {
            service.unregisterPreviewFrameListener(previewFrameBridge)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister preview frame listener", e)
        } finally {
            previewListenerRegistered = false
        }
    }

    companion object {
        private const val TAG = "AgentService"
        const val EXTRA_AGENT_PROMPT = "moe.reimu.nekoassistant.extra.AGENT_PROMPT"
        private const val FOREGROUND_CHANNEL_ID = "agent_job"
        private const val FOREGROUND_CHANNEL_NAME = "Agent job"
        private const val FOREGROUND_CHANNEL_DESCRIPTION = "Runs agent automation tasks"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
    }
}
