package dev.liftgate.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.ReferenceOption.SET_NULL
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

private fun Table.createdAtColumn() = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
private fun Table.oneOf(name: String, vararg values: String) = text(name).check { it inList values.toList() }

/**
 * @author Dean
 * @date 9/17/2026
 */
object Users : Table("users") {
    val id = javaUUID("id")
    val login = text("login")
    val name = text("name").nullable()
    val email = text("email").nullable()
    val emailVerified = bool("email_verified").default(false)
    val avatarUrl = text("avatar_url").nullable()
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Organizations : Table("organizations") {
    val id = javaUUID("id")
    val slug = text("slug").uniqueIndex()
    val name = text("name")
    val plan = text("plan").default("free")
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Memberships : Table("memberships") {
    val orgId = reference("org_id", Organizations.id, onDelete = CASCADE)
    val userId = reference("user_id", Users.id, onDelete = CASCADE)
    val role = oneOf("role", "owner", "admin", "member")
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(orgId, userId)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Sessions : Table("sessions") {
    val id = text("id")
    val userId = reference("user_id", Users.id, onDelete = CASCADE)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)
}

/**
 * @author Dean
 * @date 9/18/2026
 */
object Identities : Table("identities") {
    val id = javaUUID("id")
    val userId = reference("user_id", Users.id, onDelete = CASCADE)
    val provider = oneOf("provider", "github", "google", "gitlab", "bitbucket", "email", "saml")
    val subject = text("subject")
    val email = text("email").nullable()
    val emailVerified = bool("email_verified").default(false)
    val createdAt = createdAtColumn()
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(provider, subject)
    }
}

/**
 * @author Dean
 * @date 9/18/2026
 */
object Passkeys : Table("passkeys") {
    val id = javaUUID("id")
    val userId = reference("user_id", Users.id, onDelete = CASCADE)
    val credentialId = binary("credential_id").uniqueIndex()
    val publicKey = binary("public_key")
    val signatureCount = long("signature_count")
    val name = text("name")
    val createdAt = createdAtColumn()
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("passkeys_user", false, userId)
    }
}

/**
 * @author Dean
 * @date 9/18/2026
 */
object EmailCodes : Table("email_codes") {
    val email = text("email")
    val codeHash = binary("code_hash")
    val attempts = integer("attempts").default(0)
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(email)
}

/**
 * @author Dean
 * @date 9/18/2026
 */
object GitConnections : Table("git_connections") {
    val userId = reference("user_id", Users.id, onDelete = CASCADE)
    val provider = oneOf("provider", "github")
    val accountLogin = text("account_login")
    val accessToken = binary("access_token")
    val refreshToken = binary("refresh_token").nullable()
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(userId, provider)
}

/**
 * @author Dean
 * @date 9/18/2026
 */
