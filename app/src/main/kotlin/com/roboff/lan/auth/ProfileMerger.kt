package com.roboff.lan.auth

import java.util.LinkedHashMap

/** Reconciles imported account devices without removing unrelated local pairings. */
internal object ProfileMerger {
    fun merge(existing: List<RobotProfile>, incoming: List<RobotProfile>): MutableList<RobotProfile> {
        val result = existing.toMutableList()
        for (imported in incoming) {
            var insertion = result.size
            var previous: RobotProfile? = null
            result.forEachIndexed { index, saved ->
                if (sameRobot(saved, imported)) {
                    insertion = minOf(insertion, index)
                    previous = when {
                        previous == null -> saved
                        saved.id == imported.id -> combine(previous!!, saved)
                        else -> combine(saved, previous!!)
                    }
                }
            }
            val merged = combine(previous, imported)
            result.removeAll { saved -> sameRobot(saved, imported) }
            result.add(minOf(insertion, result.size), merged)
        }
        return result
    }

    private fun sameRobot(saved: RobotProfile, imported: RobotProfile): Boolean {
        if (saved.id == imported.id) return true
        val savedLegacy = saved.hasAddress() && saved.id == saved.host
        val importedLegacy = imported.hasAddress() && imported.id == imported.host
        if (saved.hasAddress() && imported.hasAddress() && saved.host == imported.host &&
            (savedLegacy || importedLegacy)
        ) return true
        return savedLegacy != importedLegacy && saved.localKey == imported.localKey
    }

    private fun combine(saved: RobotProfile?, incoming: RobotProfile): RobotProfile {
        if (saved == null) return incoming
        val rooms = LinkedHashMap(saved.roomNames).apply { putAll(incoming.roomNames) }
        val incomingLegacy = incoming.hasAddress() && incoming.id == incoming.host
        val savedLegacy = saved.hasAddress() && saved.id == saved.host
        val identity = if (incomingLegacy && !savedLegacy) saved.id else incoming.id
        return RobotProfile(
            identity,
            incoming.name,
            choose(incoming.host, saved.host),
            incoming.localKey,
            choose(incoming.model, saved.model),
            choose(incoming.homeId, saved.homeId),
            choose(incoming.homeName, saved.homeName),
            rooms,
        )
    }

    private fun choose(incoming: String, saved: String): String = incoming.ifEmpty { saved }
}
