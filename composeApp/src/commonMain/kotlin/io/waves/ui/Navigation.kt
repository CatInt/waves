package io.waves.ui

import androidx.compose.runtime.mutableStateListOf
import io.waves.model.Podcast

/** Bottom navigation / rail order. [Home] is the app's entry point. */
enum class Tab { Home, Discover, History, Settings }

sealed interface Screen {
    data class Root(val tab: Tab) : Screen
    /** [autoFetch]: refresh the feed on open (used when coming from search / charts). */
    data class PodcastDetail(val podcast: Podcast, val autoFetch: Boolean) : Screen
    data object NowPlaying : Screen
}

/**
 * Minimal state-based navigation: one back stack, rooted at the selected tab. Going back from the root of any
 * tab other than [start] moves one tab towards [start], matching a single page swipe (the Android edge-back
 * gesture doubles as one); only at [start] is there nothing left to go back to.
 */
class Navigator(private val start: Tab = Tab.Home) {
    private val stack = mutableStateListOf<Screen>(Screen.Root(start))

    val current: Screen get() = stack.last()
    /** The screen shown beneath the player sheet: [current], unless that is [Screen.NowPlaying]. */
    val content: Screen get() = stack.last { it != Screen.NowPlaying }
    val currentTab: Tab get() = (stack.first() as Screen.Root).tab
    val canGoBack: Boolean get() = stack.size > 1 || currentTab != start
    val backStack: List<Screen> get() = stack.toList()

    /** Switch tab; re-selecting the current tab pops back to its root. */
    fun select(tab: Tab) {
        stack.clear()
        stack.add(Screen.Root(tab))
    }

    fun push(screen: Screen) {
        if (stack.last() == screen) return
        if (screen == Screen.NowPlaying) stack.remove(Screen.NowPlaying)
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (currentTab == start) return false
        val step = if (currentTab.ordinal > start.ordinal) -1 else 1
        select(Tab.entries[currentTab.ordinal + step])
        return true
    }
}
