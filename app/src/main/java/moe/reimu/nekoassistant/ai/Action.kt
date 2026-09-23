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
import kotlin.random.nextLong

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
    @SerialName("Type_Name")
    data class TypeName(val text: String) : Action()

    @Serializable
    @SerialName("Interact")
    data object Interact : Action()

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
    @SerialName("Note")
    data class Note(val message: String) : Action()

    @Serializable
    @SerialName("Call_API")
    data class Summarize(val instruction: String) : Action()

    @Serializable
    @SerialName("Long_Press")
    data class LongPress(val x: Int, val y: Int) : Action() {
        // ponytail: stub — no real implementation yet
        override suspend fun execute(service: AgentService): ActionResult =
            ActionResult.fail("LongPress 尚未实现")
    }

    @Serializable
    @SerialName("Double_Tap")
    data class DoubleTap(val x: Int, val y: Int) : Action() {
        // ponytail: stub — no real implementation yet
        override suspend fun execute(service: AgentService): ActionResult =
            ActionResult.fail("DoubleTap 尚未实现")
    }

    @Serializable
    @SerialName("Take_over")
    data class TakeOver(val message: String) : Action() {
        // ponytail: stub — no real implementation yet
        override suspend fun execute(service: AgentService): ActionResult =
            ActionResult.fail("TakeOver 尚未实现")
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
    @SerialName("Home")
    data object Home : Action() {
        // ponytail: stub — no real implementation yet
        override suspend fun execute(service: AgentService): ActionResult =
            ActionResult.fail("Home 尚未实现")
    }

    @Serializable
    @SerialName("Wait")
    data class Wait(val seconds: Int) : Action() {
        override suspend fun execute(service: AgentService): ActionResult {
            delay(seconds * 1000L)
            return ActionResult.success()
        }
    }

    @Serializable
    @SerialName("Finish")
    data object Finish : Action()
}