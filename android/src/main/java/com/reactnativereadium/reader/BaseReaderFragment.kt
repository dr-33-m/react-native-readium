package com.reactnativereadium.reader

import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.reactnativereadium.utils.EventChannel
import com.reactnativereadium.utils.LinkOrLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.positions
import java.io.File
import java.io.FileOutputStream

/*
 * Base reader fragment class
 *
 * Provides common menu items and saves last location on stop.
 */
abstract class BaseReaderFragment : Fragment() {
  val channel = EventChannel(
    Channel<ReaderViewModel.Event>(Channel.BUFFERED),
    lifecycleScope
  )

  protected abstract val model: ReaderViewModel
  protected abstract val navigator: Navigator

  // TTS
  private var ttsManager: TTSManager? = null

  // True while the user has an active text selection.
  // applyDecorations injects JS into the EPUB WebView; doing so while the user
  // is dragging selection handles causes the selection to jump or reset.
  protected var isUserSelecting = false

  // Bounded polling job for selection tracking — runs only while an ActionMode is active.
  private var selectionPollingJob: Job? = null

  // Track active decoration listeners to avoid duplicates
  private val activeDecorationGroups = mutableSetOf<String>()

  // Store decorations if they're set before navigator is ready
  private var pendingDecorations: Map<String, List<Decoration>>? = null

