package com.roboff.lan.auth

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Keeps the encrypted version-one profile representation readable. */
internal object ProfileCodec {
    @Throws(JSONException::class)
    fun decode(array: JSONArray): List<RobotProfile> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val roomNames = linkedMapOf<String, String>()
            val encodedNames = item.optJSONObject("roomNames")
            if (encodedNames != null) {
                val keys = encodedNames.keys()
                while (keys.hasNext()) {
                    val roomId = keys.next()
                    val name = encodedNames.opt(roomId)
                    if (name is String) roomNames[roomId] = name
                }
            }
            add(
                RobotProfile(
                    item.getString("id"),
                    item.getString("name"),
                    item.optString("host", ""),
                    item.getString("localKey"),
                    item.optString("model", ""),
                    item.optString("homeId", ""),
                    item.optString("homeName", ""),
                    roomNames,
                ),
            )
        }
    }

    @Throws(JSONException::class)
    fun encode(profiles: List<RobotProfile>): JSONArray = JSONArray().apply {
        profiles.forEach { profile ->
            put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("localKey", profile.localKey)
                    .put("model", profile.model)
                    .put("homeId", profile.homeId)
                    .put("homeName", profile.homeName)
                    .put("roomNames", JSONObject(profile.roomNames)),
            )
        }
    }
}
