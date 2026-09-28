package dev.liftgate.notify

import dev.liftgate.auth.randomToken
import dev.liftgate.db.Db
import dev.liftgate.db.NotificationChannels as ChannelsTable
import dev.liftgate.db.Organizations
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.http.conflict
import dev.liftgate.http.notFound
import dev.liftgate.notify.NotificationChannel.Event
import dev.liftgate.notify.NotificationChannel.Kind
import dev.liftgate.secret.SecretBox
import io.ktor.http.Url
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.util.UUID

private const val MAX_CHANNELS = 10

/**
 * @author Dean
 * @date 9/27/2026
 */
class NotificationChannels(private val db: Db, private val secrets: SecretBox) {
    suspend fun list(orgId: UUID): List<NotificationChannel> = db.tx {
        ChannelsTable.selectAll().where { ChannelsTable.orgId eq orgId }.orderBy(ChannelsTable.createdAt).map { it.toChannel() }
    }

    suspend fun create(orgId: UUID, name: String, kind: Kind, url: String, events: Set<Event>): NotificationChannel = db.tx {
        Organizations.select(Organizations.id).where { Organizations.id eq orgId }.forUpdate().toList()
        if (ChannelsTable.select(ChannelsTable.id).where { ChannelsTable.orgId eq orgId }.count() >= MAX_CHANNELS) {
            conflict("an organization can have at most $MAX_CHANNELS notification channels")
        }
        val secret = randomToken().takeIf { kind == Kind.WEBHOOK }
        ChannelsTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[ChannelsTable.orgId] = orgId
            it[ChannelsTable.name] = name
            it[ChannelsTable.kind] = kind.sql
            it[urlEncrypted] = secrets.seal(url)
            it[secretEncrypted] = secret?.let(secrets::seal)
            it[ChannelsTable.events] = events.map { e -> e.sql }
        }.single().toChannel().copy(secret = secret)
    }

    suspend fun update(orgId: UUID, id: UUID, name: String, events: Set<Event>): NotificationChannel = db.tx {
        ChannelsTable.updateReturning(ChannelsTable.columns, { (ChannelsTable.id eq id) and (ChannelsTable.orgId eq orgId) }) {
            it[ChannelsTable.name] = name
            it[ChannelsTable.events] = events.map { e -> e.sql }
        }.singleOrNull()?.toChannel() ?: notFound("notification channel")
    }

    suspend fun delete(orgId: UUID, id: UUID) {
        db.tx { if (ChannelsTable.deleteWhere { (ChannelsTable.id eq id) and (ChannelsTable.orgId eq orgId) } == 0) notFound("notification channel") }
    }

    suspend fun subscribers(orgId: UUID, event: Event): List<UUID> = db.tx {
        ChannelsTable.select(ChannelsTable.id, ChannelsTable.events).where { ChannelsTable.orgId eq orgId }
            .filter { event.sql in it[ChannelsTable.events] }
            .map { it[ChannelsTable.id] }
    }

    suspend fun endpoint(orgId: UUID, id: UUID): Endpoint? = db.tx {
        ChannelsTable.selectAll().where { (ChannelsTable.id eq id) and (ChannelsTable.orgId eq orgId) }.singleOrNull()?.let {
            Endpoint(it[ChannelsTable.kind].toEnum(), secrets.open(it[ChannelsTable.urlEncrypted]), it[ChannelsTable.secretEncrypted]?.let(secrets::open))
        }
    }

    private fun ResultRow.toChannel() = NotificationChannel(
        this[ChannelsTable.id],
        this[ChannelsTable.name],
        this[ChannelsTable.kind].toEnum(),
        Url(secrets.open(this[ChannelsTable.urlEncrypted])).host,
        this[ChannelsTable.events].map { it.toEnum<Event>() }.toSet(),
        this[ChannelsTable.createdAt].toInstant(),
    )

    data class Endpoint(val kind: Kind, val url: String, val secret: String?)
}