  // Check if navigator is ready to use
  private val isNavigatorReady: Boolean
    get() {
      if (view == null) return false
      return try {
        navigator
        true
      } catch (e: UninitializedPropertyAccessException) {
        false
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    setHasOptionsMenu(true)
    super.onCreate(savedInstanceState)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val viewScope = viewLifecycleOwner.lifecycleScope

    // Emit PublicationReady event with all metadata
    viewScope.launch {
      val positions = try {
        model.publication.positions()
      } catch (e: Exception) {
        emptyList<Locator>()
      }

      val coverPath: String? = try {
        val bitmap = model.publication.cover()
        if (bitmap != null) {
          val coversDir = File(requireContext().filesDir, "covers").also { it.mkdirs() }
          val coverFile = File(coversDir, "${model.bookId}.jpg")
          FileOutputStream(coverFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
          "file://${coverFile.absolutePath}"
        } else null
      } catch (e: Exception) {
        null
      }

      channel.send(
        ReaderViewModel.Event.PublicationReady(
          tableOfContents = model.publication.tableOfContents,
          positions = positions,
          metadata = model.publication.metadata,
          coverPath = coverPath
        )
      )
    }

    navigator.currentLocator
      .onEach { channel.send(ReaderViewModel.Event.LocatorUpdate(it)) }
      .launchIn(viewScope)

    // Apply any pending decorations now that navigator is ready
    pendingDecorations?.let { applyDecorations(it) }

    // Selection detection is handled via ActionMode callbacks in EpubReaderFragment,
    // not polling — see onSelectionActionModeCreated/Destroyed.
  }

  override fun onHiddenChanged(hidden: Boolean) {
    super.onHiddenChanged(hidden)
    setMenuVisibility(!hidden)
    requireActivity().invalidateOptionsMenu()
  }

  fun go(location: LinkOrLocator, animated: Boolean): Boolean {
    // Check if navigator is initialized
    if (!isNavigatorReady) {
      android.util.Log.w("BaseReaderFragment", "Navigator not initialized yet")
      return false
    }

    var locator: Locator? = null
    when (location) {
      is LinkOrLocator.Link -> {
        locator = model.publication.locatorFromLink(location.link)
      }
      is LinkOrLocator.Locator -> {
        locator = location.locator
      }
    }

    if (locator == null) {
      return false
    }

    // don't attempt to navigate if we're already there
    val currentLocator = navigator.currentLocator.value
    if (locator.hashCode() == currentLocator.hashCode()) {
      return true
    }

    return navigator.go(locator, animated)
  }

  fun goForward(): Boolean {
    if (!isNavigatorReady) return false
    val overflowNav = navigator as? OverflowableNavigator ?: return false
    return overflowNav.goForward(animated = true)
  }

  fun goBackward(): Boolean {
    if (!isNavigatorReady) return false
    val overflowNav = navigator as? OverflowableNavigator ?: return false
    return overflowNav.goBackward(animated = true)
  }

  /**
   * Apply pre-converted Readium decoration groups directly (no JSON round-trip).
   */
  fun applyDecorations(groups: Map<String, List<Decoration>>?) {
    if (groups == null) {
      pendingDecorations = null
      return
    }

    // Check if navigator is initialized
    if (!isNavigatorReady) {
      pendingDecorations = groups
      return
    }

    val decorableNavigator = navigator as? DecorableNavigator
    if (decorableNavigator == null) {
      android.util.Log.w("BaseReaderFragment", "Navigator does not support decorations")
      return
    }

    val viewScope = viewLifecycleOwner.lifecycleScope

    groups.forEach { (group, decorations) ->
      viewScope.launch {
        decorableNavigator.applyDecorations(decorations, group)
      }

      // Set up listener for this group if not already active
      if (!activeDecorationGroups.contains(group)) {
        activeDecorationGroups.add(group)
        setupDecorationListener(decorableNavigator, group)
      }
    }

    // Clear pending decorations as they've been applied
    pendingDecorations = null
  }

  /**
   * Set up a decoration listener for a specific group
   */
  private fun setupDecorationListener(decorableNavigator: DecorableNavigator, group: String) {
    val viewScope = viewLifecycleOwner.lifecycleScope

    decorableNavigator.addDecorationListener(group, object : DecorableNavigator.Listener {
      override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
        viewScope.launch {
          channel.send(
            ReaderViewModel.Event.DecorationActivated(
              decoration = event.decoration,
              group = event.group,
              rect = event.rect,
              point = event.point
            )
          )
        }
        return true
      }
    })
  }

  // MARK: - TTS

  fun ttsStart(rate: Float?, language: String?, voice: String?) {
    val viewScope = viewLifecycleOwner.lifecycleScope
    if (ttsManager == null) {
      val app = requireActivity().application
      ttsManager = TTSManager(
        application = app,
        publication = model.publication,
        scope = viewScope,
        onStateChange = { isPlaying, isPaused, r ->
          viewScope.launch {
            channel.send(ReaderViewModel.Event.TTSStateChanged(isPlaying, isPaused, r))
          }
        },
        onUtterance = { locator, text ->
          applyTTSDecoration(locator) // Apply decoration natively — no JS round-trip
          viewScope.launch {
            channel.send(ReaderViewModel.Event.TTSUtterance(locator, text, null, null))
          }
        },
        onError = { message ->
          viewScope.launch {
            channel.send(ReaderViewModel.Event.TTSError(message))
          }
        }
      )
    }
    viewScope.launch {
      // Wait for the EPUB navigator to finish navigating to initialLocation
      // before reading the visible element locator. Without this delay,
      // currentLocator may still point to the resource start when the book
      // is first opened from a saved position.
      if (ttsManager?.isRunning() != true) delay(400)
      val startLocator = if (isNavigatorReady) {
        (navigator as? VisualNavigator)?.firstVisibleElementLocator()
          ?: navigator.currentLocator.value
      } else null
      ttsManager?.start(rate = rate, language = language, voice = voice, fromLocator = startLocator)
    }
  }

  fun ttsStop() {
    ttsManager?.stop()
    clearTTSDecoration()
  }
  fun ttsPause() { ttsManager?.pause() }
  fun ttsResume() { ttsManager?.resume() }
  fun ttsSetRate(rate: Float) { ttsManager?.setRate(rate) }
  fun ttsSkipNext() { ttsManager?.skipNext() }
  fun ttsSkipPrevious() { ttsManager?.skipPrevious() }

  private fun applyTTSDecoration(locator: Locator) {
    if (!isNavigatorReady) return
    if (isUserSelecting) return  // skip DOM update while user drags selection handles
    val decorableNavigator = navigator as? DecorableNavigator ?: return
    // applyDecorations is suspend in Readium 3.x — must be called from a coroutine.
    // viewLifecycleOwner.lifecycleScope dispatches to Dispatchers.Main, matching
    // the observeWhenStarted pattern used by the Readium test app.
    viewLifecycleOwner.lifecycleScope.launch {
      decorableNavigator.applyDecorations(
        listOf(
          Decoration(
            id = "tts",
            locator = locator,
            style = Decoration.Style.Highlight(
              tint = android.graphics.Color.argb(89, 0xF2, 0xCA, 0x50) // #f2ca50 @ 35%
            )
          )
        ),
        "tts"
      )
    }
  }

  private fun clearTTSDecoration() {
    if (!isNavigatorReady) return
    val decorableNavigator = navigator as? DecorableNavigator ?: return
    viewLifecycleOwner.lifecycleScope.launch {
      decorableNavigator.applyDecorations(emptyList(), "tts")
    }
  }

  override fun onDestroyView() {
    selectionPollingJob?.cancel()
    selectionPollingJob = null
    clearTTSDecoration()
    ttsManager?.cleanup()
    ttsManager = null
    super.onDestroyView()
  }

  /**
   * Called by [EpubReaderFragment] when the WebView's ActionMode is created
   * (i.e. the user has started a text selection). Starts a bounded polling loop
   * that samples [SelectableNavigator.currentSelection] every 500ms and emits
   * [SelectionChanged] events so the React Native side can track handle-drag
   * adjustments and show the highlight menu with the correct locator.
   *
   * Polling is scoped to the ActionMode lifecycle (started here, cancelled in
   * [onSelectionActionModeDestroyed]) so it never runs in the background.
   * The 500ms initial delay lets the long-press gesture fully settle before
   * the first JavaScript query, avoiding disruption to the initial selection.
   *
   * Note: `onPrepareActionMode` is not called during handle drags and
   * `onSelectionEnd` (Readium JS bridge) only fires when selection collapses —
   * neither can replace polling for tracking mid-selection adjustments.
   */
  fun onSelectionActionModeCreated() {
    isUserSelecting = true
    selectionPollingJob?.cancel()
    selectionPollingJob = viewLifecycleOwner.lifecycleScope.launch {
      var previousLocator: Locator? = null
      // Wait for long-press gesture to finish before first JS query.
      delay(500)
      while (true) {
        if (isNavigatorReady) {
          val sel = try {
            (navigator as? SelectableNavigator)?.currentSelection()
          } catch (e: Exception) { null }
          val locator = sel?.locator
          val text = locator?.text?.highlight
          val hasChanged = when {
            previousLocator == null && locator == null -> false
            previousLocator == null || locator == null -> true
            previousLocator.href != locator.href -> true
            previousLocator.text.highlight != text -> true
            else -> false
          }
          if (hasChanged) {
            channel.send(
              ReaderViewModel.Event.SelectionChanged(
                locator = locator,
                selectedText = text
              )
            )
            previousLocator = locator
          }
        }
        delay(500)
      }
    }
  }

  /**
   * Called by [EpubReaderFragment] when the WebView's ActionMode is destroyed
   * (i.e. the user taps away or the selection is dismissed). Cancels the
   * polling job and notifies the React Native side that selection is cleared.
   */
  fun onSelectionActionModeDestroyed() {
    isUserSelecting = false
    selectionPollingJob?.cancel()
    selectionPollingJob = null
    viewLifecycleOwner.lifecycleScope.launch {
      channel.send(
        ReaderViewModel.Event.SelectionChanged(
          locator = null,
          selectedText = null
        )
      )
    }
  }

}
