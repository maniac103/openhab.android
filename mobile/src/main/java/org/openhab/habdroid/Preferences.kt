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

package org.openhab.habdroid

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.openhab.habdroid.model.ServerPath
import org.openhab.habdroid.util.PrefKeys
import org.openhab.habdroid.util.toNormalizedUrl

interface Preference<T> {
    val value: T
    fun asFlow(): Flow<T>
}

interface WriteablePreference<T> : Preference<T> {
    suspend fun update(newValue: T)
    suspend fun reset()
}

class Preferences(private val prefs: SharedPreferences, private val secretPrefs: SharedPreferences) {
    private val activeServerId = intPreference(prefs, PrefKeys.ACTIVE_SERVER_ID, 0)
    private val primaryServerId = intPreference(prefs, PrefKeys.PRIMARY_SERVER_ID, 0)
    private val configuredServerIds = stringSetPreference(prefs, PrefKeys.SERVER_IDS, null)

    val activeServer: Preference<Server?> = TransformingPreferenceImpl(
        activeServerId,
        { id -> if (id != 0) Server(activeServerId, prefs, secretPrefs) else null }
    )
    val primaryServer: Preference<Server?> = TransformingPreferenceImpl(
        primaryServerId,
        { id -> if (id != 0) Server(primaryServerId, prefs, secretPrefs) else null }
    )

    val configuredServers: Preference<List<Server>> = TransformingPreferenceImpl(configuredServerIds) { idStrings ->
        idStrings
            ?.map { idString ->
                val id = idString.toInt()
                Server(constantValuePreference(id), prefs, secretPrefs)
            }
            ?.toList()
            ?: emptyList()
    }

    suspend fun createServer(name: String, localPath: ServerPath?, remotePath: ServerPath?): Server {
        val configuredServers = configuredServerIds.value
        val nextAvailableId = configuredServers?.maxOfOrNull { it.toInt() } ?: 1
        val server = Server(constantValuePreference(nextAvailableId), prefs, secretPrefs)
        server.name.update(name)
        server.localPath.update(localPath)
        server.remotePath.update(remotePath)

        val newServers = configuredServers?.toMutableSet()?.apply { add(nextAvailableId.toString()) }
            ?: mutableSetOf(nextAvailableId.toString())
        configuredServerIds.update(newServers)
        if (configuredServers.isNullOrEmpty()) {
            activeServerId.update(nextAvailableId)
            primaryServerId.update(nextAvailableId)
        }

        return server
    }

    class Server internal constructor(val id: Preference<Int>, prefs: SharedPreferences, secretPrefs: SharedPreferences) {
        val name: WriteablePreference<String?> = serverConfigStringPreference(prefs, id, PrefKeys.SERVER_NAME_PREFIX, null)

        val localPath: WriteablePreference<ServerPath?> = serverPathPreference(
            prefs,
            secretPrefs,
            id,
            PrefKeys.LOCAL_URL_PREFIX,
            PrefKeys.LOCAL_USERNAME_PREFIX,
            PrefKeys.LOCAL_PASSWORD_PREFIX
        )
        val remotePath: WriteablePreference<ServerPath?> = serverPathPreference(
            prefs,
            secretPrefs,
            id,
            PrefKeys.REMOTE_URL_PREFIX,
            PrefKeys.REMOTE_USERNAME_PREFIX,
            PrefKeys.REMOTE_PASSWORD_PREFIX
        )

        val defaultSitemap: WriteablePreference<DefaultSitemap?> = defaultSitemapPreference(
            prefs,
            id,
            PrefKeys.DEFAULT_SITEMAP_NAME_PREFIX,
            PrefKeys.DEFAULT_SITEMAP_LABEL_PREFIX
        )
        val carSitemap: WriteablePreference<DefaultSitemap?> = defaultSitemapPreference(
            prefs,
            id,
            PrefKeys.CAR_SITEMAP_NAME_PREFIX,
            PrefKeys.CAR_SITEMAP_LABEL_PREFIX
        )
        val wearSitemap: WriteablePreference<DefaultSitemap?> = defaultSitemapPreference(
            prefs,
            id,
            PrefKeys.WEAR_SITEMAP_NAME_PREFIX,
            PrefKeys.WEAR_SITEMAP_LABEL_PREFIX
        )
        val wearTileSitemap: WriteablePreference<DefaultSitemap?> = defaultSitemapPreference(
            prefs,
            id,
            PrefKeys.WEAR_TILE_SITEMAP_NAME_PREFIX,
            PrefKeys.WEAR_TILE_SITEMAP_LABEL_PREFIX
        )

