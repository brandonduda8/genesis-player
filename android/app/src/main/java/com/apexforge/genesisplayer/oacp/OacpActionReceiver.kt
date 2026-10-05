package com.apexforge.genesisplayer.oacp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.apexforge.genesisplayer.PlayerService
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OACP v0.3 action endpoint for AURUM.
 *
 * Receives the broadcast intents declared in assets/oacp.json and drives
 * playback through the app's Media3 session (PlayerService), then reports
 * async results back on org.oacp.ACTION_RESULT with the v0.3 envelope.
 *
 * All six capabilities are playback-only (sensitivity low, confirmation
 * never): no purchase / delete / publish / account surface is reachable here.
 */
class OacpActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OACP"

        const val ACTION_RESUME = "com.apexforge.genesisplayer.oacp.ACTION_RESUME"
        const val ACTION_PAUSE = "com.apexforge.genesisplayer.oacp.ACTION_PAUSE"
        const val ACTION_NEXT = "com.apexforge.genesisplayer.oacp.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.apexforge.genesisplayer.oacp.ACTION_PREVIOUS"
        const val ACTION_PLAY_QUERY = "com.apexforge.genesisplayer.oacp.ACTION_PLAY_QUERY"
        const val ACTION_NOW_PLAYING = "com.apexforge.genesisplayer.oacp.ACTION_NOW_PLAYING"

        const val EXTRA_QUERY = "com.apexforge.genesisplayer.oacp.extra.QUERY"
        const val EXTRA_REQUEST_ID = "org.oacp.extra.REQUEST_ID"
        const val RESULT_ACTION = "org.oacp.ACTION_RESULT"
        const val EXTRA_RESULT = "org.oacp.extra.RESULT"
        // Namespaced keys the Hark voice assistant's result receiver reads
        // (HarkPlatformPlugin: org.oacp.extra.{STATUS,CAPABILITY_ID,MESSAGE,ERROR,SOURCE_PACKAGE}).
        const val EXTRA_STATUS = "org.oacp.extra.STATUS"
        const val EXTRA_CAPABILITY_ID = "org.oacp.extra.CAPABILITY_ID"
        const val EXTRA_MESSAGE = "org.oacp.extra.MESSAGE"
        const val EXTRA_ERROR = "org.oacp.extra.ERROR"
        const val EXTRA_SOURCE_PACKAGE = "org.oacp.extra.SOURCE_PACKAGE"

        // PlayerService custom session command (see PlayerService.ACTION_PLAY_IDS)
        private const val CMD_PLAY_IDS = "GENESIS_PLAY_IDS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                handle(context.applicationContext, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "OACP handle failed", t)
                try {
                    val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                        ?: UUID.randomUUID().toString()
                    sendResult(
                        context.applicationContext, requestId,
                        capabilityIdFor(intent.action), "failed",
                        "Something went wrong inside AURUM.", null,
                        "internal_error", t.message ?: "unknown"
                    )
                } catch (_: Throwable) { /* last resort: never crash the receiver */ }
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; name = "oacp-action" }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: UUID.randomUUID().toString()
        val action = intent.action ?: return
        val capabilityId = capabilityIdFor(action)

        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val controller = try {
            MediaController.Builder(context, token).buildAsync().get(15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            sendResult(context, requestId, capabilityId, "failed",
                "AURUM's player isn't reachable right now.", null,
                "internal_error", "media session unavailable")
            return
        }
        try {
            when (action) {
                ACTION_RESUME -> {
                    controller.play()
                    sendResult(context, requestId, capabilityId, "completed",
                        "Playback resumed in AURUM.", null)
                }
                ACTION_PAUSE -> {
                    controller.pause()
                    sendResult(context, requestId, capabilityId, "completed",
                        "Playback paused in AURUM.", null)
                }
                ACTION_NEXT -> {
                    if (controller.hasNextMediaItem()) controller.seekToNext()
                    sendResult(context, requestId, capabilityId, "completed",
                        "Skipped to the next track in AURUM.", null)
                }
                ACTION_PREVIOUS -> {
                    if (controller.hasPreviousMediaItem()) controller.seekToPrevious()
                    sendResult(context, requestId, capabilityId, "completed",
                        "Back to the previous track in AURUM.", null)
                }
                ACTION_PLAY_QUERY -> {
                    val query = intent.getStringExtra(EXTRA_QUERY).orEmpty().trim()
                    if (query.isEmpty()) {
                        sendResult(context, requestId, capabilityId, "failed",
                            "No song or artist was named.", null,
                            "missing_parameters", "query was empty")
                        return
                    }
                    val match = findTrack(context, query)
                    if (match == null) {
                        sendResult(context, requestId, capabilityId, "failed",
                            "No match for \"$query\" in the AURUM catalog.", null,
                            "not_found", "no catalog match")
                        return
                    }
                    val args = Bundle().apply {
                        putStringArrayList("ids", arrayListOf(match.id))
                        putInt("index", 0)
                    }
                    controller.sendCustomCommand(SessionCommand(CMD_PLAY_IDS, Bundle.EMPTY), args)
                        .get(15, TimeUnit.SECONDS)
                    val result = JSONObject()
                        .put("track_id", match.id)
                        .put("title", match.title)
                        .put("artist", match.artist)
                    sendResult(context, requestId, capabilityId, "completed",
                        "Playing ${match.title} by ${match.artist} in AURUM.", result)
                }
                ACTION_NOW_PLAYING -> {
                    val item = controller.currentMediaItem
                    val meta = item?.mediaMetadata
                    val title = meta?.title?.toString().orEmpty()
                    val artist = meta?.artist?.toString().orEmpty()
                    val playing = controller.isPlaying
                    val result = JSONObject()
                        .put("title", title)
                        .put("artist", artist)
                        .put("is_playing", playing)
                    val message = if (title.isEmpty()) "Nothing is playing in AURUM right now."
                    else "Now playing: $title by $artist (${if (playing) "playing" else "paused"})."
                    sendResult(context, requestId, capabilityId, "completed", message, result)
                }
            }
        } finally {
            controller.release()
        }
    }

    private fun capabilityIdFor(action: String?): String = when (action) {
        ACTION_RESUME -> "playback_resume"
        ACTION_PAUSE -> "playback_pause"
        ACTION_NEXT -> "playback_next"
        ACTION_PREVIOUS -> "playback_previous"
        ACTION_PLAY_QUERY -> "playback_play_query"
        ACTION_NOW_PLAYING -> "playback_now_playing"
        else -> "unknown"
    }

    private fun sendResult(
        context: Context,
        requestId: String,
        capabilityId: String,
        status: String,
        message: String,
        result: JSONObject?,
        errorCode: String? = null,
        errorMessage: String? = null
    ) {
        val envelope = JSONObject()
            .put("requestId", requestId)
            .put("status", status)
            .put("capabilityId", capabilityId)
            .put("message", message)
        if (result != null) envelope.put("result", result)
        if (errorCode != null) {
            envelope.put("error", JSONObject()
                .put("code", errorCode)
                .put("message", errorMessage ?: errorCode)
                .put("retryable", errorCode != "not_found"))
        }
        val out = Intent(RESULT_ACTION)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_RESULT, envelope.toString())
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_CAPABILITY_ID, capabilityId)
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_SOURCE_PACKAGE, context.packageName)
            .putExtra("requestId", requestId)
            .putExtra("status", status)
            .putExtra("capabilityId", capabilityId)
            .putExtra("message", message)
        if (errorMessage != null) out.putExtra(EXTRA_ERROR, errorMessage)
        context.sendBroadcast(out)
        Log.i(TAG, "OACP result: $capabilityId -> $status")
    }

    // ---- catalog matching ----

    private data class TrackHit(val id: String, val title: String, val artist: String)

    private fun findTrack(context: Context, query: String): TrackHit? {
        val q = query.lowercase()
        var best: TrackHit? = null
        var bestScore = 0
        try {
            val json = context.assets.open("tracks.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val title = o.optString("title")
                val artist = o.optString("artist")
                val t = title.lowercase()
                val a = artist.lowercase()
                val score = when {
                    t == q -> 100
                    t.startsWith(q) -> 80
                    a == q -> 75
                    t.contains(q) -> 60
                    a.startsWith(q) -> 50
                    a.contains(q) -> 40
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = TrackHit(id, title, artist)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OACP catalog read failed", t)
        }
        return best
    }
}
