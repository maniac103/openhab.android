/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.core.sitemap.SitemapPageRepository
import org.openhab.habdroid.model.Item
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.model.ServerProperties
import org.openhab.habdroid.model.Sitemap
import org.openhab.habdroid.model.Widget
import org.openhab.habdroid.model.sortedWithDefaultName
import org.openhab.habdroid.util.HttpClient
import org.openhab.habdroid.util.buildBaseSourceId
import org.openhab.habdroid.util.getConnectionFactory
import org.openhab.habdroid.util.getHumanReadableErrorMessage
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.isDebugModeEnabled
import org.openhab.habdroid.util.loadActiveServerConfig

class TvSessionViewModel(application: Application) :
    AndroidViewModel(application),
    SitemapPageRepository.Callback {

    sealed interface State {
        data object Loading : State
        data class Error(val message: String) : State
        data class NoSitemaps(val serverUrl: String?) : State
        data class SelectSitemap(val sitemaps: List<Sitemap>) : State
        data class Ready(val sitemap: Sitemap, val connection: Connection, val serverFlags: Int) : State
    }

    sealed interface PageState {
        data object Loading : PageState
        data class Loaded(val title: String?, val widgets: List<Widget>) : PageState
        data class Error(val message: String) : PageState
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    private val repository = SitemapPageRepository(viewModelScope)
    private var lastKnownServerConfig: ServerConfiguration? = null
    private var connection: Connection? = null
    private var sitemap: Sitemap? = null

    // Page data by URL, only for pages that currently have a back stack entry
    private val pageStates = mutableMapOf<String, MutableStateFlow<PageState>>()

    val state = _state.asStateFlow()

    init {
        repository.setCallback(this)
        lastKnownServerConfig = application.loadActiveServerConfig()
        viewModelScope.launch {
            application.getConnectionFactory().activeFlow.collectLatest { info -> onConnectionChanged(info.conn) }
        }
    }

    fun updateServerConfig(config: ServerConfiguration?) {
        lastKnownServerConfig = config
        val activeSitemap = sitemap
        if (activeSitemap != null && activeSitemap.name != config?.defaultSitemap?.name) {
            findDefaultSitemap()?.let { showSitemap(it) }
        }
    }

    fun start() {
        repository.start()
        val selectedSitemap = findDefaultSitemap() ?: return
        if (selectedSitemap.name != sitemap?.name) {
            showSitemap(selectedSitemap)
        }
    }

    fun stop() = repository.stop()


    /**
     * Show the given sitemap. The caller is responsible for persisting it as default sitemap.
     */
    fun selectSitemap(selectedSitemap: Sitemap) {
        showSitemap(selectedSitemap)
    }

    /**
     * Retry after a connection or server properties failure (see [State.Error] / [State.NoSitemaps]).
     * Page failures are retried via [refreshPage].
     */
    fun retry() {
        val conn = connection
        when {
            // The connection factory publishes a new connection if it's available now
            conn == null -> application.getConnectionFactory().restartNetworkCheck()
            serverProperties == null -> viewModelScope.launch {
                onConnectionChanged(ConnectionFactory.ConnectionResult(conn, null))
            }
            else -> pageStates.keys.forEach { refreshPage(it) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Page registration
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers a back stack entry for the given page URL and returns the flow of its content.
     * Must be paired with [releasePage] once the entry is gone for good.
     */
    fun acquirePage(url: String) =
        pageStates.getOrPut(url) { MutableStateFlow(PageState.Loading) }

    fun releasePage(url: String) {
        pageStates.remove(url)
    }

    /**
     * The page is on screen. Only visible pages are kept up to date, pages further down the back
     * stack are reloaded when they become visible again.
     */
    fun onPageShown(url: String) {
        updateTrackedPages()
        if (_state.value is State.Ready) {
            repository.triggerUpdate(url, false)
        }
    }

    fun onPageHidden(url: String) {
        updateTrackedPages()
    }

    fun refreshPage(url: String) {
        pageStates[url]?.update { current -> if (current is PageState.Error) PageState.Loading else current }
        repository.triggerUpdate(url, true)
    }

    fun sendCommand(pageUrl: String, item: Item?, command: String) {
        Log.d(TAG, "Send command $command to ${item?.name}")
        connection?.httpClient?.sendItemCommand(item, command, buildSourceId(pageUrl))
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private suspend fun onConnectionChanged(result: ConnectionFactory.ConnectionResult?) {
        Log.d(TAG, "onConnectionChanged($result)")
        val conn = result?.connection

        // Forget everything loaded from the previous connection
        connection = conn
        serverProperties = null
        sitemap = null
        resetPages()
        repository.updateActiveConnections(emptyList(), conn)

        if (conn == null) {
            val url = lastKnownServerConfig?.localPath?.url
            _state.value = State.Error(application.getString(R.string.tv_error_connection_failed, url.orEmpty()))
            return
        }

        _state.value = State.Loading

        when (val propsResult = ServerProperties.fetch(conn)) {
            is ServerProperties.Companion.PropsSuccess -> serverProperties = propsResult.props
            is ServerProperties.Companion.PropsFailure -> {
                _state.value = State.Error(
                    application.getHumanReadableErrorMessage(
                        propsResult.request.url.toString(),
                        propsResult.httpStatusCode,
                        propsResult.error,
                        false
                    ).toString()
                )
                return
            }
        }

        val sitemaps = serverProperties?.sitemaps.orEmpty()
        val selectedSitemap = findDefaultSitemap() ?: sitemaps.singleOrNull()
        when {
            selectedSitemap != null -> showSitemap(selectedSitemap)
            sitemaps.isEmpty() -> {
                _state.value = State.NoSitemaps(lastKnownServerConfig?.localPath?.url)
            }
            // Let the user choose instead of picking one of multiple sitemaps
            else -> _state.value = State.SelectSitemap(sitemaps.sortedWithDefaultName(""))
        }
    }

    /**
     * @return The configured default sitemap, or null if none is configured or it doesn't exist on the server
     */
    private fun findDefaultSitemap(): Sitemap? {
        val defaultSitemapName = lastKnownServerConfig?.defaultSitemap?.name ?: return null
        return serverProperties?.sitemaps?.firstOrNull { sitemap -> sitemap.name == defaultSitemapName }
    }

    private fun showSitemap(selectedSitemap: Sitemap) {
        val conn = connection ?: return
        sitemap = selectedSitemap
        resetPages()
        _state.value = State.Ready(selectedSitemap, conn, serverProperties?.flags ?: 0)
        // Pages that are already visible (e.g. the home page after a sitemap reload) need new data
        updateTrackedPages()
        pageStates.keys.forEach { url -> repository.triggerUpdate(url, false) }
    }

    /**
     * Drops all loaded page content, but keeps the flows, as their back stack entries still exist
     * until the navigation layer removes them.
     */
    private fun resetPages() {
        pageStates.values.forEach { flow -> flow.value = PageState.Loading }
    }

    private fun updateTrackedPages() {
        repository.updateActiveConnections(pageStates.keys.toList(), connection)
    }

    private fun buildSourceId(pageUrl: String): String {
        val pageId = pageUrl.substringAfterLast('/')
        return "org.openhab.ui.basic$${sitemap?.name}:$pageId=>${application.buildBaseSourceId()}"
    }

    // ---------------------------------------------------------------------------------------------
    // SitemapPageRepository.Callback
    // ---------------------------------------------------------------------------------------------

    override var serverProperties: ServerProperties? = null
        private set

    override val isDetailedLoggingEnabled get() = application.getPrefs().isDebugModeEnabled()

    override fun onPageUpdated(pageUrl: String, pageTitle: String?, widgets: List<Widget>) {
        pageStates[pageUrl]?.value = PageState.Loaded(pageTitle, widgets)
    }

    override fun onWidgetUpdated(pageUrl: String, widget: Widget) {
        pageStates[pageUrl]?.update { current ->
            if (current is PageState.Loaded) {
                current.copy(widgets = current.widgets.map { w -> if (w.id == widget.id) widget else w })
            } else {
                current
            }
        }
    }

    override fun onPageTitleUpdated(pageUrl: String, title: String) {
        pageStates[pageUrl]?.update { current ->
            if (current is PageState.Loaded) current.copy(title = title) else current
        }
    }

    override fun onLoadFailure(error: HttpClient.HttpException) {
        // The callback doesn't tell which page failed, so report it on all visible ones
        val message = application.getHumanReadableErrorMessage(error.originalUrl, error.statusCode, error, false)
            .toString()
        pageStates.values.forEach { state -> state.value = PageState.Error(message) }
    }

    override fun onSseFailure() {
        // The repository falls back to long polling
    }

    companion object {
        private val TAG = TvSessionViewModel::class.java.simpleName
    }
}
