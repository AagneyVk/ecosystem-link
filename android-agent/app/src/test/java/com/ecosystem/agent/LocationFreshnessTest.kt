package com.ecosystem.agent

import com.ecosystem.agent.capabilities.LocationFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFreshnessTest {
    @Test fun freshAndStaleFixesAreDistinct() {
        assertFalse(LocationFreshness.isStale(100_000, 80_000))
        assertTrue(LocationFreshness.isStale(100_000, 60_000))
    }
}
