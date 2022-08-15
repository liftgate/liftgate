package io.liftgate.server.autoscale.impl

import io.liftgate.server.autoscale.AutoScalePropertyChoiceScheme
import io.liftgate.server.listening
import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.server.ServerHandler

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object IncrementalPropertyChoiceScheme : AutoScalePropertyChoiceScheme
{
    override fun chooseUid(template: ServerTemplate): String
    {
        val servers = ServerHandler
            .findServersByClassifier(
                template.id
            )

        var found = false
        var current = 0

        while (!found)
        {
            current++
            found = servers
                .none {
                    current.toString() in it.serverId
                }
        }

        return "$current"
    }

    override fun choosePort(template: ServerTemplate): Int
    {
        var found = false
        var current = template.autoScalePortStart - 1

        while (!found)
        {
            current += 1
            found = !listening(
                "0.0.0.0",
                current, 10
            )
        }

        return current
    }
}
