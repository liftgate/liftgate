package dev.liftgate.cache

import com.hazelcast.config.Config as HazelcastConfig
import com.hazelcast.config.EvictionConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.MaxSizePolicy
import com.hazelcast.config.NearCacheConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.map.IMap
import dev.liftgate.config.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private const val DAY_SECONDS = 86_400
private const val HOUR_SECONDS = 3_600
const val PASSKEY_CHALLENGES_CAP = 10_000
private const val SAML_REQUESTS_CAP = 10_000
private const val SAML_ASSERTIONS_CAP = 100_000
private const val RATE_LIMITS_CAP = 100_000
private const val DETECTIONS_TTL_SECONDS = 600
private const val DETECTIONS_CAP = 1_000

/**
 * @author Dean
 * @date 9/17/2026
 */
class Cache(config: Config) : AutoCloseable {
    private val hazelcast = Hazelcast.newHazelcastInstance(HazelcastConfig().apply {
        setClusterName(config.hazelcastCluster)
        setProperty("hazelcast.logging.type", "slf4j")
        setProperty("hazelcast.phone.home.enabled", "false")
        networkConfig.join.apply {
            autoDetectionConfig.setEnabled(false)
            multicastConfig.setEnabled(false)
            if (config.hazelcastKubernetes) kubernetesConfig.setEnabled(true).setProperty("service-name", "${config.hazelcastCluster}-hazelcast") else tcpIpConfig.setEnabled(true).addMember("127.0.0.1")
        }
        getMapConfig("sessions").setTimeToLiveSeconds(DAY_SECONDS).setNearCacheConfig(NearCacheConfig().setTimeToLiveSeconds(DAY_SECONDS))
        getMapConfig("rate-limits").setTimeToLiveSeconds(HOUR_SECONDS).setEvictionConfig(lru(RATE_LIMITS_CAP))
        getMapConfig("passkey-challenges").setEvictionConfig(lru(PASSKEY_CHALLENGES_CAP))
        getMapConfig("saml-requests").setEvictionConfig(lru(SAML_REQUESTS_CAP))
        getMapConfig("saml-assertions").setEvictionConfig(lru(SAML_ASSERTIONS_CAP))
        getMapConfig("detections").setTimeToLiveSeconds(DETECTIONS_TTL_SECONDS).setEvictionConfig(lru(DETECTIONS_CAP))
    })
    val sessions: IMap<String, String> = hazelcast.getMap("sessions")
    val passkeyChallenges: IMap<String, String> = hazelcast.getMap("passkey-challenges")
    val samlRequests: IMap<String, String> = hazelcast.getMap("saml-requests")
    val samlAssertions: IMap<String, String> = hazelcast.getMap("saml-assertions")
    private val rateLimits: IMap<String, Int> = hazelcast.getMap("rate-limits")
    val detections: MutableMap<String, String> = hazelcast.getMap("detections")
    val running get() = hazelcast.lifecycleService.isRunning
    val members get() = hazelcast.cluster.members.size

    fun allow(key: String, limit: Int, window: Duration = 1.hours): Boolean =
        rateLimits.merge("$key:${System.currentTimeMillis() / window.inWholeMilliseconds}", 1, Int::plus)!! <= limit

    override fun close() = hazelcast.shutdown()
}

private fun lru(size: Int) = EvictionConfig().setEvictionPolicy(EvictionPolicy.LRU).setMaxSizePolicy(MaxSizePolicy.PER_NODE).setSize(size)
