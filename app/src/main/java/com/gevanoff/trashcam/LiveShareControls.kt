package com.gevanoff.trashcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.gevanoff.trashcam.databinding.FragmentCameraBinding

/** View-scoped sharing UI. Never captures the gallery, controls, or other applications. */
internal class LiveShareControls(
    private val context: Context,
    private val views: FragmentCameraBinding,
    private val snapshot: () -> Bitmap?,
    private val orientation: () -> Pair<Int, Boolean>
) {
    private val handler = Handler(Looper.getMainLooper())
    private var stream: LiveStream? = null
    private var link: String? = null
    private var resumed = false
    private var destroyed = false
    private var dialog: AlertDialog? = null
    private val sampler = object : Runnable {
        override fun run() {
            val current = stream
            if (current != null && resumed && !destroyed) {
                val bitmap = try { snapshot() } catch (_: Exception) { null }
                current.setEnabled(bitmap != null)
                views.liveShareStatus.setText(if (bitmap == null) R.string.live_waiting_camera else R.string.live_sharing)
                if (bitmap != null) {
                    val (turns, mirrored) = orientation()
                    current.submit(bitmap, turns, mirrored)
                }
            }
            if (current != null && resumed && !destroyed) handler.postDelayed(this, 150)
        }
    }

    init { views.liveShareButton.setOnClickListener { showControls() } }

    private fun showControls() {
        dialog?.dismiss()
        if (stream != null) {
            dialog = AlertDialog.Builder(context).setTitle(R.string.live_title)
                .setMessage(R.string.live_running_help)
                .setPositiveButton(R.string.live_send_link) { _, _ -> shareLink() }
                .setNegativeButton(R.string.live_stop) { _, _ -> stop() }
                .setNeutralButton(android.R.string.cancel, null).show()
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = link != null
            return
        }
        val prefs = context.getSharedPreferences("live_sharing", Context.MODE_PRIVATE)
        val endpoint = EditText(context).apply {
            hint = context.getString(R.string.live_server_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(); setText(prefs.getString("endpoint", ""))
        }
        val key = EditText(context).apply {
            hint = context.getString(R.string.live_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            // Publisher credentials intentionally stay out of saved view state and backups.
            isSaveEnabled = false
        }
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(endpoint); addView(key)
        }
        val setup = AlertDialog.Builder(context).setTitle(R.string.live_title)
            .setMessage(R.string.live_setup_help).setView(fields)
            .setPositiveButton(R.string.live_start, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog = setup
        setup.setOnShowListener {
            setup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = endpoint.text.toString().trim().trimEnd('/')
                if (!LiveStream.validEndpoint(url)) { endpoint.error = context.getString(R.string.live_server_error); return@setOnClickListener }
                if (key.text.length < 32) { key.error = context.getString(R.string.live_key_error); return@setOnClickListener }
                prefs.edit().putString("endpoint", url).apply()
                start(url, key.text.toString()); key.text.clear(); setup.dismiss()
            }
        }
        setup.show()
    }

    private fun start(endpoint: String, key: String) {
        views.liveShareButton.isSelected = true
        views.liveShareButton.contentDescription = context.getString(R.string.live_manage)
        views.liveShareStatus.visibility = View.VISIBLE
        views.liveShareStatus.setText(R.string.live_connecting)
        stream = LiveStream(context, endpoint, key, onReady = { url ->
            if (!destroyed) {
                link = url
                Toast.makeText(context, R.string.live_link_ready, Toast.LENGTH_LONG).show()
                // Sending is always an explicit user action, after the link exists.
                showControls()
            }
        }, onEnded = { message ->
            if (!destroyed) {
                stream = null; link = null
                handler.removeCallbacks(sampler)
                views.liveShareButton.isSelected = false
                views.liveShareButton.contentDescription = context.getString(R.string.live_title)
                views.liveShareStatus.visibility = View.GONE
                dialog?.dismiss()
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        })
        if (resumed) handler.post(sampler)
    }

    private fun shareLink() {
        val url = link ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.live_share_message, url))
        }
        try { context.startActivity(Intent.createChooser(intent, context.getString(R.string.live_send_link))) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, R.string.live_no_share_app, Toast.LENGTH_LONG).show() }
    }

    fun resume() {
        resumed = true
        handler.removeCallbacks(sampler)
        if (stream != null) handler.post(sampler)
    }

    fun pause() {
        resumed = false
        handler.removeCallbacks(sampler)
        stream?.setEnabled(false)
    }

    private fun stop() { stream?.close() }

    fun destroy() {
        destroyed = true; pause(); stop(); stream = null; link = null
        dialog?.dismiss(); dialog = null
    }
}
