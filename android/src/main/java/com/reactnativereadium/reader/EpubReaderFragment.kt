/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.reactnativereadium.reader

import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.reactnativereadium.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.navigator.preferences.Theme

data class SelectionAction(
    val id: String,
    val label: String
)

class EpubReaderFragment : VisualReaderFragment() {

    override lateinit var model: ReaderViewModel
    override lateinit var navigator: Navigator
    private lateinit var publication: Publication
    lateinit var navigatorFragment: EpubNavigatorFragment
    private lateinit var factory: ReaderViewModel.Factory
    private lateinit var navigatorFactory: EpubNavigatorFactory
    private var pendingPreferences: EpubPreferences? = null

    private lateinit var userPreferences: EpubPreferences

    // Accessibility
    private var isExploreByTouchEnabled = false

    // Selection actions configuration
    private var selectionActions: List<SelectionAction> = emptyList()

    // When true, the native Copy/Select-all/Share toolbar is suppressed in favour
    // of the custom React Native selection bar.
    var suppressNativeSelectionMenu: Boolean = false

    // Custom selection action mode callback for adding custom action buttons
    val customSelectionActionModeCallback: ActionMode.Callback by lazy {
        SelectionActionModeCallback()
    }

    private fun ensureUserPreferencesInitialized() {
      if (this::userPreferences.isInitialized) return
      userPreferences = pendingPreferences ?: EpubPreferences()
    }

    private fun applyPendingPreferencesIfNeeded() {
      if (!this::navigator.isInitialized) return
      pendingPreferences?.let { updatePreferences(it) }
    }

    fun initFactory(
      publication: Publication,
      initialLocation: Locator?,
      bookId: String
    ) {
      factory = ReaderViewModel.Factory(
        publication,
        initialLocation,
        bookId
      )
      navigatorFactory = EpubNavigatorFactory(publication)
    }

    fun updatePreferences(epubPreferences: EpubPreferences) {
      userPreferences = epubPreferences

      if (this::navigator.isInitialized && navigator is EpubNavigatorFragment) {
        (navigator as EpubNavigatorFragment).submitPreferences(userPreferences)
        pendingPreferences = null

        // Update position label color to match theme, similar to iOS implementation
        updatePositionLabelColor()
      } else {
        pendingPreferences = epubPreferences
      }
    }

    private fun updatePositionLabelColor() {
      // Priority 1: Use explicit textColor if set
      val color = userPreferences.textColor?.int
      // Priority 2: Use theme's content color
      ?: userPreferences.theme?.contentColor
      // Priority 3: Default to dark gray
      ?: android.graphics.Color.DKGRAY

      setPositionLabelColor(color)
    }

    fun updateSelectionActions(actions: List<SelectionAction>) {
      selectionActions = actions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
      check(::navigatorFactory.isInitialized) { "EpubReaderFragment factory was not initialized" }

        ViewModelProvider(this, factory)
          .get(ReaderViewModel::class.java)
          .let {
            model = it
            publication = it.publication
          }

          ensureUserPreferencesInitialized()

          childFragmentManager.fragmentFactory =
            navigatorFactory.createFragmentFactory(
              initialLocator = model.initialLocation,
              initialPreferences = userPreferences,
              configuration = EpubNavigatorFragment.Configuration {
                when {
                  suppressNativeSelectionMenu -> selectionActionModeCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        menu.clear()
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                    override fun onDestroyActionMode(mode: ActionMode) {}
                  }
                  selectionActions.isNotEmpty() -> selectionActionModeCallback = customSelectionActionModeCallback
                }
              }
            )

        setHasOptionsMenu(true)

        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        val navigatorFragmentTag = getString(R.string.epub_navigator_tag)

        if (savedInstanceState == null) {
            childFragmentManager.commitNow {
                add(R.id.fragment_reader_container, EpubNavigatorFragment::class.java, Bundle(), navigatorFragmentTag)
            }
        }
        navigator = childFragmentManager.findFragmentByTag(navigatorFragmentTag) as Navigator
        navigatorFragment = navigator as EpubNavigatorFragment

        applyPendingPreferencesIfNeeded()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set initial position label color based on current preferences
        updatePositionLabelColor()

        // Inject selection/caret color CSS on every page render so it survives chapter navigation
        navigatorFragment.currentLocator
            .onEach {
                delay(150)
                navigatorFragment.evaluateJavascript(SELECTION_COLOR_JS)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    companion object {
        fun newInstance(): EpubReaderFragment = EpubReaderFragment()

        // Gold primary color: #f2ca50 — matches both app themes (dark = #f2ca50, light ≈ same hue)
        private const val SELECTION_COLOR_JS = """
            (function(){
                var id='__app_sel_style__';
                if(document.getElementById(id))return;
                var s=document.createElement('style');
                s.id=id;
                s.textContent='::selection{background-color:rgba(242,202,80,0.35)!important;}:root,:root *{caret-color:#f2ca50!important;}';
                (document.head||document.documentElement).appendChild(s);
            })();
        """
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity()

        ensureUserPreferencesInitialized()
        applyPendingPreferencesIfNeeded()

        // If TalkBack or any touch exploration service is activated we force scroll mode (and
        // override user preferences)
        val am = activity.getSystemService(AppCompatActivity.ACCESSIBILITY_SERVICE) as AccessibilityManager
        isExploreByTouchEnabled = am.isTouchExplorationEnabled

        userPreferences = if (isExploreByTouchEnabled) {
            userPreferences.plus(EpubPreferences(scroll = true))
        } else {
            userPreferences.plus(EpubPreferences(scroll = null))
        }
        (navigator as? EpubNavigatorFragment)?.submitPreferences(userPreferences)
    }

    private inner class SelectionActionModeCallback : ActionMode.Callback {
        // Store action IDs mapped to their menu item IDs for lookup
        private val actionIdMap = mutableMapOf<Int, String>()

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // Clear previous action mappings
            actionIdMap.clear()

            // Only add menu items if navigator supports decorations
            if (navigator !is DecorableNavigator) {
                return true
            }

            // Dynamically add menu items for each configured action
            selectionActions.forEachIndexed { index, action ->
                // Generate a unique menu item ID using the action's hash
                val menuItemId = action.id.hashCode()
                actionIdMap[menuItemId] = action.id

                // Add menu item with the action's label
                menu.add(Menu.NONE, menuItemId, index, action.label).apply {
                    // Show as action button if there's space
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    // Use star icon for highlight action, otherwise use default
                    if (action.id == "highlight") {
                        setIcon(android.R.drawable.btn_star_big_on)
                    }
                }
            }

            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val actionId = actionIdMap[item.itemId]
            if (actionId != null) {
                // Get the current selection from the navigator
                lifecycleScope.launch {
                    val selectableNavigator = navigator as? SelectableNavigator
                    val selection = selectableNavigator?.currentSelection()

                    if (selection != null) {
                        // Emit generic SelectionAction event to React Native
                        channel.send(
                            ReaderViewModel.Event.SelectionAction(
                                actionId = actionId,
                                locator = selection.locator,
                                selectedText = selection.locator.text.highlight ?: ""
                            )
                        )
                    }
                }

                mode.finish()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            // Clean up action mappings
            actionIdMap.clear()
        }
    }

}
