package com.roboff.lan

import com.roboff.lan.protocol.LocalClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger

/** Values are present only after the vacuum answered the corresponding read-only LAN query. */
data class RobotProbe(
    val features: RobotFeatures?,
    val combinedCleaningMode: Boolean,
    val settings: RobotSettings,
)

data class RobotFeatures(
    val childLock: Boolean?,
    val carpet: Boolean?,
    val mopPath: Boolean?,
    val shakeMop: Boolean?,
    val carefulSlowMop: Boolean?,
) {
    companion object {
        /** These bit positions match python-roborock's DeviceFeatures.from_feature_flags. */
        fun parse(response: Any?): RobotFeatures? {
            val value = response.firstObject() ?: return null
            val raw = value.opt("new_feature_info")?.takeUnless { it === JSONObject.NULL }
            val bits = try { raw?.toString()?.let(::BigInteger) } catch (_: NumberFormatException) { null }
            if (bits == null && !value.has("feature_info")) return null
            fun bit(mask: Long): Boolean? = bits?.and(BigInteger.valueOf(mask))?.signum()?.let { it != 0 }
            fun upper(index: Int): Boolean? = bits?.testBit(32 + index)
            return RobotFeatures(
                childLock = bit(256),
                carpet = bit(512),
                mopPath = bit(2048),
                shakeMop = bit(262144),
                carefulSlowMop = upper(9),
            )
        }
    }
}

data class DndSetting(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean,
) {
    fun scheduleParams() = JSONArray()
        .put(startHour).put(startMinute).put(endHour).put(endMinute)

    companion object {
        fun parse(response: Any?): DndSetting? {
            val value = response.firstObject() ?: return null
            val startHour = value.intField("start_hour", "startHour") ?: return null
            val startMinute = value.intField("start_minute", "startMinute") ?: return null
            val endHour = value.intField("end_hour", "endHour") ?: return null
            val endMinute = value.intField("end_minute", "endMinute") ?: return null
            val enabled = value.intField("enabled") ?: return null
            if (startHour !in 0..23 || endHour !in 0..23 || startMinute !in 0..59 || endMinute !in 0..59 || enabled !in 0..1) return null
            return DndSetting(startHour, startMinute, endHour, endMinute, enabled == 1)
        }
    }
}

data class CarpetSetting(
    val enabled: Boolean,
    val stallTime: Int,
    val currentLow: Int,
    val currentHigh: Int,
    val currentIntegral: Int,
) {
    /** Preserve the vacuum's own calibration values when toggling carpet boost. */
    fun params(enable: Boolean) = JSONArray().put(JSONObject()
        .put("enable", if (enable) 1 else 0)
        .put("stall_time", stallTime)
        .put("current_low", currentLow)
        .put("current_high", currentHigh)
        .put("current_integral", currentIntegral))

    companion object {
        fun parse(response: Any?): CarpetSetting? {
            val value = response.firstObject() ?: return null
            val enable = value.intField("enable") ?: return null
            val stall = value.intField("stall_time") ?: return null
            val low = value.intField("current_low") ?: return null
            val high = value.intField("current_high") ?: return null
            val integral = value.intField("current_integral") ?: return null
            if (enable !in 0..1 || stall < 0 || low < 0 || high < 0 || integral < 0) return null
            return CarpetSetting(enable == 1, stall, low, high, integral)
        }
    }
}

data class RobotSettings(
    val dnd: DndSetting? = null,
    val childLock: Boolean? = null,
    val volume: Int? = null,
    val carpet: CarpetSetting? = null,
) {
    fun hasAny() = dnd != null || childLock != null || volume != null || carpet != null
}

/** No setter is used during discovery. Unknown or malformed replies leave a feature hidden. */
object RobotCapabilityProbe {
    fun read(client: LocalClient): RobotProbe {
        var transportFailed = false
        fun query(method: String): Any? {
            if (transportFailed) return null
            return try {
                client.request(method)
            } catch (_: LocalClient.RpcException) {
                null // A rejected/unknown getter is an unsupported feature, not a connection failure.
            } catch (_: IOException) {
                transportFailed = true
                null
            }
        }

        val features = RobotFeatures.parse(query("app_get_init_status"))
        val motorMode = query("get_clean_motor_mode").let { result ->
            when (result) {
                is JSONObject -> result.length() > 0
                is JSONArray -> result.length() > 0 && result.opt(0) !is String
                else -> result is Number
            }
        }
        val dnd = DndSetting.parse(query("get_dnd_timer"))
        val child = query("get_child_lock_status").firstObject()
            ?.intField("lock_status", "lockStatus")?.takeIf { it in 0..1 }?.let { it == 1 }
        val volume = query("get_sound_volume").firstValue()
            ?.let { (it as? Number)?.toInt() }?.takeIf { it in 0..100 }
        val carpet = CarpetSetting.parse(query("get_carpet_mode"))
        return RobotProbe(features, motorMode, RobotSettings(dnd, child, volume, carpet))
    }
}

private fun Any?.firstValue(): Any? = if (this is JSONArray) this.opt(0) else this

private fun Any?.firstObject(): JSONObject? = firstValue() as? JSONObject

private fun JSONObject.intField(vararg keys: String): Int? {
    for (key in keys) {
        val value = opt(key)
        if (value is Number) return value.toInt()
    }
    return null
}
