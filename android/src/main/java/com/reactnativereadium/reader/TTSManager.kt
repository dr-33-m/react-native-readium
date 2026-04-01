package com.reactnativereadium.reader

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.readium.navigator.media.common.MediaNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Language

@OptIn(ExperimentalReadiumApi::class)
class TTSManager(
    private val application: Application,
    private val publication: Publication,
    private val scope: CoroutineScope,
    private val onStateChange: (isPlaying: Boolean, isPaused: Boolean, rate: Float) -> Unit,
    private val onUtterance: (locator: Locator, text: String) -> Unit,
    private val onError: (message: String) -> Unit
) : TtsNavigator.Listener {
    private var navigator: AndroidTtsNavigator? = null
    private var factory: AndroidTtsNavigatorFactory? = null
    private var observeJob: Job? = null
    private var currentRate: Float = 1.0f

    fun isRunning(): Boolean = navigator != null

    override fun onStopRequested() {
        stop()
    }

    suspend fun start(
        rate: Float? = null,
        language: String? = null,
        voice: String? = null,
        fromLocator: Locator? = null
    ) {
        if (navigator != null) {
            // Already running, just play
            navigator?.play()
            return
        }

        rate?.let { currentRate = it.coerceIn(0.25f, 4.0f) }

        try {
            val ttsFactory = factory ?: run {
                val f = TtsNavigatorFactory(application, publication)
                if (f == null) {
                    onError("TTS is not available for this publication")
                    return
                }
                factory = f
                f
            }

            val voicesMap: Map<Language, AndroidTtsEngine.Voice.Id> =
                if (voice != null && language != null) {
                    mapOf(Language(language) to AndroidTtsEngine.Voice.Id(voice))
                } else {
                    emptyMap()
                }

            val navigatorResult = ttsFactory.createNavigator(
                listener = this,
                initialLocator = fromLocator,
                initialPreferences = AndroidTtsPreferences(
                    speed = currentRate.toDouble(),
                    language = language?.let { Language(it) },
                    voices = voicesMap
                )
            )

            if (navigatorResult.isFailure) {
                onError("Failed to create TTS navigator: ${navigatorResult.failureOrNull()}")
                return
            }

            val ttsNavigator = navigatorResult.getOrNull()!!
            navigator = ttsNavigator
            observeNavigator(ttsNavigator)

            ttsNavigator.play()

        } catch (e: Exception) {
            onError("Failed to start TTS: ${e.message}")
        }
    }

    private fun observeNavigator(ttsNavigator: AndroidTtsNavigator) {
        observeJob?.cancel()
        observeJob = scope.launch {
            // Observe playback state
            ttsNavigator.playback
                .onEach { playback ->
                    val isPlaying = playback.playWhenReady && playback.state is MediaNavigator.State.Ready
                    val isPaused = !playback.playWhenReady && playback.state is MediaNavigator.State.Ready
                    onStateChange(isPlaying, isPaused, currentRate)
                }
                .launchIn(this)

            // Observe current locator
            ttsNavigator.currentLocator
                .onEach { locator ->
                    val text = locator.text.highlight ?: ""
                    onUtterance(locator, text)
                }
                .launchIn(this)
        }
    }

    fun pause() {
        navigator?.pause()
    }

    fun resume() {
        navigator?.play()
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        navigator?.close()
        navigator = null
        onStateChange(false, false, currentRate)
    }

    fun setRate(rate: Float) {
        currentRate = rate.coerceIn(0.25f, 4.0f)
        navigator?.submitPreferences(
            AndroidTtsPreferences(speed = currentRate.toDouble())
        )
    }

    fun skipNext() {
        navigator?.skipToNextUtterance()
    }

    fun skipPrevious() {
        navigator?.skipToPreviousUtterance()
    }

    fun cleanup() {
        stop()
        factory = null
    }
}
