package io.liftgate.server.provision.step

import io.liftgate.server.models.server.ServerTemplate

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
interface ServerProvisionStep
{
    fun runStep(
        template: ServerTemplate,
        uid: String?, port: Int?,
        temporaryMeta: MutableMap<String, String>
    )
}
