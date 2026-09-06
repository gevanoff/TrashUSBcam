package com.gevanoff.trashcam

/**
 * Ordered, device-neutral view of Wi-Fi cameras reported by discovery providers.
 *
 * The first camera is selected automatically. Later discoveries do not interrupt an active
 * selection, and removing the selected camera falls back to the next available candidate.
 */
internal class WifiCameraDiscoveryCollection {
    data class Camera(
        val id: String,
        val name: String,
        val address: String,
        val networkName: String? = null
    ) {
        val pickerLabel: String
            get() = networkName
                ?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
                ?.let { "$name — $it ($address)" }
                ?: "$name ($address)"
    }

    data class Snapshot(
        val cameras: List<Camera>,
        val selectedId: String?
    ) {
        val selectedCamera: Camera?
            get() = cameras.firstOrNull { it.id == selectedId }
    }

    private val cameras = linkedMapOf<String, Camera>()
    private var selectedId: String? = null

    fun upsert(camera: Camera): Snapshot {
        require(camera.id.isNotBlank()) { "Camera id cannot be blank" }
        require(camera.name.isNotBlank()) { "Camera name cannot be blank" }
        require(camera.address.isNotBlank()) { "Camera address cannot be blank" }
        cameras[camera.id] = camera
        if (selectedId !in cameras) selectedId = camera.id
        return snapshot()
    }

    fun remove(cameraId: String): Snapshot {
        cameras.remove(cameraId)
        if (selectedId !in cameras) selectedId = cameras.keys.firstOrNull()
        return snapshot()
    }

    fun select(cameraId: String): Snapshot {
        if (cameraId in cameras) selectedId = cameraId
        return snapshot()
    }

    fun clear(): Snapshot {
        cameras.clear()
        selectedId = null
        return snapshot()
    }

    fun snapshot(): Snapshot = Snapshot(cameras.values.toList(), selectedId)
}
