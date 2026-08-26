package com.gokorei.kotlinmcp.doc

import com.gokorei.kotlinmcp.models.FrameworkFeature
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameworkFeatureCatalogTest {

    @Test
    fun `featureDocs contains documentation for all framework features including ANDROID`() {
        for (feature in FrameworkFeature.values()) {
            val doc = FrameworkFeatureCatalog.featureDocs[feature]
            assertNotNull(doc, "Missing documentation in FrameworkFeatureCatalog for $feature")
            assertTrue(doc!!.isNotBlank(), "Documentation for $feature should not be blank")
        }

        val androidDoc = FrameworkFeatureCatalog.featureDocs[FrameworkFeature.ANDROID]!!
        assertTrue(androidDoc.contains("Jetpack Compose"))
        assertTrue(androidDoc.contains("collectAsStateWithLifecycle"))
        assertTrue(androidDoc.contains("viewModelScope"))
        assertTrue(androidDoc.contains("enableEdgeToEdge"))
    }
}
