package dev.liftgate.db

import dev.liftgate.TestDatabase
import dev.liftgate.deploy.Builds
import dev.liftgate.org.Orgs
import dev.liftgate.org.insertUser
import dev.liftgate.project.Projects
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import dev.liftgate.service.Services
import dev.liftgate.waitingLocks
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * @author Dean
 * @date 9/27/2026
 */
class BacklogTest {
    private val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private fun value(series: String) = metrics.scrape().lines().single { it.startsWith("$series ") }.substringAfterLast(' ').toDouble()

    @Test
    fun `the relay leader reports the outbox backlog and the build queue until it stops leading`() = runBlocking {
        val db = TestDatabase.clean()
        val owner = db.tx { insertUser("dean", null, null, null) }.id
        val projects = Projects(db)
        val project = projects.create(Orgs(db).create("acme", "Acme", owner).id, "shop", "Shop", "acme/shop", 1)
        val service = Services(db).create(projects.environments(project.id).single().id, ServiceSpec("api", "API", ServiceKind.WEB))
        val builds = Builds(db)
        builds.request(service.id, "aaa", null, "main")
        builds.markRunning(builds.request(service.id, "bbb", null, "dev").id)
        val pending = db.tx {
            Outbox.update { it[createdAt] = now().minusMinutes(3) }
            Outbox.selectAll().where { Outbox.publishedAt.isNull() }.count()
        }

        val backlog = Backlog(db, metrics).start(this)
        withTimeout(10.seconds) { while ("liftgate_builds{status=\"queued\"} 1.0" !in metrics.scrape()) delay(50) }
        assertEquals(1.0, value("liftgate_builds{status=\"running\"}"))
        assertEquals(pending.toDouble(), value("liftgate_outbox_pending"))
        assertTrue(value("liftgate_outbox_oldest_pending_seconds") >= 180)

        backlog.cancelAndJoin()
        assertTrue(metrics.scrape().lines().none { it.startsWith("liftgate_") })
    }

    @Test
    fun `a term that starts while the previous one is still in a query keeps its gauges after that query returns`() = runBlocking {
        val db = TestDatabase.clean()
        val backlog = Backlog(db, metrics)
        val locked = CompletableDeferred<Unit>()
        val unlock = CountDownLatch(1)
        launch(Dispatchers.IO) { db.tx { exec("lock table outbox"); locked.complete(Unit); unlock.await(30, TimeUnit.SECONDS) } }
        locked.await()
        val first = backlog.start(this)
        withTimeout(10.seconds) { while (db.tx { waitingLocks() } == 0L) delay(50) }

        first.cancel()
        val second = backlog.start(this)
        yield()
        unlock.countDown()
        first.join()
        withTimeout(10.seconds) { while ("liftgate_outbox_pending 0.0" !in metrics.scrape()) delay(50) }
        second.cancelAndJoin()
    }
}