object SsoConnections : Table("sso_connections") {
    val id = javaUUID("id")
    val orgId = reference("org_id", Organizations.id, onDelete = CASCADE).uniqueIndex()
    val idpEntityId = text("idp_entity_id")
    val idpSsoUrl = text("idp_sso_url")
    val idpCertificate = text("idp_certificate")
    val emailDomains = array<String>("email_domains")
    val verifiedDomains = array<String>("verified_domains")
    val verificationToken = text("verification_token")
    val defaultRole = oneOf("default_role", "admin", "member").default("member")
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object ApiTokens : Table("api_tokens") {
    val id = javaUUID("id")
    val orgId = reference("org_id", Organizations.id, onDelete = CASCADE)
    val name = text("name")
    val tokenHash = text("token_hash").uniqueIndex()
    val createdBy = reference("created_by", Users.id)
    val createdAt = createdAtColumn()
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object GitHubInstallations : Table("github_installations") {
    val id = long("id")
    val orgId = reference("org_id", Organizations.id, onDelete = CASCADE)
    val accountLogin = text("account_login")
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Projects : Table("projects") {
    val id = javaUUID("id")
    val orgId = reference("org_id", Organizations.id, onDelete = CASCADE)
    val slug = text("slug")
    val name = text("name")
    val repoFullName = text("repo_full_name")
    val repoDefaultBranch = text("repo_default_branch").default("main")
    val installationId = reference("installation_id", GitHubInstallations.id)
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(orgId, slug)
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Environments : Table("environments") {
    val id = javaUUID("id")
    val projectId = reference("project_id", Projects.id, onDelete = CASCADE)
    val slug = text("slug")
    val name = text("name")
    val kind = oneOf("kind", "production", "preview")
    val branch = text("branch")
    val namespace = text("namespace").uniqueIndex()
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(projectId, slug)
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Services : Table("services") {
    val id = javaUUID("id")
    val environmentId = reference("environment_id", Environments.id, onDelete = CASCADE)
    val slug = text("slug")
    val name = text("name")
    val kind = oneOf("kind", "web", "worker", "cron", "static")
    val rootDir = text("root_dir").default("/")
    val buildStrategy = oneOf("build_strategy", "auto", "dockerfile").default("auto")
    val dockerfilePath = text("dockerfile_path").default("Dockerfile")
    val port = integer("port").nullable()
    val replicas = integer("replicas").default(1)
    val cpuMillis = integer("cpu_millis").default(500)
    val memoryMb = integer("memory_mb").default(512)
    val cronSchedule = text("cron_schedule").nullable()
    val startCommand = text("start_command").nullable()
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(environmentId, slug)
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object EnvVars : Table("env_vars") {
    val id = javaUUID("id")
    val serviceId = reference("service_id", Services.id, onDelete = CASCADE)
    val name = text("name")
    val valueEncrypted = binary("value_encrypted")
    val isSecret = bool("is_secret").default(false)
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(serviceId, name)
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Builds : Table("builds") {
    val id = javaUUID("id")
    val serviceId = reference("service_id", Services.id, onDelete = CASCADE)
    val commitSha = text("commit_sha")
    val commitMessage = text("commit_message").nullable()
    val branch = text("branch")
    val status = oneOf("status", "queued", "running", "succeeded", "failed", "cancelled")
    val imageRef = text("image_ref").nullable()
    val error = text("error").nullable()
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)

    init {
        index("builds_service_created", false, serviceId, createdAt)
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Deployments : Table("deployments") {
    val id = javaUUID("id")
    val serviceId = reference("service_id", Services.id, onDelete = CASCADE)
    val buildId = reference("build_id", Builds.id)
    val status = oneOf("status", "pending", "releasing", "running", "failed", "superseded", "rolled_back")
    val replicasReady = integer("replicas_ready").default(0)
    val error = text("error").nullable()
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)

    init {
        index("deployments_service_created", false, serviceId, createdAt)
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Domains : Table("domains") {
    val id = javaUUID("id")
    val serviceId = reference("service_id", Services.id, onDelete = CASCADE)
    val hostname = text("hostname")
    val kind = oneOf("kind", "platform", "custom")
    val verificationToken = text("verification_token").nullable()
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()
    val certificateStatus = text("certificate_status").default("pending")
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(serviceId, hostname)
        index("domains_verified_hostname", true, hostname) { verifiedAt.isNotNull() }
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object UsageRecords : Table("usage_records") {
    val id = long("id").autoIncrement()
    val orgId = reference("org_id", Organizations.id, onDelete = CASCADE)
    val serviceId = reference("service_id", Services.id, onDelete = SET_NULL).nullable()
    val metric = text("metric")
    val quantity = decimal("quantity", 20, 6)
    val windowStart = timestampWithTimeZone("window_start")
    val windowEnd = timestampWithTimeZone("window_end")
    override val primaryKey = PrimaryKey(id)

    init {
        index("usage_records_org_window", false, orgId, windowStart)
    }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object AuditLog : Table("audit_log") {
    val id = long("id").autoIncrement()
    val orgId = reference("org_id", Organizations.id, onDelete = CASCADE).nullable()
    val actorUserId = reference("actor_user_id", Users.id, onDelete = SET_NULL).nullable()
    val action = text("action")
    val targetType = text("target_type")
    val targetId = text("target_id")
    val details = jsonb("details", Json, JsonObject.serializer()).default(JsonObject(emptyMap()))
    val createdAt = createdAtColumn()
    override val primaryKey = PrimaryKey(id)
}

/**
 * @author Dean
 * @date 9/17/2026
 */
object Outbox : Table("outbox") {
    val id = long("id").autoIncrement()
    val subject = text("subject")
    val payload = jsonb("payload", Json, JsonObject.serializer())
    val createdAt = createdAtColumn()
    val publishedAt = timestampWithTimeZone("published_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index("outbox_unpublished", false, id) { publishedAt.isNull() }
    }
}