        val sslClientCertificate: WriteablePreference<String?> = serverConfigStringPreference(
            prefs,
            id,
            PrefKeys.SSL_CLIENT_CERT_PREFIX,
            null
        )

        val wifiSsids: WriteablePreference<Set<String>?> = serverConfigStringSetPreference(
            prefs,
            id,
            PrefKeys.WIFI_SSID_PREFIX,
            null
        )
        val restrictToWifiSsids: WriteablePreference<Boolean> = serverConfigBooleanPreference(
            prefs,
            id,
            PrefKeys.RESTRICT_TO_SSID_PREFIX,
            false
        )

        val frontailUrl: WriteablePreference<String?> = serverConfigStringPreference(
            prefs,
            id,
            PrefKeys.FRONTAIL_URL_PREFIX,
            null
        )
        val mainUiStartPage: WriteablePreference<String?> = serverConfigStringPreference(
            prefs,
            id,
            PrefKeys.MAIN_UI_START_PAGE_PREFIX,
            null
        )
        val wearComplicationGroup: WriteablePreference<String?> = serverConfigStringPreference(
            prefs,
            id,
            PrefKeys.WEAR_COMPLICATION_GROUP_PREFIX,
            null
        )

        data class DefaultSitemap(val name: String, val label: String)
    }
}

private fun booleanPreference(prefs: SharedPreferences, key: String, defaultValue: Boolean) = PreferenceImpl(
    prefs,
    key,
    valueGetter = { getBoolean(key, defaultValue) },
    valueWriter = { value -> putBoolean(key, value) }
)

private fun intPreference(prefs: SharedPreferences, key: String, defaultValue: Int) = PreferenceImpl(
    prefs,
    key,
    valueGetter = { getInt(key, defaultValue) },
    valueWriter = { value -> putInt(key, value) }
)

private fun stringPreference(prefs: SharedPreferences, key: String, defaultValue: String?) = PreferenceImpl(
    prefs,
    key,
    valueGetter = { getString(key, defaultValue) },
    valueWriter = { value -> putString(key, value) }
)

private fun stringSetPreference(prefs: SharedPreferences, key: String, defaultValue: Set<String>?) = PreferenceImpl(
    prefs,
    key,
    valueGetter = { getStringSet(key, defaultValue) },
    valueWriter = { value -> putStringSet(key, value) }
)

private fun serverConfigBooleanPreference(
    prefs: SharedPreferences,
    idPreference: Preference<Int>,
    keyPrefix: String,
    defaultValue: Boolean
) = ServerConfigPreferenceImpl(
    prefs,
    idPreference,
    keyPrefix,
    valueGetter = { key -> getBoolean(key, defaultValue) },
    valueWriter = { key, value -> putBoolean(key, value) }
)

private fun serverConfigStringPreference(
    prefs: SharedPreferences,
    idPreference: Preference<Int>,
    keyPrefix: String,
    defaultValue: String?
) = ServerConfigPreferenceImpl(
    prefs,
    idPreference,
    keyPrefix,
    valueGetter = { key -> getString(key, defaultValue) },
    valueWriter = { key, value -> putString(key, value) }
)


private fun serverConfigStringSetPreference(
    prefs: SharedPreferences,
    idPreference: Preference<Int>,
    keyPrefix: String,
    defaultValue: Set<String>?
) = ServerConfigPreferenceImpl(
    prefs,
    idPreference,
    keyPrefix,
    valueGetter = { key -> getStringSet(key, defaultValue) },
    valueWriter = { key, value -> putStringSet(key, value) }
)

private class PreferenceImpl<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val valueGetter: SharedPreferences.() -> T,
    private val valueWriter: SharedPreferences.Editor.(T) -> Unit
) : WriteablePreference<T> {
    override val value get() = valueGetter(prefs)

    override fun asFlow() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (key == changedKey) {
                trySend(valueGetter(prefs))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(valueGetter(prefs))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun update(newValue: T) {
        withContext(Dispatchers.IO) {
            prefs.edit {
                valueWriter(newValue)
            }
        }
    }

    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            prefs.edit {
                remove(key)
            }
        }
    }
}

private fun <T> constantValuePreference(prefValue: T) = object : Preference<T> {
    override val value = prefValue
    override fun asFlow() = flowOf(prefValue)
}

