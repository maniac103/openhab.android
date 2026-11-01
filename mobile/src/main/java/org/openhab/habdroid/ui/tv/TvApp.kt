/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.habdroid.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import kotlinx.serialization.Serializable
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.model.DefaultSitemap
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.model.ServerPath

sealed interface TvRoute : NavKey {
    @Serializable
    object Setup : TvRoute
    @Serializable
    object SitemapHome: TvRoute
    @Serializable
    data class SitemapPage(val pageUrl: String) : TvRoute
    @Serializable
    object Settings : TvRoute
    @Serializable
    object About : TvRoute
}

@Composable
fun TvApp(
    config: ServerConfiguration?,
    connectionProvider: () -> Connection?,
    onSaveServerPath: (ServerPath) -> Unit,
    onSaveDefaultSitemap: (DefaultSitemap) -> Unit
) {
    val session: TvSessionViewModel = viewModel()
    val backStack = rememberNavBackStack(
        if (config == null) TvRoute.Setup else TvRoute.SitemapHome
    )

    LaunchedEffect(config) {
        session.updateServerConfig(config)
    }
    LifecycleStartEffect(session) {
        session.start()
        onStopOrDispose { session.stop() }
    }

    // Sitemap or connection changed -> drop pages that belong to the old one
    val state by session.state.collectAsStateWithLifecycle()
    val ready = state as? TvSessionViewModel.State.Ready
    LaunchedEffect(ready?.sitemap?.name, ready?.connection) {
        backStack.removeAll { it is TvRoute.SitemapPage }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
    ) {
        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<TvRoute.Setup> {
                    TvSetupScreen(
                        onSavePath = { path ->
                            onSaveServerPath(path)
                            backStack.clear()
                            backStack += TvRoute.SitemapHome
                        }
                    )
                }
                entry<TvRoute.SitemapHome> {
                    TvSitemapRoot(
                        session = session,
                        onSaveDefaultSitemap = onSaveDefaultSitemap,
                        onOpenPage = { backStack += TvRoute.SitemapPage(it.link) },
                        onOpenSettings = { backStack += TvRoute.Settings }
                    )
                }
                entry<TvRoute.SitemapPage> { key ->
                    TvSitemapPage(
                        pageUrl = key.pageUrl,
                        fallbackTitle = null,
                        session = session,
                        onOpenPage = { backStack += TvRoute.SitemapPage(it.link) },
                        onOpenSettings = { backStack += TvRoute.Settings }
                    )
                }
                entry<TvRoute.Settings> {
                    TvSettingsScreen(
                        config = config,
                        connectionProvider = connectionProvider,
                        onSavePath = onSaveServerPath,
                        onSaveDefaultSitemap = onSaveDefaultSitemap,
                        onOpenAbout = { backStack += TvRoute.About }
                    )
                }
                entry<TvRoute.About> {
                    TvAboutScreen()
                }
            }
        )
    }
}
