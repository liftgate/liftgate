package io.liftgate.server.autoscale.impl

import io.liftgate.server.autoscale.AutoScalePropertyChoiceScheme
import io.liftgate.server.models.server.ServerTemplate

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object IncrementalPropertyChoiceScheme : AutoScalePropertyChoiceScheme
{
    override fun chooseUid(template: ServerTemplate): String
    {
        TODO("Not yet implemented")
    }

    override fun choosePort(template: ServerTemplate): Int
    {
        TODO("Not yet implemented")
    }
}
