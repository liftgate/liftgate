package dev.liftgate.org

const val DEFAULT_PLAN = "default"
private const val UNLIMITED = "unlimited"

/**
 * @author Dean
 * @date 9/27/2026
 */
data class Plans(val all: Map<String, Plan> = mapOf(UNLIMITED to Plan()), val default: String = UNLIMITED) {
    init {
        check(default in all) { "LIFTGATE_DEFAULT_PLAN must be one of the plans in LIFTGATE_PLANS: ${all.keys.joinToString()}" }
        check(DEFAULT_PLAN !in all) { "LIFTGATE_PLANS cannot define a plan named $DEFAULT_PLAN, which stands for LIFTGATE_DEFAULT_PLAN" }
    }

    fun name(plan: String) = plan.takeIf { it in all } ?: default

    fun of(plan: String) = all.getValue(name(plan))
}
