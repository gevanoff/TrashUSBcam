package com.gevanoff.trashcam

import org.junit.Assert.*
import org.junit.Test

class LiveEndpointTest {
    @Test fun acceptsHttpsOrigins() {
        assertTrue(LiveStream.validEndpoint("https://live.example.com"))
        assertTrue(LiveStream.validEndpoint("https://live.example.com:8443/"))
    }
    @Test fun rejectsInsecureOrAmbiguousDestinations() {
        listOf("http://live.example.com", "https://user:secret@live.example.com",
            "https://live.example.com/path", "https://live.example.com?key=value",
            "https://live.example.com/#token", "https://", "live.example.com")
            .forEach { assertFalse(it, LiveStream.validEndpoint(it)) }
    }
}
