/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.home

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.menu.BrowserMenuBuilder
import mozilla.components.browser.menu.BrowserMenuHighlight
import mozilla.components.browser.menu.ext.getHighlight
import mozilla.components.browser.menu.item.BrowserMenuDivider
import mozilla.components.browser.menu.item.BrowserMenuHighlightableItem
import mozilla.components.browser.menu.item.BrowserMenuImageSwitch
import mozilla.components.browser.menu.item.BrowserMenuImageText
import mozilla.components.browser.menu.item.BrowserMenuItemToolbar
import mozilla.components.browser.menu.item.SimpleBrowserMenuItem
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.support.ktx.android.content.getColorFromAttr
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.theme.ThemeManager

interface HomeMenu {
    sealed class Item {
        object WhatsNew : Item()
        object Help : Item()
        object AddonsManager : Item()
        object Settings : Item()
        object SyncedTabs : Item()
        object History : Item()
        object Bookmarks : Item()
        object Downloads : Item()
        object Quit : Item()
        object Sync : Item()
        data class Back(val viewHistory: Boolean) : Item()
        data class Forward(val viewHistory: Boolean) : Item()
        data class RequestDesktop(val isChecked: Boolean) : Item()
    }

    val menuToolbar: BrowserMenuItemToolbar
}

class DefaultHomeMenu(
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val store: BrowserStore,
    private val onItemTapped: (HomeMenu.Item) -> Unit = {},
    private val onMenuBuilderChanged: (BrowserMenuBuilder) -> Unit = {},
    private val onHighlightPresent: (BrowserMenuHighlight) -> Unit = {}
) : HomeMenu {

    private val primaryTextColor =
        ThemeManager.resolveAttribute(R.attr.primaryText, context)
    private val syncDisconnectedColor = ThemeManager.resolveAttribute(R.attr.syncDisconnected, context)
    private val syncDisconnectedBackgroundColor = context.getColorFromAttr(R.attr.syncDisconnectedBackground)

    private val shouldUseBottomToolbar = context.settings().shouldUseBottomToolbar

    private val selectedSession: TabSessionState? get() = store.state.selectedTab

    override val menuToolbar by lazy {
        val back = BrowserMenuItemToolbar.TwoStateButton(
            primaryImageResource = mozilla.components.ui.icons.R.drawable.mozac_ic_back,
            primaryContentDescription = context.getString(R.string.browser_menu_back),
            primaryImageTintResource = primaryTextColor,
            isInPrimaryState = {
                selectedSession?.content?.canGoBack ?: true
            },
            secondaryImageTintResource = ThemeManager.resolveAttribute(
                R.attr.disabled,
                context
            ),
            disableInSecondaryState = true,
            longClickListener = { onItemTapped.invoke(HomeMenu.Item.Back(viewHistory = true)) }
        ) {
            onItemTapped.invoke(HomeMenu.Item.Back(viewHistory = false))
        }

        val forward = BrowserMenuItemToolbar.TwoStateButton(
            primaryImageResource = mozilla.components.ui.icons.R.drawable.mozac_ic_forward,
            primaryContentDescription = context.getString(R.string.browser_menu_forward),
            primaryImageTintResource = primaryTextColor,
            isInPrimaryState = {
                selectedSession?.content?.canGoForward ?: true
            },
            secondaryImageTintResource = ThemeManager.resolveAttribute(
                R.attr.disabled,
                context
            ),
            disableInSecondaryState = true,
            longClickListener = { onItemTapped.invoke(HomeMenu.Item.Forward(viewHistory = true)) }
        ) {
            onItemTapped.invoke(HomeMenu.Item.Forward(viewHistory = false))
        }

        BrowserMenuItemToolbar(listOf(back, forward))
    }

    // 'Reconnect' and 'Quit' items aren't needed most of the time, so we'll only create the if necessary.
    private val reconnectToSyncItem by lazy {
        BrowserMenuHighlightableItem(
            context.getString(R.string.sync_reconnect),
            R.drawable.ic_sync_disconnected,
            iconTintColorResource = syncDisconnectedColor,
            textColorResource = primaryTextColor,
            highlight = BrowserMenuHighlight.HighPriority(
                backgroundTint = syncDisconnectedBackgroundColor,
                canPropagate = false
            ),
            isHighlighted = { true }
        ) {
            onItemTapped.invoke(HomeMenu.Item.Sync)
        }
    }

    private val quitItem by lazy {
        BrowserMenuImageText(
            context.getString(R.string.delete_browsing_data_on_quit_action),
            R.drawable.ic_exit,
            primaryTextColor
        ) {
            onItemTapped.invoke(HomeMenu.Item.Quit)
        }
    }

    private val coreMenuItems by lazy {
        val whatsNewItem = SimpleBrowserMenuItem(
            context.getString(R.string.browser_menu_whats_new)
        ) {
            onItemTapped.invoke(HomeMenu.Item.WhatsNew)
        }

        val bookmarksItem = SimpleBrowserMenuItem(
            context.getString(R.string.library_bookmarks)
        ) {
            onItemTapped.invoke(HomeMenu.Item.Bookmarks)
        }

        val historyItem = SimpleBrowserMenuItem(
            context.getString(R.string.library_history)
        ) {
            onItemTapped.invoke(HomeMenu.Item.History)
        }

        val addons = SimpleBrowserMenuItem(
            context.getString(R.string.browser_menu_add_ons)
        ) {
            onItemTapped.invoke(HomeMenu.Item.AddonsManager)
        }

        val settingsItem = SimpleBrowserMenuItem(
            context.getString(R.string.browser_menu_settings)
        ) {
            onItemTapped.invoke(HomeMenu.Item.Settings)
        }

        val syncedTabsItem = SimpleBrowserMenuItem(
            context.getString(R.string.library_synced_tabs)
        ) {
            onItemTapped.invoke(HomeMenu.Item.SyncedTabs)
        }

        val helpItem = SimpleBrowserMenuItem(
            context.getString(R.string.browser_menu_help)
        ) {
            onItemTapped.invoke(HomeMenu.Item.Help)
        }

        val downloadsItem = SimpleBrowserMenuItem(
            context.getString(R.string.library_downloads)
        ) {
            onItemTapped.invoke(HomeMenu.Item.Downloads)
        }

        val desktopMode = BrowserMenuImageSwitch(
            imageResource = R.drawable.ic_desktop,
            label = context.getString(R.string.browser_menu_desktop_site),
            initialState = {
                selectedSession?.content?.desktopMode ?: false
            }
        ) { checked ->
            onItemTapped.invoke(HomeMenu.Item.RequestDesktop(checked))
        }

        // Only query account manager if it has been initialized.
        // We don't want to cause its initialization just for this check.
        val accountAuthItem = if (context.components.backgroundServices.accountManagerAvailableQueue.isReady()) {
            if (context.components.backgroundServices.accountManager.accountNeedsReauth()) reconnectToSyncItem else null
        } else {
            null
        }

        val settings = context.components.settings

        val menuItems = listOfNotNull(
            menuToolbar,
            BrowserMenuDivider(),
            if (settings.shouldDeleteBrowsingDataOnQuit) quitItem else null,
            settingsItem,
            helpItem,
            whatsNewItem,
            BrowserMenuDivider(),
            desktopMode,
            BrowserMenuDivider(),
            if (settings.syncedTabsInTabsTray) null else syncedTabsItem,
            addons,
            downloadsItem,
            historyItem,
            bookmarksItem,
            accountAuthItem
        ).also { items ->
            items.getHighlight()?.let { onHighlightPresent(it) }
        }

        if (shouldUseBottomToolbar) {
            menuItems.reversed()
        } else {
            menuItems
        }
    }

    init {
        // Report initial state.
        onMenuBuilderChanged(BrowserMenuBuilder(coreMenuItems))

        // Observe account state changes, and update menu item builder with a new set of items.
        context.components.backgroundServices.accountManagerAvailableQueue.runIfReadyOrQueue {
            // This task isn't relevant if our parent fragment isn't around anymore.
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                return@runIfReadyOrQueue
            }
            context.components.backgroundServices.accountManager.register(object : AccountObserver {
                override fun onAuthenticationProblems() {
                    lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        onMenuBuilderChanged(BrowserMenuBuilder(
                            listOf(reconnectToSyncItem) + coreMenuItems
                        ))
                    }
                }

                override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
                    lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        onMenuBuilderChanged(
                            BrowserMenuBuilder(
                                coreMenuItems
                            )
                        )
                    }
                }

                override fun onLoggedOut() {
                    lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        onMenuBuilderChanged(
                            BrowserMenuBuilder(
                                coreMenuItems
                            )
                        )
                    }
                }
            }, lifecycleOwner)
        }
    }
}
