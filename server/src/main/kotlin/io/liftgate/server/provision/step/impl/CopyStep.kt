package io.liftgate.server.provision.step.impl

import io.liftgate.server.autoscale.provision.AutoScalePropertyChoiceScheme
import io.liftgate.server.config
import io.liftgate.server.logger
import io.liftgate.server.models.server.ServerTemplate
import io.liftgate.server.provision.step.ServerProvisionStep
import io.liftgate.server.resource.ResourceHandler
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.RandomStringUtils
import java.io.File

/**
 * @author GrowlyX
 * @since 8/15/2022
 */
object CopyStep : ServerProvisionStep
{
    private val autoProvisionedServerDirectory =
        File(config.autoProvisionedServerDirectory)
            .apply {
                if (!this.exists())
                {
                    this.mkdirs()
                }
            }

    override fun runStep(
        template: ServerTemplate,
        uid: String?, port: Int?,
        temporaryMeta: MutableMap<String, String>
    )
    {
        if (uid == null || port == null)
        {
            val scheme = Class
                .forName(temporaryMeta["propertyScheme"])
                .kotlin.objectInstance as AutoScalePropertyChoiceScheme

            if (uid == null)
            {
                temporaryMeta["uid"] = scheme.chooseUid(template)
                    .apply {
                        logger.info("Chose uid: $this")
                    }
            }

            if (port == null)
            {
                temporaryMeta["port"] = scheme.choosePort(template).toString()
                    .apply {
                        logger.info("Chose port: $this")
                    }
            }
        }

        // UIDs may be based on servers that are not
        // provisioned automatically, so we're going to
        // create a random string to prevent conflicts.
        val subDirectory = File(
            autoProvisionedServerDirectory,
            "${RandomStringUtils.randomAlphanumeric(5)}-${
                if (temporaryMeta["uid"] == null) uid else temporaryMeta["uid"]
            }"
        )

        subDirectory.mkdirs()

        temporaryMeta["directory"] = subDirectory.absolutePath

        for (dependency in template.dependencies)
        {
            val mapping = ResourceHandler
                .findResourceByReference(dependency)
                ?: continue

            val assets =
                File(
                    mapping.templateDirectory
                )

            FileUtils
                .copyDirectory(
                    assets, subDirectory
                )
        }
    }
}
