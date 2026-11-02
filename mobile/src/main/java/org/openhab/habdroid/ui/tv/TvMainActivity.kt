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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.openhab.habdroid.R
import org.openhab.habdroid.model.ServerPath
import org.openhab.habdroid.util.getConnectionFactory
import org.openhab.habdroid.util.getPreferences

/**
 * Entry point of the app on TV devices. The TV UI only supports sitemaps and is fully usable with a D-pad.
 */
class TvMainActivity : ComponentActivity() {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeServerFlow = getPreferences().activeServer.asFlow()
        val serverPathFlow = activeServerFlow
            .flatMapLatest { it?.localPath?.asFlow() ?: flowOf(null) }
        val defaultSitemapFlow = activeServerFlow
            .flatMapLatest { it?.defaultSitemap?.asFlow() ?: flowOf(null) }

        setContent {
            TvTheme {
                val serverPath by serverPathFlow.collectAsStateWithLifecycle(null)
                val defaultSitemap by defaultSitemapFlow.collectAsStateWithLifecycle(null)

                TvApp(
                    serverPath = serverPath,
                    defaultSitemap = defaultSitemap,
                    connectionProvider = { getConnectionFactory().currentActive?.conn?.connection },
                    onSaveServerPath = { path ->
                        lifecycleScope.launch {
                            saveServer(path)
                        }
                    },
                    onSaveDefaultSitemap = { sitemap ->
                        lifecycleScope.launch {
                            getPreferences().activeServer.value?.defaultSitemap?.update(sitemap)
                        }
                    }
                )
            }
        }
    }

    private suspend fun saveServer(path: ServerPath) {
        val activeServer = getPreferences().activeServer.value
        if (activeServer != null) {
            activeServer.localPath.update(path)
        } else {
            getPreferences().createServer(getString(R.string.openhab), path, null)
        }
    }

    override fun onStart() {
        super.onStart()
        // Allows asking the user whether to trust an unknown certificate, otherwise the connection hangs forever
        getConnectionFactory().trustManager.bindDisplayActivity(this)
    }

    override fun onStop() {
        super.onStop()
        getConnectionFactory().trustManager.unbindDisplayActivity(this)
    }
}
