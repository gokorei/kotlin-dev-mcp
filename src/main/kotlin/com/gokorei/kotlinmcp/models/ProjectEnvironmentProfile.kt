package com.gokorei.kotlinmcp.models

enum class FrameworkFeature(val id: String) {
    KTOR("ktor"),
    SPRING("spring"),
    COMPOSE("compose"),
    ARROW("arrow"),
    SERIALIZATION("serialization"),
    MOCKK("mockk"),
    COROUTINES("coroutines"),
    TURBINE("turbine"),
    DATETIME("datetime"),
    EXPOSED("exposed"),
    ROOM("room"),
    ANDROID("android")
}

data class ProjectEnvironmentProfile(
    val activeFrameworks: Set<FrameworkFeature> = emptySet(),
    val isKmp: Boolean = false
) {
    val isAndroid: Boolean get() = hasFramework(FrameworkFeature.ANDROID)

    fun hasFramework(feature: FrameworkFeature): Boolean = feature in activeFrameworks

    companion object {
        val ALL: ProjectEnvironmentProfile = ProjectEnvironmentProfile(FrameworkFeature.entries.toSet())
        val NONE: ProjectEnvironmentProfile = ProjectEnvironmentProfile(emptySet())
    }
}
