package com.example.uploader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.navigation.fragment.findNavController
import com.example.uploader.databinding.FragmentVideoViewerBinding
import com.example.uploader.toolbar.VideoToolbarInterface
import java.io.File

class VideoViewerFragment : Fragment(), VideoToolbarInterface {

    private lateinit var fileId: String

    private var _binding: FragmentVideoViewerBinding? = null
    private val binding get() = _binding!!

    private var exoPlayer: ExoPlayer? = null

    companion object {
        private const val ARG_FILE_ID = "image_url"

        fun newInstance(fileId: String): VideoViewerFragment {
            val fragment = VideoViewerFragment()
            val args = Bundle()
            args.putString(ARG_FILE_ID, fileId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoViewerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fileId = arguments?.getString(ARG_FILE_ID) ?: throw IllegalStateException("File id is null")
        binding.toolbar.setInterface(this)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.release()
        exoPlayer = null
    }

    @OptIn(UnstableApi::class)
    fun buildCachedMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to SessionManager(requireContext()).authHeader()))

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(VideoCacheManager.getCache(context))
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return DefaultMediaSourceFactory(cacheDataSourceFactory)
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(requireContext()).setMediaSourceFactory(
            buildCachedMediaSourceFactory(requireContext())
        ).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
        }
        // If the URI already arrived before the player existed, load it now
        val session = SessionManager(requireContext())
        loadVideo(session.baseUrl + "/media/" + arguments?.getString(ARG_FILE_ID))
    }

    private fun loadVideo(uri: String) {
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    //TODO this needs to be tested and connected to a button in the GUI
    fun openVideoExternally(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "Open video with")

        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackClicked() {
        findNavController().popBackStack()
    }

    override fun onDetailsClicked() {
        TODO("Not yet implemented")
    }

    override fun onEditClicked() {
        TODO("Not yet implemented")
    }

    override fun onDownloadClicked() {
        StorageManager.saveFileToStorage(requireContext(), fileId)
    }
}