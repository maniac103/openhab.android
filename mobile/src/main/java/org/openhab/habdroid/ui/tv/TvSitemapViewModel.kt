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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.openhab.habdroid.model.Item

class TvSitemapViewModel(
    private val pageUrl: String,
    private val session: TvSessionViewModel
) : ViewModel() {
    val state: StateFlow<TvSessionViewModel.PageState> = session.acquirePage(pageUrl)
    var focusedWidgetId: String? = null          // survives while the entry is on the stack

    fun onShown() = session.onPageShown(pageUrl)
    fun onHidden() = session.onPageHidden(pageUrl)
    fun retry() = session.refreshPage(pageUrl)

    fun sendCommand(item: Item?, command: String) = session.sendCommand(pageUrl, item, command)

    override fun onCleared() = session.releasePage(pageUrl)
}
