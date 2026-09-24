package moe.reimu.nekoassistant.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.reimu.nekoassistant.AgentService
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@Serializable
sealed class Action {
    open suspend fun execute(service: AgentService): ActionResult = ActionResult.success()

    @Serializable
    @SerialName("SearchApp")
    data class SearchApp(val query: String) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            if (query.isBlank()) {
                return ActionResult.retry("SearchApp 的 query 不能为空")
            }
            val packageManager = service.packageManager
            val installedApps =
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val matches = installedApps.mapNotNull { appInfo ->
                val name = packageManager.getApplicationLabel(appInfo).toString()
                val pkg = appInfo.packageName
                if (name.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)) {
                    "$name ($pkg)"
                } else {
                    null
                }
            }
            return if (matches.isEmpty()) {
                ActionResult.retry("没有找到匹配 \"$query\" 的应用，请尝试其他关键词")
            } else {
                ActionResult.success("匹配的应用：\n" + matches.joinToString("\n"))
            }
        }
    }

    @Serializable
    @SerialName("Launch")
    data class Launch(val packageName: String) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            val launchIntent = service.packageManager.getLaunchIntentForPackage(packageName)
            return if (launchIntent != null) {
                Log.i(TAG, "Launching app: $packageName")
                service.startActivityRemote(launchIntent)
                delay(5000)
                ActionResult.success()
            } else {
                Log.e(TAG, "No launch intent found for package: $packageName")
                ActionResult.retry("找不到包名 $packageName 对应的应用，请先调用 SearchApp 搜索获取正确包名")
            }
        }

        companion object {
            private const val TAG = "Action.Launch"
        }
    }

    @Serializable
    @SerialName("Tap")
    data class Tap(val x: Float, val y: Float, val message: String? = null) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            val x = service.mapXToScreen(x)
                ?: return ActionResult.retry("X坐标不在范围内，最大值为${service.screenshotSize.width - 1}，请调整")
            val y = service.mapYToScreen(y)
                ?: return ActionResult.retry("Y坐标不在范围内，最大值为${service.screenshotSize.height - 1}，请调整")

            if (!service.injectTapEvent(x, y)) {
                Log.e(TAG, "Failed to inject tap event at ($x, $y)")
                return ActionResult.fail()
            }

            return ActionResult.success()
        }

        companion object {
            private const val TAG = "Action.Tap"
        }
    }

    @Serializable
    @SerialName("Type")
    data class Type(val text: String) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            val cm = service.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText(null, text))

            delay(Random.nextLong(180, 220))

            service.injectKeyPressEvent(KeyEvent.KEYCODE_PASTE)

            return ActionResult.success(
                "已尝试输入，请验证输入是否成功，如果输入不成功，可能是输入框没有聚焦，请先使用 Tap 点击输入框，不要继续尝试 Type"
            )
        }
    }

    @Serializable
    @SerialName("Swipe")
    data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float) :
        Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            val (startX, startY) = service.mapToScreenCoordinates(startX, startY)
                ?: return ActionResult.retry("起始坐标不在范围内，请重试")
            val (endX, endY) = service.mapToScreenCoordinates(endX, endY)
                ?: return ActionResult.retry("结束坐标不在范围内，请重试")
            val distance = (endX - startX).pow(2) + (endY - startY).pow(2)
            val durationMs = (distance / 1000.0f).toLong().coerceIn(1000, 2000)
            return if (service.injectSwipeEvent(startX, startY, endX, endY, durationMs)) {
                ActionResult.success()
            } else {
                ActionResult.fail()
            }
        }

    }

    @Serializable
    @SerialName("LongPress")
    data class LongPress(val x: Int, val y: Int) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            val screenX = service.mapXToScreen(x.toFloat())
                ?: return ActionResult.retry("X坐标不在范围内，最大值为${service.screenshotSize.width - 1}，请调整")
            val screenY = service.mapYToScreen(y.toFloat())
                ?: return ActionResult.retry("Y坐标不在范围内，最大值为${service.screenshotSize.height - 1}，请调整")

            return if (service.injectSwipeEvent(
                    screenX, screenY,
                    screenX, screenY,
                    Random.nextLong(900, 1000),
            )) {
                ActionResult.success()
            } else {
                ActionResult.fail()
            }
        }
    }

    @Serializable
    @SerialName("DoubleTap")
    data class DoubleTap(val x: Int, val y: Int) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            val screenX = service.mapXToScreen(x.toFloat())
                ?: return ActionResult.retry("X坐标不在范围内，最大值为${service.screenshotSize.width - 1}，请调整")
            val screenY = service.mapYToScreen(y.toFloat())
                ?: return ActionResult.retry("Y坐标不在范围内，最大值为${service.screenshotSize.height - 1}，请调整")

            if (!service.injectTapEvent(screenX, screenY)) {
                return ActionResult.fail()
            }

            delay(Random.nextLong(100, 160).milliseconds)

            if (!service.injectTapEvent(screenX, screenY)) {
                return ActionResult.fail()
            }
            return ActionResult.success()
        }
    }

    @Serializable
    @SerialName("TakeOver")
    data class TakeOver(val message: String) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            service.awaitUserTakeOver(message)
            return ActionResult.success("用户已接管完成，请先检查当前界面，再决定下一步操作")
        }
    }

    @Serializable
    @SerialName("Back")
    data object Back : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            if (service.injectKeyPressEvent(KeyEvent.KEYCODE_BACK)) {
                return ActionResult.success()
            }
            return ActionResult.fail()
        }
    }

    @Serializable
    @SerialName("Wait")
    data class Wait(val seconds: Int) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            delay((seconds * 1000L).milliseconds)
            return ActionResult.success()
        }
    }

    @Serializable
    @SerialName("Finish")
    data object Finish : Action()
}