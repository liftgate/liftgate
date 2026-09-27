package dev.liftgate

import dev.liftgate.admin.Admin
import dev.liftgate.auth.Access
import dev.liftgate.auth.ApiTokens
import dev.liftgate.auth.EmailCodes
import dev.liftgate.auth.GitConnections
import dev.liftgate.auth.Mailer
import dev.liftgate.auth.OAuth
import dev.liftgate.auth.OAuthProviders
import dev.liftgate.auth.Passkeys
import dev.liftgate.auth.Sessions
import dev.liftgate.auth.SignIn
import dev.liftgate.auth.Sso
import dev.liftgate.build.BuildAdmission
import dev.liftgate.build.Builder
import dev.liftgate.build.GitHubApp
import dev.liftgate.build.RegistryTokens
import dev.liftgate.cache.Cache
import dev.liftgate.config.Config
import dev.liftgate.config.Role
import dev.liftgate.db.Db
import dev.liftgate.db.Housekeeping
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.Deployments
import dev.liftgate.domain.Domains
import dev.liftgate.events.LeaderElection
import dev.liftgate.events.Nats
import dev.liftgate.events.OutboxRelay
import dev.liftgate.events.Subject
import dev.liftgate.events.uuid
import dev.liftgate.http.httpServer
import dev.liftgate.http.json
import dev.liftgate.k8s.DeploymentWatcher
import dev.liftgate.k8s.Reconciler
import dev.liftgate.k8s.Suspension
import dev.liftgate.metering.Meter
import dev.liftgate.metering.Prometheus
import dev.liftgate.org.Limits
import dev.liftgate.org.Orgs
import dev.liftgate.project.Projects
import dev.liftgate.secret.SecretBox
import dev.liftgate.service.EnvVars
import dev.liftgate.service.Services
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.EmbeddedServer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private const val DRAIN_MILLIS = 5_000L

/**
 * @author Dean
 * @date 9/17/2026
 */
class App(val config: Config) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Db(config)
    private val hazelcast = lazy { Cache(config).also { metrics.gauge("liftgate.hazelcast.members", it) { cache -> cache.members.toDouble() } } }
    val cache by hazelcast
    val nats = Nats(config)
    val secrets by lazy { SecretBox(checkNotNull(config.secretsMasterKey)) }
    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(UserAgent) { agent = "liftgate" }
    }
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val kube = KubernetesClientBuilder().build()
    private val limits = Limits(config.plans, config.customDomainsMax)
    val orgs = Orgs(db, limits)
    val sessions by lazy { Sessions(db, cache, orgs) }
    val apiTokens by lazy { ApiTokens(db, cache) }
    val access = Access(orgs)
    val projects = Projects(db, limits)
    val services = Services(db, limits)
    val envVars by lazy { EnvVars(db, secrets) }
    val builds = Builds(db)
    val deployments = Deployments(db)
    val domains = Domains(db, config.deployDomain, limits)
    val github by lazy { config.github?.let { GitHubApp(it, http) } }
    val oauth by lazy { OAuth(http, config.publicUrl, OAuthProviders.enabled(config)) }
    val signIn by lazy { SignIn(db, sessions, config.signup, config.signupAllow, consent = config.termsUrl != null) }
    val gitConnections by lazy { GitConnections(db, secrets, oauth) }
    val passkeys by lazy { Passkeys(config, db, cache, signIn) }
    val emailCodes by lazy { config.email?.let { EmailCodes(db, cache, checkNotNull(config.secretsMasterKey), Mailer(it), signIn) } }
    val sso by lazy { Sso(config.publicUrl, db, cache, signIn) }
    val registryTokens = RegistryTokens(db, services, config)
    val buildAdmission = BuildAdmission(db, config.plans)
    private val stopped = CountDownLatch(1)
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var stopping = false
        private set

    fun runs(role: Role) = config.role == Role.ALL || config.role == role

    fun start() {
        nats.ensureStream()
        server = httpServer(this).start(wait = false)
        if (runs(Role.API)) {
            val relay = OutboxRelay(db, nats)
            LeaderElection(config, kube, "liftgate-outbox-relay").start(scope) { coroutineScope { relay.start(this); Housekeeping(db).start(this) } }
            nats.consume(Subject.USER_UPDATED, "api-user-updated", scope) { sessions.evict(it.uuid("userId")) }
        }
        if (runs(Role.RECONCILER)) {
            Reconciler(this, kube).start()
            DeploymentWatcher(this, kube).start()
            Suspension(this, kube).start()
        }
        if (runs(Role.BUILDER)) Builder(this, kube).start()
        if (runs(Role.METER)) Meter(this, Prometheus(config.prometheusUrl, http)).start()
    }

    fun awaitShutdown() {
        Runtime.getRuntime().addShutdownHook(Thread(::close))
        stopped.await()
    }

    override fun close() {
        stopping = true
        Thread.sleep(DRAIN_MILLIS)
        server?.stop(1000, 5000)
        runBlocking { withTimeoutOrNull(10.seconds) { scope.coroutineContext.job.cancelAndJoin() } }
        http.close()
        nats.close()
        if (hazelcast.isInitialized()) cache.close()
        kube.close()
        db.close()
        stopped.countDown()
    }
}

fun main(args: Array<String>) {
    val config = Config.fromEnv()
    if (args.firstOrNull() == "admin") {
        val result = runCatching { Db(config).use { runBlocking { Admin(it, config.plans).run(args.drop(1)) } } }
        result.onSuccess(::println).onFailure { System.err.println(it.message ?: it) }
        exitProcess(if (result.isSuccess) 0 else 1)
    }
    if (config.role == Role.MIGRATE) return Db(config).use { it.migrate() }
    val app = App(config)
    app.start()
    app.awaitShutdown()
}
