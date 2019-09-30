/*
 * Copyright (c) 2010-2019 Contributors to the openHAB project
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

package org.openhab.habdroid.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication

import org.openhab.habdroid.background.BackgroundTasksManager
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.util.getDayNightMode
import org.openhab.habdroid.util.getPrefs

@Suppress("UNUSED")
class OpenHabApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(getPrefs().getDayNightMode(this))
        ConnectionFactory.initialize(this)
        BackgroundTasksManager.initialize(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        ConnectionFactory.shutdown()
    }
}
