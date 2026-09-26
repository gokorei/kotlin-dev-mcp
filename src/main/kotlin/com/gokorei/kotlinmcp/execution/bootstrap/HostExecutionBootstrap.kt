package com.gokorei.kotlinmcp.execution.bootstrap

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

internal object HostExecutionBootstrap {
    init {
        Runtime.getRuntime().addShutdownHook(Thread { destroyDescendants() })
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val setupError = executeTarget(args)
        if (setupError == null) return
        System.err.println(BOOTSTRAP_ERROR_MARKER)
        System.err.println(setupError)
        System.err.flush()
        Runtime.getRuntime().halt(BOOTSTRAP_SETUP_EXIT_CODE)
    }

    @Suppress("ReturnCount")
    private fun executeTarget(args: Array<String>): String? {
        if (args.size != 2) {
            return "Expected target class and readiness token, received ${args.size} argument(s)"
        }
        val targetClass =
            try {
                Class.forName(args[0], false, ClassLoader.getSystemClassLoader())
            } catch (e: ReflectiveOperationException) {
                return "Could not load target class '${args[0]}': ${e.describe()}"
            }
        val mainMethod =
            try {
                targetClass.getMethod("main", Array<String>::class.java)
            } catch (_: NoSuchMethodException) {
                try {
                    targetClass.getMethod("main")
                } catch (e: ReflectiveOperationException) {
                    return "Target class '${args[0]}' exposes no main method: ${e.describe()}"
                }
            }
        println(args[1])
        System.out.flush()
        if (System.`in`.read() != PROCESS_START_SIGNAL) {
            return "Missing execution start signal for '${args[0]}'"
        }
        return try {
            if (mainMethod.parameterCount == 1) {
                mainMethod.invoke(null, arrayOf<String>())
            } else {
                mainMethod.invoke(null)
            }
            null
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause.isIsolationInfrastructureFailure()) {
                "Target '${args[0]}' could not be executed in the isolated JVM: ${cause.describe()}"
            } else {
                throw e
            }
        } catch (e: ReflectiveOperationException) {
            "Target '${args[0]}' could not be invoked: ${e.describe()}"
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

    private fun Throwable.describe(): String = "${javaClass.name}: ${message ?: "no detail"}"

    private fun Throwable.isIsolationInfrastructureFailure(): Boolean =
        when (this) {
            is NoClassDefFoundError,
            is ClassNotFoundException,
            is ExceptionInInitializerError,
            is UnsatisfiedLinkError,
            is NoSuchMethodError,
            is NoSuchFieldError,
            -> true
            else -> false
        }

    private const val PROCESS_START_SIGNAL = 1
}

internal const val BOOTSTRAP_SETUP_EXIT_CODE = 2
internal const val BOOTSTRAP_ERROR_MARKER = "[[kmcp-bootstrap-error]]"
