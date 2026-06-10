package com.gevanoff.trashcam

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MediaThumbnailAdapter(
    private val contentResolver: ContentResolver,
    private val onItemClick: (GalleryItem) -> Unit
) : RecyclerView.Adapter<MediaThumbnailAdapter.MediaViewHolder>() {

    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private var items: List<GalleryItem> = emptyList()
    private var selectedUri: Uri? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media_thumbnail, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position], items[position].uri == selectedUri)
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    fun submitItems(nextItems: List<GalleryItem>) {
        items = nextItems
        if (selectedUri != null && items.none { it.uri == selectedUri }) {
            selectedUri = null
        }
        notifyDataSetChanged()
    }

    fun setSelected(uri: Uri?) {
        val oldUri = selectedUri
        if (oldUri == uri) {
            return
        }
        selectedUri = uri
        val oldIndex = items.indexOfFirst { it.uri == oldUri }
        val newIndex = items.indexOfFirst { it.uri == uri }
        if (oldIndex >= 0) {
            notifyItemChanged(oldIndex)
        }
        if (newIndex >= 0 && newIndex != oldIndex) {
            notifyItemChanged(newIndex)
        }
    }

    fun shutdown() {
        thumbnailExecutor.shutdownNow()
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnail_image)
        private val videoBadge: ImageView = itemView.findViewById(R.id.video_badge)

        fun bind(item: GalleryItem, selected: Boolean) {
            itemView.isSelected = selected
            itemView.contentDescription = item.displayName
            itemView.setOnClickListener { onItemClick(item) }
            videoBadge.visibility = if (item.kind == GalleryKind.Video) View.VISIBLE else View.GONE
            thumbnailImage.tag = item.uri
            thumbnailImage.setImageResource(placeholderFor(item))
            loadThumbnail(item)
        }

        fun clear() {
            thumbnailImage.tag = null
            thumbnailImage.setImageDrawable(null)
            itemView.setOnClickListener(null)
        }

        private fun loadThumbnail(item: GalleryItem) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return
            }

            thumbnailExecutor.execute {
                val bitmap = try {
                    contentResolver.loadThumbnail(
                        item.uri,
                        Size(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX),
                        null
                    )
                } catch (error: Exception) {
                    null
                }

                thumbnailImage.post {
                    if (thumbnailImage.tag == item.uri) {
                        if (bitmap != null) {
                            thumbnailImage.setImageBitmap(bitmap)
                        } else {
                            thumbnailImage.setImageResource(placeholderFor(item))
                        }
                    } else {
                        bitmap?.recycle()
                    }
                }
            }
        }

        private fun placeholderFor(item: GalleryItem): Int {
            return if (item.kind == GalleryKind.Video) {
                R.drawable.ic_videocam_24
            } else {
                R.drawable.ic_photo_camera_24
            }
        }
    }

    companion object {
        private const val THUMBNAIL_SIZE_PX = 192
    }
}
