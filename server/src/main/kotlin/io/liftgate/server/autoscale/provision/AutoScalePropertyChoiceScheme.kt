package io.liftgate.server.autoscale.provision

import io.liftgate.server.models.server.ServerTemplate

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
interface AutoScalePropertyChoiceScheme
{
    fun chooseUid(template: ServerTemplate): String
    fun choosePort(template: ServerTemplate): Int
}
