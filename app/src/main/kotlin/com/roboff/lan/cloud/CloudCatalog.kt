package com.roboff.lan.cloud

import com.roboff.lan.auth.RobotProfile
import org.json.JSONArray
import org.json.JSONObject

/** Account setup metadata needed for local operation. Session tokens never enter this model. */
class CloudCatalog(
    val homeId: String,
    homeName: String,
    devices: List<Device>,
    roomNames: Map<String, String>,
    warnings: List<String>,
) {
    val homeName = homeName.ifBlank { "My home" }
    val devices: List<Device> = devices.toList()
    val roomNames: Map<String, String> = roomNames.toMap()
    val warnings: List<String> = warnings.toList()

    class Device(
        val id: String,
        name: String,
        val model: String,
        val protocol: String,
        localKey: String,
        roomNames: Map<String, String>,
        val shared: Boolean,
    ) {
        val name = name.ifBlank { "Roborock" }
        private val key = RobotProfile.validateLocalKey(localKey)
        val roomNames: Map<String, String> = roomNames.toMap()

        fun localKey(): String = key

        fun withRooms(names: Map<String, String>) = Device(id, name, model, protocol, key, names, shared)
    }

    companion object {
        /** Extracts vacuum keys and home room names from the one-home cloud response. */
        fun fromHomeData(home: JSONObject, homeId: String, homeName: String): CloudCatalog {
            val roomNames = readRooms(home.optJSONArray("rooms"))
            val products = linkedMapOf<String, JSONObject>()
            val productArray = home.optJSONArray("products")
            if (productArray != null) {
                for (index in 0 until minOf(1000, productArray.length())) {
                    val product = productArray.optJSONObject(index) ?: continue
                    products[text(product, "id")] = product
                }
            }

            val devices = linkedMapOf<String, Device>()
            val warnings = mutableListOf<String>()
            for (group in listOf("devices", "receivedDevices")) {
                val entries = home.optJSONArray(group) ?: continue
                for (index in 0 until minOf(1000, entries.length())) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val id = text(entry, "duid")
                    val product = products[text(entry, "productId")]
                    val model = text(entry, "model", text(product, "model"))
                    val category = text(product, "category")
                    if (!model.startsWith("roborock.vacuum.") &&
                        !model.startsWith("rockrobo.vacuum.") && category != "robot.vacuum.cleaner"
                    ) continue
                    if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
                        warnings += "A vacuum with an invalid identifier was skipped."
                        continue
                    }
                    val name = text(entry, "name", text(product, "name", "Roborock")).ifBlank { "Roborock" }
                    val protocol = text(entry, "pv")
                    val shared = group == "receivedDevices"
                    try {
                        val deviceRooms = if (shared) emptyMap() else roomNames
                        devices.putIfAbsent(
                            id,
                            Device(id, name, model, protocol, text(entry, "localKey"), deviceRooms, shared),
                        )
                    } catch (_: IllegalArgumentException) {
                        warnings += "A vacuum without a valid local key was skipped."
                    }
                }
            }
            return CloudCatalog(text(home, "id", homeId), text(home, "name", homeName), devices.values.toList(), roomNames, warnings)
        }

        fun readRooms(rooms: JSONArray?): Map<String, String> {
            val names = linkedMapOf<String, String>()
            if (rooms == null) return names
            for (index in 0 until minOf(1000, rooms.length())) {
                val room = rooms.optJSONObject(index) ?: continue
                val id = text(room, "id", text(room, "roomId"))
                val name = text(room, "name")
                if (id.isNotBlank() && id.length <= 128 && name.isNotBlank()) names[id] = name
            }
            return names
        }

        fun text(value: JSONObject?, key: String, fallback: String = ""): String {
            val item = value?.opt(key)
            return if (item == null || item === JSONObject.NULL) fallback else item.toString()
        }
    }
}
