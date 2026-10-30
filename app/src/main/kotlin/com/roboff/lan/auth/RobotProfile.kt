package com.roboff.lan.auth

import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap

/** A locally paired robot. The local key is deliberately omitted from toString(). */
class RobotProfile(
    id: String,
    name: String,
    host: String,
    localKey: String,
    model: String,
    homeId: String = "",
    homeName: String = "",
    roomNames: Map<String, String> = emptyMap(),
) {
    val id = id.trim().also { require(it.isNotEmpty()) { "A robot identifier is required." } }
    val name = name.trim().ifEmpty { "Roborock" }
    val host = host.trim()
    val localKey = validateLocalKey(localKey)
    val model = model.trim()
    val homeId = homeId.trim()
    val homeName = homeName.trim()
    val roomNames: Map<String, String> = Collections.unmodifiableMap(
        roomNames.mapNotNull { (key, value) ->
            key.trim().takeIf(String::isNotEmpty)?.let { it to value.trim() }
        }.filter { it.second.isNotEmpty() }.toMap(LinkedHashMap()),
    )

    fun hasAddress(): Boolean = host.isNotEmpty()

    fun withHost(newHost: String) = RobotProfile(id, name, newHost, localKey, model, homeId, homeName, roomNames)

    companion object {
        /** Roborock's local key is exactly 16 UTF-8 bytes, not a hex-encoded AES key. */
        fun validateLocalKey(key: String): String {
            val value = key.trim()
            require(value.toByteArray(StandardCharsets.UTF_8).size == 16) {
                "Enter the 16-byte local key for this vacuum."
            }
            require(value.none { it.isISOControl() }) {
                "The local key must not contain control characters."
            }
            return value
        }
    }
}
