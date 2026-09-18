package dev.liftgate

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
import dev.liftgate.build.Builder
import dev.liftgate.build.GitHubApp
import dev.liftgate.cache.Cache
import dev.liftgate.config.Config
import dev.liftgate.config.Role
import dev.liftgate.db.Db
import dev.liftgate.deploy.Builds
import dev.liftgate.deploy.Deployments
import dev.liftgate.domain.Domains
import dev.liftgate.events.LeaderElection
import dev.liftgate.events.Nats
import dev.liftgate.events.OutboxRelay
import dev.liftgate.http.httpServer
import dev.liftgate.http.json
import dev.liftgate.k8s.DeploymentWatcher
import dev.liftgate.k8s.Reconciler
import dev.liftgate.metering.Meter
import dev.liftgate.metering.Prometheus
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CountDownLatch

/**
 * @author Dean
 * @date 9/17/2026
 */
class App(val config: Config) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Db(config)
    val cache = Cache(config)
    val nats = Nats(config)
    val secrets = SecretBox(config.secretsMasterKey)
    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(UserAgent) { agent = "liftgate" }
    }
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val kube = KubernetesClientBuilder().build()
    val orgs = Orgs(db)
    val sessions = Sessions(db, cache, orgs)
    val apiTokens = ApiTokens(db)
    val access = Access(orgs)
    val projects = Projects(db)
    val services = Services(db)
    val envVars = EnvVars(db, secrets)
    val builds = Builds(db)
    val deployments = Deployments(db)
    val domains = Domains(db, config.deployDomain)
    val github = config.github?.let { GitHubApp(it, http) }
    val oauth = OAuth(http, config.publicUrl, OAuthProviders.enabled(config))
    val signIn = SignIn(db, sessions)
    val gitConnections = GitConnections(db, secrets, oauth)
    val passkeys = Passkeys(config, db, cache, signIn)
    val emailCodes = config.email?.let { EmailCodes(db, cache, config.secretsMasterKey, Mailer(it), signIn) }
    val sso = Sso(config.publicUrl, db, cache, signIn)
    private val stopped = CountDownLatch(1)
    private var server: EmbeddedServer<*, *>? = null

    fun runs(role: Role) = config.role == Role.ALL || config.role == role

    fun start() {
        db.migrate()
        nats.ensureStream()
        if (runs(Role.API)) {
            val relay = OutboxRelay(db, nats)
            LeaderElection(config, kube).start(scope) { coroutineScope { relay.start(this) } }
            server = httpServer(this).start(wait = false)
        }
        if (runs(Role.RECONCILER)) {
            Reconciler(this, kube).start()
            DeploymentWatcher(this, kube).start()
        }
        if (runs(Role.BUILDER)) Builder(this, kube).start()
        if (runs(Role.METER)) Meter(this, Prometheus(config.prometheusUrl, http)).start()
    }

    fun awaitShutdown() {
        Runtime.getRuntime().addShutdownHook(Thread(::close))
        stopped.await()
    }

    override fun close() {
        server?.stop(1000, 5000)
        scope.cancel()
        http.close()
        nats.close()
        cache.close()
        kube.close()
        db.close()
        stopped.countDown()
    }
}

fun main() {
    val app = App(Config.fromEnv())
    app.start()
    app.awaitShutdown()
}
