package com.example.uploader

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CustomAdapter(
    val galleryFragment: GalleryFragment,
    private val dataSet: MutableList<GalleryFragment.GalleryItem> = mutableListOf()
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class ViewType {
        HEADING,
        IMAGE,
        VIDEO
    }

    class HeadingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val headingTitle: TextView = view.findViewById(R.id.headingTitle)
    }

    open class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbImage: ImageButton = view.findViewById(R.id.imageButton)

        val progressBar2: ProgressBar = view.findViewById(R.id.progressBar2)

        lateinit var fileId: String

        fun initialize(fileId: String) {
            this.fileId = fileId
            thumbImage.visibility = View.INVISIBLE
            progressBar2.visibility = View.VISIBLE
        }

        fun setThumbnail(img: Bitmap) {
            thumbImage.setImageBitmap(img)
            thumbImage.visibility = View.VISIBLE
            progressBar2.visibility = View.INVISIBLE
        }
    }

    class ImageFileViewHolder(view: View) : FileViewHolder(view) {

    }

    class VideoFileViewHolder(view: View) : FileViewHolder(view) {
        val durationText: TextView = view.findViewById(R.id.durationText)
    }

    override fun getItemViewType(position: Int): Int {
        val item = dataSet[position]
        return when (item) {
            is GalleryFragment.GalleryHeading -> ViewType.HEADING.ordinal
            is GalleryFragment.GalleryFile -> {
                when (item.fileType) {
                    GalleryFragment.FileType.IMAGE -> ViewType.IMAGE.ordinal
                    GalleryFragment.FileType.VIDEO -> ViewType.VIDEO.ordinal
                }
            }
        }
    }

    // Create new views (invoked by the layout manager)
    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        // Create a new view, which defines the UI of the list item
        return if (viewType == ViewType.HEADING.ordinal) {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.gallery_heading, viewGroup, false)

            HeadingViewHolder(view)
        } else if (viewType == ViewType.IMAGE.ordinal) {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.gallery_thumbnail, viewGroup, false)

            ImageFileViewHolder(view)
        } else if (viewType == ViewType.VIDEO.ordinal) {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.gallery_thumbnail_video, viewGroup, false)

            VideoFileViewHolder(view)
        } else {
            throw IllegalArgumentException("Unable to identify viewType")
        }
    }

    // Replace the contents of a view (invoked by the layout manager)
    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        // this is getting called multiple times when scrolling up and down

        val item = dataSet[position]
        when (item) {
            is GalleryFragment.GalleryFile -> {
                viewHolder as FileViewHolder

                viewHolder.initialize(item.id)
                // TODO i dont think it should pass viewHolder here, since this view might be deleted by the time it updates
                galleryFragment.retrieveThumbnail(item.id, viewHolder)

                if (item.fileType == GalleryFragment.FileType.IMAGE) {
                    viewHolder.thumbImage.setOnClickListener {
                        galleryFragment.imageThumbnailClicked(item.id)
                    }
                } else if (item.fileType == GalleryFragment.FileType.VIDEO) {
                    viewHolder.thumbImage.setOnClickListener {
                        galleryFragment.videoThumbnailClicked(item.id)
                    }

                    viewHolder as VideoFileViewHolder

                    val duration = galleryFragment.getVideoDuration(item.id) ?: 0.toDouble()
                    viewHolder.durationText.text = duration.toString()
                }
            }

            is GalleryFragment.GalleryHeading -> {
                (viewHolder as HeadingViewHolder).headingTitle.text = item.text
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is FileViewHolder) {
            galleryFragment.cancelGetThumbnail(holder.fileId)
        }
    }

    override fun getItemCount() = dataSet.size

    fun addItems(newItems: Collection<GalleryFragment.GalleryItem>) {
        val startPosition = dataSet.size
        dataSet.addAll(newItems)
        notifyItemRangeInserted(startPosition, newItems.size)
    }

    fun clearItems() {
        val size = dataSet.size
        dataSet.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getItem(position: Int): GalleryFragment.GalleryItem {
        return dataSet[position]
    }
}