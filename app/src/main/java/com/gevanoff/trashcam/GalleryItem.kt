package com.gevanoff.trashcam

import android.net.Uri

data class GalleryItem(
    val uri: Uri,
    val kind: GalleryKind,
    val displayName: String,
    val mimeType: String,
    val dateAddedSeconds: Long
)

enum class GalleryKind {
    Photo,
    Video
}
