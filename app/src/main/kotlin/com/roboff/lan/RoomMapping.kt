package com.roboff.lan

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal

/** A validated LAN get_room_mapping response, including optional cloud room IDs. */
data class RoomMapping(
    val ids: List<Int>,
    val cloudIds: Map<Int, String>,
) {
    companion object {
        /** Invalid responses throw before callers replace their last valid room list. */
        @Throws(IOException::class)
        fun parse(result: Any?): RoomMapping {
            if (result !is JSONArray) {
                throw IOException("The vacuum returned an invalid room list: expected an array.")
            }

            val ids = linkedSetOf<Int>()
            val cloudIds = linkedMapOf<Int, String>()
            for (index in 0 until result.length()) {
                val entry = result.opt(index)
                if (entry !is JSONArray || entry.length() == 0) {
                    throw invalid(index, "expected a room entry")
                }
                val segment = entry.opt(0)
                if (segment !is Number) {
                    throw invalid(index, "the room ID must be a positive integer")
                }
                val id = try {
                    BigDecimal(segment.toString()).intValueExact()
                } catch (_: NumberFormatException) {
                    throw invalid(index, "the room ID must be a positive integer")
                } catch (_: ArithmeticException) {
                    throw invalid(index, "the room ID must be a positive integer")
                }
                if (id <= 0) throw invalid(index, "the room ID must be a positive integer")
                ids += id

                val cloudId = entry.opt(1)
                if (cloudId == null || cloudId === JSONObject.NULL) continue
                val normalized = when (cloudId) {
                    is String -> cloudId
                    is Number -> try {
                        BigDecimal(cloudId.toString()).toBigIntegerExact().toString()
                    } catch (_: NumberFormatException) {
                        throw invalid(index, "the room reference must be text or an integer")
                    } catch (_: ArithmeticException) {
                        throw invalid(index, "the room reference must be text or an integer")
                    }
                    else -> throw invalid(index, "the room reference must be text or an integer")
                }
                cloudIds.putIfAbsent(id, normalized)
            }
            return RoomMapping(ids.toList(), cloudIds)
        }

        private fun invalid(index: Int, reason: String) =
            IOException("The vacuum returned an invalid room list at entry ${index + 1}: $reason.")
    }
}
