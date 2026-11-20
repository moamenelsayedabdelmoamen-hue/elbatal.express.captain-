package com.example.splash

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays the bundled silent intro once per cold application launch, then opens the
 * existing MainActivity. Existing AppNavigation and Firebase/session routing remain unchanged.
 */
@UnstableApi
class SplashActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CaptainVideoSplash"
        private const val KEY_POSITION = "captain_splash_position"
        private const val KEY_NAVIGATED = "captain_splash_navigated"
        private const val TIMEOUT_MS = 12_000L
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var watchdog: Job? = null
    private var playbackPosition = 0L
    private val navigated = AtomicBoolean(false)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finishSplash()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Intro playback failed; opening existing app flow.", error)
            finishSplash()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CaptainSplashSession.completed || savedInstanceState?.getBoolean(KEY_NAVIGATED) == true) {
            openMain()
            return
        }

        playbackPosition = savedInstanceState?.getLong(KEY_POSITION) ?: CaptainSplashSession.position
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(90, 0, 12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val view = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.TRANSPARENT)
            setKeepContentOnPlayerReset(true)
            keepScreenOn = true
            isClickable = false
            isFocusable = false
            contentDescription = getString(R.string.splash_video_content_description)
        }
        root.addView(view)
        setContentView(root)
        playerView = view
        startPlayer()
        watchdog = lifecycleScope.launch {
            delay(TIMEOUT_MS)
            if (!navigated.get()) {
                Log.w(TAG, "Splash watchdog timeout; continue into app.")
                finishSplash()
            }
        }
    }

    private fun startPlayer() {
        if (navigated.get() || player != null) return
        try {
            val videoUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(packageName)
                .appendPath(R.raw.intro.toString())
                .build()

            val exo = ExoPlayer.Builder(this).build().apply {
                volume = 0f // Force silent playback; device volume is never modified.
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(listener)
                setMediaItem(MediaItem.fromUri(videoUri))
                if (playbackPosition > 0L) seekTo(playbackPosition)
                playWhenReady = true
                prepare()
            }
            player = exo
            playerView?.player = exo
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to initialize intro video.", t)
            finishSplash()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!navigated.get()) {
            if (player == null && playerView != null) startPlayer()
            else player?.playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!navigated.get()) player?.play()
    }

    override fun onPause() {
        player?.let {
            playbackPosition = it.currentPosition.coerceAtLeast(0L)
            CaptainSplashSession.position = playbackPosition
            it.pause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        playbackPosition = player?.currentPosition?.coerceAtLeast(0L) ?: playbackPosition
        outState.putLong(KEY_POSITION, playbackPosition)
        outState.putBoolean(KEY_NAVIGATED, navigated.get())
        super.onSaveInstanceState(outState)
    }

    private fun finishSplash() {
        if (!navigated.compareAndSet(false, true)) return
        CaptainSplashSession.completed = true
        CaptainSplashSession.position = 0L
        watchdog?.cancel()
        watchdog = null
        openMain()
    }

    private fun openMain() {
        if (!navigated.compareAndSet(false, true) && !CaptainSplashSession.completed) return
        watchdog?.cancel()
        player?.let {
            it.removeListener(listener)
            playerView?.player = null
            it.release()
        }
        player = null
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        watchdog?.cancel()
        player?.let {
            it.removeListener(listener)
            playerView?.player = null
            it.release()
        }
        player = null
        playerView = null
        super.onDestroy()
    }
}

/** Process-scoped guard: replay only after a new cold process launch. */
private object CaptainSplashSession {
    @Volatile var completed: Boolean = false
    @Volatile var position: Long = 0L
}
