package dev.liftgate.domain

import java.util.Hashtable
import javax.naming.Context
import javax.naming.NameNotFoundException
import javax.naming.directory.InitialDirContext

fun txtRecords(name: String): List<String> = try {
    InitialDirContext(Hashtable(mapOf(Context.INITIAL_CONTEXT_FACTORY to "com.sun.jndi.dns.DnsContextFactory")))
        .getAttributes(name, arrayOf("TXT")).get("TXT")?.all?.toList().orEmpty()
        .map { it.toString().replace("\"", "") }
} catch (e: NameNotFoundException) {
    emptyList()
}
