package com.roboff.lan

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The exact rooms and passes reviewed in a cleaning confirmation. */
data class RoomCleanPlan private constructor(
    val robotId: String,
    val roomIds: List<Int>,
    val roomNames: List<String>,
    val repeat: Int,
) {
    fun summary(): String = "${roomIds.size}${if (roomIds.size == 1) " room" else " rooms"} · " +
        "$repeat${if (repeat == 1) " pass" else " passes"}"

    /** Builds a fresh LAN app_segment_clean payload. */
    @Throws(JSONException::class)
    fun params(): JSONArray {
        val plan = JSONObject()
            .put("segments", JSONArray(roomIds))
            .put("repeat", repeat)
            .put("clean_order_mode", 0)
        return JSONArray().put(plan)
    }

    companion object {
        /** Rejects stale selections so they cannot turn into a whole-home request. */
        fun create(
            robotId: String,
            availableIds: List<Int>,
            displayNames: Map<Int, String>,
            selected: Set<Int>,
            repeat: Int,
        ): RoomCleanPlan {
            require(robotId.isNotBlank()) { "Choose a vacuum before cleaning rooms." }
            require(repeat in 1..3) { "Choose between one and three passes." }
            require(selected.isNotEmpty()) { "Select at least one room." }
            require(availableIds.all { it > 0 }) { "Refresh rooms before choosing a cleaning plan." }
            require(selected.all { it > 0 && it in availableIds }) {
                "The selected rooms changed. Select them again."
            }

            val ids = availableIds.distinct().filter { it in selected }
            val names = ids.map { id -> displayNames[id]?.takeIf(String::isNotBlank) ?: "Room $id" }
            return RoomCleanPlan(robotId, ids, names, repeat)
        }
    }
}
