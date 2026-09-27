package dev.liftgate.events

import java.time.Duration

/**
 * @author Dean
 * @date 9/27/2026
 */
class Redeliver(val delay: Duration) : RuntimeException("redelivering after $delay")
