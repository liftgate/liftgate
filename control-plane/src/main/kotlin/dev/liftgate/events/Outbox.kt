package dev.liftgate.events

import dev.liftgate.db.Outbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.time.OffsetDateTime

fun JdbcTransaction.enqueue(subject: Subject, payload: JsonObject) {
    Outbox.insert {
        it[Outbox.subject] = subject.value
        it[Outbox.payload] = payload
    }
}

fun JdbcTransaction.requestedSince(subject: Subject, key: String, since: OffsetDateTime): Set<String> =
    Outbox.select(Outbox.payload).where { (Outbox.subject eq subject.value) and ((Outbox.createdAt greater since) or Outbox.publishedAt.isNull()) }
        .mapNotNull { it[Outbox.payload][key]?.jsonPrimitive?.content }
        .toSet()
