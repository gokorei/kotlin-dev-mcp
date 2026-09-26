package com.gokorei.kotlinmcp.execution.bootstrap

import java.util.concurrent.TimeUnit

internal object HostExecutionBootstrap {
    init {
        Runtime.getRuntime().addShutdownHook(Thread { destroyDescendants() })
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected target class and readiness token" }
        val targetClass = Class.forName(args[0], false, ClassLoader.getSystemClassLoader())
        val mainMethod =
            try {
                targetClass.getMethod("main", Array<String>::class.java)
            } catch (_: NoSuchMethodException) {
                targetClass.getMethod("main")
            }
        println(args[1])
        System.out.flush()
        check(System.`in`.read() == PROCESS_START_SIGNAL) { "Missing execution start signal" }
        if (mainMethod.parameterCount == 1) {
            mainMethod.invoke(null, arrayOf<String>())
        } else {
            mainMethod.invoke(null)
        }
    }

    private fun destroyDescendants() {
        val descendants =
            runCatching { ProcessHandle.current().descendants().toList() }.getOrDefault(emptyList())
        descendants.forEach { it.destroyForcibly() }
        descendants.forEach { descendant ->
            runCatching { descendant.onExit().get(1, TimeUnit.SECONDS) }
        }
    }

    private const val PROCESS_START_SIGNAL = 1
}
