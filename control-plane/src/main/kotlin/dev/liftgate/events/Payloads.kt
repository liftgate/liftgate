package dev.liftgate.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

fun JsonObject.uuid(key: String): UUID = UUID.fromString(getValue(key).jsonPrimitive.content)