private class TransformingPreferenceImpl<T, R>(
    private val base: Preference<T>,
    private val transformRead: (T) -> (R),
) : Preference<R> {
    override val value get() = transformRead(base.value)

    override fun asFlow(): Flow<R> = base.asFlow().map { transformRead(it) }
}

private class TransformingWriteablePreferenceImpl<T, R>(
    private val base: WriteablePreference<T>,
    private val transformRead: (T) -> (R),
    private val transformWrite: (R) -> (T)
) : WriteablePreference<R> {
    override val value get() = transformRead(base.value)

    override fun asFlow(): Flow<R> = base.asFlow().map { transformRead(it) }

    override suspend fun update(newValue: R) {
        base.update(transformWrite(newValue))
    }

    override suspend fun reset() {
        base.reset()
    }
}

private class ServerConfigPreferenceImpl<T>(
    private val prefs: SharedPreferences,
    private val idPreference: Preference<Int>,
    private val keyPrefix: String,
    private val valueGetter: SharedPreferences.(String) -> T,
    private val valueWriter: SharedPreferences.Editor.(String, T) -> Unit
) : WriteablePreference<T> {
    override val value get() = valueGetter(prefs, buildKey(idPreference.value))

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun asFlow() = idPreference.asFlow().flatMapLatest { id ->
        val key = buildKey(id)
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (key == changedKey) {
                    trySend(valueGetter(prefs, key))
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            send(valueGetter(prefs, key))
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    override suspend fun update(newValue: T) {
        withContext(Dispatchers.IO) {
            prefs.edit {
                valueWriter(buildKey(idPreference.value), newValue)
            }
        }
    }

    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            prefs.edit {
                remove(buildKey(idPreference.value))
            }
        }
    }

    private fun buildKey(id: Int) = "$keyPrefix$id"
}

fun <T1, T2, R> compoundPreference(
    pref1: WriteablePreference<T1>,
    pref2: WriteablePreference<T2>,
    merge: (T1, T2) -> R,
    split: (R) -> Pair<T1, T2>
) = object : WriteablePreference<R> {
    override val value get() = merge(pref1.value, pref2.value)

    override fun asFlow() = combine(pref1.asFlow(), pref2.asFlow(), merge)

    override suspend fun update(newValue: R) {
        val (v1, v2) = split(newValue)
        pref1.update(v1)
        pref2.update(v2)
    }

    override suspend fun reset() {
        pref1.reset()
        pref2.reset()
    }
}

fun <T1, T2, T3, R> compoundPreference(
    pref1: WriteablePreference<T1>,
    pref2: WriteablePreference<T2>,
    pref3: WriteablePreference<T3>,
    merge: (T1, T2, T3) -> R,
    split: (R) -> Triple<T1, T2, T3>
) = object : WriteablePreference<R> {
    override val value get() = merge(pref1.value, pref2.value, pref3.value)

    override fun asFlow() = combine(pref1.asFlow(), pref2.asFlow(), pref3.asFlow(), merge)

    override suspend fun update(newValue: R) {
        val (v1, v2, v3) = split(newValue)
        pref1.update(v1)
        pref2.update(v2)
        pref3.update(v3)
    }

    override suspend fun reset() {
        pref1.reset()
        pref2.reset()
        pref3.reset()
    }
}

private fun defaultSitemapPreference(
    prefs: SharedPreferences,
    idPreference: Preference<Int>,
    namePrefix: String,
    labelPrefix: String
) = compoundPreference(
    serverConfigStringPreference(prefs, idPreference, namePrefix, null),
    serverConfigStringPreference(prefs, idPreference, labelPrefix, null),
    merge = { name, label -> if (name != null && label != null) Preferences.Server.DefaultSitemap(name, label) else null },
    split = { sitemap -> sitemap?.name to sitemap?.label }
)

private fun serverPathPreference(
    prefs: SharedPreferences,
    secretPrefs: SharedPreferences,
    idPreference: Preference<Int>,
    urlPrefix: String,
    userNamePrefix: String,
    passwordPrefix: String
) = compoundPreference(
    serverConfigStringPreference(prefs, idPreference, urlPrefix, null),
    serverConfigStringPreference(secretPrefs, idPreference, userNamePrefix, null),
    serverConfigStringPreference(secretPrefs, idPreference, passwordPrefix, null),
    merge = { url, username, password ->
        url.toNormalizedUrl()?.let {
            ServerPath(it, username, password)
        }
    },
    split = { path -> Triple(path?.url, path?.userName, path?.password) }
)
