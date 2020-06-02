/*
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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

package org.openhab.habdroid.util

import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.Log
import com.caverock.androidsvg.SVGExternalFileResolver
import kotlinx.coroutines.runBlocking

class SVGFileResolver constructor(private val httpClient: HttpClient): SVGExternalFileResolver() {
    override fun resolveFont(fontFamily: String, fontWeight: Int, fontStyle: String?): Typeface? {
        //return Typeface.createFromAsset(getContext().getAssets(), "$fontFamily.ttf")
        return null
    }

    override fun resolveImage(filename: String): Bitmap? {
        return try {
            runBlocking {
                httpClient.get("images/$filename").asBitmap(0, false).response
            }
            null
        } catch (e: HttpClient.HttpException) {
            null
        }
    }

    override fun resolveCSSStyleSheet(url: String): String? {
        return try {
            Log.d(Util.TAG, "SVG bitmapURL: $httpClient")
            runBlocking {
                httpClient.get(url).asText().response
            }
        } catch (e: HttpClient.HttpException) {
            null
        }
    }
}
