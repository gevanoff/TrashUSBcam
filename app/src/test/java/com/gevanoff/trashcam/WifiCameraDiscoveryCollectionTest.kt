package com.gevanoff.trashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiCameraDiscoveryCollectionTest {
    @Test
    fun firstCameraIsSelectedAutomatically() {
        val collection = WifiCameraDiscoveryCollection()

        val snapshot = collection.upsert(camera("one"))

        assertEquals(listOf("one"), snapshot.cameras.map { it.id })
        assertEquals("one", snapshot.selectedId)
    }

    @Test
    fun laterDiscoveriesDoNotInterruptSelection() {
        val collection = WifiCameraDiscoveryCollection()
        collection.upsert(camera("one"))

        val snapshot = collection.upsert(camera("two"))

        assertEquals(listOf("one", "two"), snapshot.cameras.map { it.id })
        assertEquals("one", snapshot.selectedId)
    }

    @Test
    fun updatingCameraPreservesOrderAndSelection() {
        val collection = WifiCameraDiscoveryCollection()
        collection.upsert(camera("one"))
        collection.upsert(camera("two"))
        collection.select("two")

        val snapshot = collection.upsert(camera("two", name = "Updated camera"))

        assertEquals(listOf("one", "two"), snapshot.cameras.map { it.id })
        assertEquals("two", snapshot.selectedId)
        assertEquals("Updated camera", snapshot.selectedCamera?.name)
    }

    @Test
    fun removingSelectionFallsBackToNextCamera() {
        val collection = WifiCameraDiscoveryCollection()
        collection.upsert(camera("one"))
        collection.upsert(camera("two"))

        val snapshot = collection.remove("one")

        assertEquals("two", snapshot.selectedId)
        assertEquals("two", snapshot.selectedCamera?.id)
    }

    @Test
    fun unknownSelectionDoesNotClearCurrentCamera() {
        val collection = WifiCameraDiscoveryCollection()
        collection.upsert(camera("one"))

        val snapshot = collection.select("missing")

        assertEquals("one", snapshot.selectedId)
    }

    @Test
    fun clearRemovesSelectionAndCandidates() {
        val collection = WifiCameraDiscoveryCollection()
        collection.upsert(camera("one"))

        val snapshot = collection.clear()

        assertEquals(emptyList<WifiCameraDiscoveryCollection.Camera>(), snapshot.cameras)
        assertNull(snapshot.selectedId)
        assertNull(snapshot.selectedCamera)
    }

    @Test
    fun pickerLabelIncludesDistinctNetworkName() {
        val candidate = camera("one", name = "Ear camera", networkName = "Scope-123")

        assertEquals("Ear camera — Scope-123 (192.168.1.1)", candidate.pickerLabel)
    }

    private fun camera(
        id: String,
        name: String = "Camera $id",
        networkName: String? = null
    ) = WifiCameraDiscoveryCollection.Camera(
        id = id,
        name = name,
        address = "192.168.1.1",
        networkName = networkName
    )
}
