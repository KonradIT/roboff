package com.roboff.lan
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
 /** A single get_status snapshot. Missing data never inherits a previous value.  */
class RobotState private constructor(val raw: JSONObject) {
    val battery: Int
    val state: Int
    val inCleaning: Int
    val error: Int
    val fan: Int
    val water: Int
    val mop: Int
    val cleanSeconds: Int
     /** The vacuum's reported estimate; -1 means no usable estimate was supplied.  */
    val cleanPercent: Int
    val cleanArea: Long
    val valid: Boolean

    init {
        val reportedBattery = integer(raw, "battery")
        battery = if (reportedBattery <= 100) reportedBattery else -1
        state = integer(raw, "state")
        inCleaning = integer(raw, "in_cleaning")
        error = integer(raw, "error_code")
        fan = integer(raw, "fan_power")
        water = integer(raw, "water_box_mode")
        mop = integer(raw, "mop_mode")
        cleanSeconds = integer(raw, "clean_time")
        val reportedPercent = integer(raw, "clean_percent")
        cleanPercent = if (reportedPercent <= 100) reportedPercent else -1
        cleanArea = nonnegativeLong(raw, "clean_area")
        valid = battery >= 0 || state >= 0 || error >= 0 || fan >= 0 || water >= 0 ||
            mop >= 0 || cleanSeconds >= 0 || cleanArea >= 0
    }
     /** States that actually perform cleaning, excluding navigation, mapping, and dock work.  */
     fun isCleaning(): Boolean {
        return (state == 5) || (state == 11) || (state == 17) || (state == 18
        ) || (state >= 6301 && state <= 6309)
    }
    fun isPaused(): Boolean = state == 10

    fun isPausedSegmentClean(): Boolean = isPaused() && inCleaning == 3
     fun stateLabel(): kotlin.String? {
        when(state){-1 -> return "Unavailable"
            0 -> return "Unknown"
            1 -> return "Starting"
            2 -> return "Charger disconnected"
            3 -> return "Idle"
            4 -> return "Remote control"
            5 -> return "Cleaning"
            6 -> return "Returning to dock"
            7 -> return "Manual mode"
            8 -> return "Charging"
            9 -> return "Charging problem"
            10 -> return "Paused"
            11 -> return "Spot cleaning"
            12 -> return "Error"
            13 -> return "Shutting down"
            14 -> return "Updating"
            15 -> return "Docking"
            16 -> return "Going to target"
            17 -> return "Zone cleaning"
            18 -> return "Room cleaning"
            22 -> return "Emptying dustbin"
            23, 25 -> return "Washing mop"
            26 -> return "Going to wash mop"
            28 -> return "In call"
            29 -> return "Mapping"
            32 -> return "Patrolling"
            33 -> return "Attaching mop"
            34 -> return "Detaching mop"
            100 -> return "Fully charged"
            101 -> return "Device offline"
            103 -> return "Locked"
            202 -> return "Stopping drying"
            6301 -> return "Mopping"
            6302 -> return "Vacuuming before mopping"
            6303 -> return "Mopping after vacuuming"
            6304 -> return "Room mopping"
            6305 -> return "Room vacuuming before mopping"
            6306 -> return "Room mopping after vacuuming"
            6307 -> return "Zone mopping"
            6308 -> return "Zone vacuuming before mopping"
            6309 -> return "Zone mopping after vacuuming"
            6310 -> return "Returning to wash mop"
            else -> return "Unknown state (" + state + ")"}
    }
     fun errorLabel(): kotlin.String? {
        when(error){-1 -> return "Unavailable"
            0 -> return "No errors"
            1 -> return "LiDAR blocked"
            2 -> return "Bumper stuck"
            3 -> return "Wheels suspended"
            4 -> return "Cliff sensor error"
            5 -> return "Main brush jammed"
            6 -> return "Side brush jammed"
            7 -> return "Wheels jammed"
            8 -> return "Robot trapped"
            9 -> return "Dustbin missing"
            10 -> return "Filter wet or blocked"
            11 -> return "Strong magnetic field"
            12 -> return "Low battery"
            13 -> return "Charging error"
            14 -> return "Battery error"
            15 -> return "Wall sensor dirty"
            16 -> return "Robot tilted"
            17 -> return "Side brush error"
            18 -> return "Fan error"
            19 -> return "Dock has no power"
            20 -> return "Optical sensor dirty"
            21 -> return "Vertical bumper pressed"
            22 -> return "Dock locator error"
            23 -> return "Could not return to dock"
            24 -> return "No-go zone detected"
            25 -> return "Camera error"
            26 -> return "Wall sensor error"
            27 -> return "VibraRise jammed"
            28 -> return "Robot on carpet"
            29 -> return "Filter blocked"
            30 -> return "Invisible wall detected"
            31 -> return "Cannot cross carpet"
            32 -> return "Internal error"
            34 -> return "Clean auto-empty dock"
            35 -> return "Auto-empty dock voltage error"
            36, 45 -> return "Wash roller jammed"
            37 -> return "Wash roller not lowered"
            38 -> return "Check clean water tank"
            39 -> return "Check dirty water tank"
            40 -> return "Reinstall water filter"
            41 -> return "Clean water tank empty"
            42 -> return "Check water filter installation"
            43 -> return "Positioning button error"
            44 -> return "Clean dock water filter"
            48 -> return "Water supply error"
            49 -> return "Water drainage error"
            51 -> return "Temperature protection"
            52 -> return "Mop cleaning tray error"
            53 -> return "Mop cleaning tray full"
            54 -> return "Water carriage detached"
            55 -> return "Check mop cleaning tray"
            56 -> return "Audio error"
            else -> return "Error " + error}
    }
    fun areaLabel(): String {
        return if (cleanArea < 0) "—" else String.format(Locale.getDefault(), "%.1f m²", cleanArea / 1_000_000.0)
    }
    fun durationLabel(): String {
        if (cleanSeconds < 0)return "—"
        if (cleanSeconds == 0)return "0 min"
        if (cleanSeconds < 60)return "<1 min"
         var minutes: Long = (cleanSeconds / 60).toLong()
        if (minutes < 60)return minutes.toString() + " min"
        return (minutes / 60).toString() + " h " + (minutes % 60) + " min"
    }
     companion object {
         /** V1 get_status returns either an object or a one-element array of objects.  */
        fun from(result: Any?): RobotState {
             var value: Any? = result
            if (value is JSONArray) {
                val array = value
                value = if (array.length() == 1) array.opt(0) else null
            }
            if (value is JSONObject) {
                try  {
                    return RobotState(JSONObject(value.toString()))
                }catch (ignored:JSONException) {
                // Treat an invalid result as unavailable instead of showing stale data.
                }
            }
            return RobotState(JSONObject())
        }
        private fun integer(objectValue: JSONObject, field: String): Int {
            val value = nonnegativeLong(objectValue, field)
            return if (value <= Int.MAX_VALUE) value.toInt() else -1
        }
        private fun nonnegativeLong(objectValue: JSONObject, field: String): Long {
            val value = objectValue.opt(field)
            if (value !is Number) return -1
            val numeric = value.toDouble()
            if (!numeric.isFinite() || numeric < 0 || numeric >= 9.223372036854776E18 || numeric != kotlin.math.floor(numeric)) return -1
            return value.toLong()
        }
    }
}
