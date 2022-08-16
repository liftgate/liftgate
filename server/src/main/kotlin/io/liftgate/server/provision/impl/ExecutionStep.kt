package io.liftgate.server.provision.impl

import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.provision.ServerProvisionStep

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object ExecutionStep : ServerProvisionStep
{
    override fun runStep(
        template: ServerTemplate, uid: String?, port: Int?,
        temporaryMeta: MutableMap<String, String>
    )
    {

    }
}
