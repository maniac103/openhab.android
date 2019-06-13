/*
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Properties

class MjpegInputStream(stream: InputStream) : DataInputStream(BufferedInputStream(stream, FRAME_MAX_LENGTH)) {
    @Throws(IOException::class)
    private fun getEndOfSeqeunce(stream: DataInputStream, sequence: ByteArray): Int {
        var seqIndex = 0
        for (i in 0 until FRAME_MAX_LENGTH) {
            val c = stream.readUnsignedByte().toByte()
            if (c == sequence[seqIndex]) {
                seqIndex++
                if (seqIndex == sequence.size) {
                    return i + 1
                }
            } else {
                seqIndex = 0
            }
        }
        return -1
    }

    @Throws(IOException::class)
    private fun getStartOfSequence(stream: DataInputStream, sequence: ByteArray): Int {
        val end = getEndOfSeqeunce(stream, sequence)
        return if (end < 0) -1 else end - sequence.size
    }

    @Throws(IOException::class, NumberFormatException::class)
    private fun parseContentLength(headerBytes: ByteArray): Int {
        val headerIn = ByteArrayInputStream(headerBytes)
        val props = Properties()
        props.load(headerIn)
        return Integer.parseInt(props.getProperty(CONTENT_LENGTH))
    }

    @Throws(IOException::class)
    fun readMjpegFrame(): Bitmap? {
        mark(FRAME_MAX_LENGTH)
        val headerLen = getStartOfSequence(this, SOI_MARKER)
        reset()

        if (headerLen < 0) {
            return null
        }

        val header = ByteArray(headerLen)
        readFully(header)

        val contentLength = try {
            parseContentLength(header)
        } catch (nfe: NumberFormatException) {
            getEndOfSeqeunce(this, EOF_MARKER)
        }

        reset()
        skipBytes(headerLen)

        if (contentLength < 0) {
            return null
        }

        val frameData = ByteArray(contentLength)
        readFully(frameData)

        return BitmapFactory.decodeStream(ByteArrayInputStream(frameData))
    }

    companion object {
        private val SOI_MARKER = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val EOF_MARKER = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        private const val HEADER_MAX_LENGTH = 100
        private const val FRAME_MAX_LENGTH = 400000 + HEADER_MAX_LENGTH
        private const val CONTENT_LENGTH = "Content-Length"
    }
}
