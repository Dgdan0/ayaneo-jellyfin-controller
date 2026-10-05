package com.pocketds.hub.ui

import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import java.lang.reflect.Proxy

/**
 * A stand-in hub for a page under test (#23): the answers the test gives, by
 * method name, and for any other read the failure a hub out of reach gives, so a
 * page that asks for more than the test cares about shows its own error state
 * rather than ending the test. No request leaves the device.
 */
object FixtureHub {
    fun of(vararg answers: Pair<String, (Array<Any?>) -> Any?>): HubApi {
        val table = answers.toMap()
        return Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, args ->
            val given = args ?: emptyArray()
            table[method.name]?.let { return@newProxyInstance it(given) }
            when {
                method.returnType == String::class.java -> ""
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == java.lang.Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                // A suspend read: the hub cannot be reached.
                given.lastOrNull() is kotlin.coroutines.Continuation<*> -> HubResult.Failed(FailureKind.NO_NETWORK, "fixture: no ${method.name}")
                else -> null
            }
        } as HubApi
    }
}
