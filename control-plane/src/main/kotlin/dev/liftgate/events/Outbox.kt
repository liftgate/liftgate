package dev.liftgate.events

import dev.liftgate.db.Outbox
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert

fun JdbcTransaction.enqueue(subject: Subject, payload: JsonObject) {
    Outbox.insert {
        it[Outbox.subject] = subject.value
        it[Outbox.payload] = payload
    }
}
