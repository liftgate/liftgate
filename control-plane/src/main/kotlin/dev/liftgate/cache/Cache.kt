package dev.liftgate.cache

import com.hazelcast.config.Config as HazelcastConfig
import com.hazelcast.config.NearCacheConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.map.IMap
import dev.liftgate.config.Config

private const val DAY_SECONDS = 86_400
private const val HOUR_SECONDS = 3_600

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
        getMapConfig("rate-limits").setTimeToLiveSeconds(HOUR_SECONDS)
    })
    val sessions: IMap<String, String> = hazelcast.getMap("sessions")
    val passkeyChallenges: IMap<String, String> = hazelcast.getMap("passkey-challenges")
    val samlRequests: IMap<String, String> = hazelcast.getMap("saml-requests")
    val samlAssertions: IMap<String, String> = hazelcast.getMap("saml-assertions")
    private val rateLimits: IMap<String, Int> = hazelcast.getMap("rate-limits")

    fun allow(key: String, perHour: Int): Boolean =
        rateLimits.merge("$key:${System.currentTimeMillis() / (HOUR_SECONDS * 1000L)}", 1, Int::plus)!! <= perHour

    override fun close() = hazelcast.shutdown()
}
