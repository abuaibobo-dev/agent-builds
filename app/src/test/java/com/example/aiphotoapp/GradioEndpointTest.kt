package com.example.aiphotoapp

import org.junit.Assert.assertEquals
import org.junit.Test

class GradioEndpointTest {
    @Test
    fun removesLeadingSlashFromNamedEndpoint() {
        assertEquals("infer", GradioEndpoint.normalize("/infer"))
    }
}
