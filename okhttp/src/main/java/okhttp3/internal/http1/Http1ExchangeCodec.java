/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http1

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.Util
import okhttp3.internal.Util.checkOffsetAndCount
import okhttp3.internal.addHeaderLenient
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.HttpHeaders
import okhttp3.internal.http.RequestLine
import okhttp3.internal.http.StatusLine
import okhttp3.internal.http.StatusLine.HTTP_CONTINUE
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingTimeout
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class strictly enforces the
 * following lifecycle:
 *
 *  1. [Send request headers][.writeRequest].
 *  2. Open a sink to write the request body. Either [known][newKnownLengthSink] or
 *  [chunked][newChunkedSink].
 *  3. Write to and then close that sink.
 *  4. [Read response headers][.readResponseHeaders].
 *  5. Open a source to read the response body. Either [fixed-length][newFixedLengthSource],
 *  [chunked][newChunkedSource] or [unknown][newUnknownLengthSource].
 *  6. Read from and close that source.
 *
 * Exchanges that do not have a request body may skip creating and closing the request body.
 * Exchanges that do not have a response body can call
 * [newFixedLengthSource(0)][newFixedLengthSource] and may skip reading and closing that source.
 */
class Http1ExchangeCodec(
  /** The client that configures this stream. May be null for HTTPS proxy tunnels.  */
  private val client: OkHttpClient?,
  /** The connection that carries this stream.  */
  private val realConnection: RealConnection?,
  private val source: BufferedSource,
  private val sink: BufferedSink
) : ExchangeCodec {
    private var state = STATE_IDLE
    private var headerLimit = HEADER_LIMIT.toLong()

    /**
     * Received trailers. Null unless the response body uses chunked transfer-encoding and includes
     * trailers. Undefined until the end of the response body.
     */
    private var trailers: Headers? = null

    /** Returns true if this connection is closed.  */
    val isClosed: Boolean
        get() = state == STATE_CLOSED

    override fun connection(): RealConnection? {
        return realConnection
    }

    @Throws(IOException::class)
    override fun createRequestBody(request: Request, contentLength: Long): Sink {
        if (request.body() != null && request.body()!!.isDuplex()) {
            throw ProtocolException("Duplex connections are not supported for HTTP/1")
        }

        if ("chunked".equals(request.header("Transfer-Encoding"), ignoreCase = true)) {
            // Stream a request body of unknown length.
            return newChunkedSink()
        }

        if (contentLength != -1L) {
            // Stream a request body of a known length.
            return newKnownLengthSink()
        }

        throw IllegalStateException(
                "Cannot stream a request body without chunked encoding or a known content length!")
    }

    override fun cancel() {
        realConnection?.cancel()
    }

    /**
     * Prepares the HTTP headers and sends them to the server.
     *
     *
     * For streaming requests with a body, headers must be prepared **before** the
     * output stream has been written to. Otherwise the body would need to be buffered!
     *
     *
     * For non-streaming requests with a body, headers must be prepared **after** the
     * output stream has been written to and closed. This ensures that the `Content-Length`
     * header field receives the proper value.
     */
    @Throws(IOException::class)
    override fun writeRequestHeaders(request: Request) {
        val requestLine = RequestLine.get(
                request, realConnection!!.route().proxy().type())
        writeRequest(request.headers(), requestLine)
    }

    override fun reportedContentLength(response: Response): Long {
        if (!HttpHeaders.hasBody(response)) {
            return 0L
        }

        return if ("chunked".equals(response.header("Transfer-Encoding"), ignoreCase = true)) {
            -1L
        } else HttpHeaders.contentLength(response)
    }

    override fun openResponseBodySource(response: Response): Source {
        if (!HttpHeaders.hasBody(response)) {
            return newFixedLengthSource(0)
        }

        if ("chunked".equals(response.header("Transfer-Encoding"), ignoreCase = true)) {
            return newChunkedSource(response.request().url())
        }

        val contentLength = HttpHeaders.contentLength(response)
        return if (contentLength != -1L) {
            newFixedLengthSource(contentLength)
        } else newUnknownLengthSource()
    }

    override fun trailers(): Headers {
        if (state != STATE_CLOSED) {
            throw IllegalStateException("too early; can't read the trailers yet")
        }
        return trailers ?: Util.EMPTY_HEADERS
    }

    @Throws(IOException::class)
    override fun flushRequest() {
        sink.flush()
    }

    @Throws(IOException::class)
    override fun finishRequest() {
        sink.flush()
    }

    /** Returns bytes of a request header for sending on an HTTP transport.  */
    @Throws(IOException::class)
    fun writeRequest(headers: Headers, requestLine: String) {
        if (state != STATE_IDLE) throw IllegalStateException("state: $state")
        sink.writeUtf8(requestLine).writeUtf8("\r\n")
        var i = 0
        val size = headers.size()
        while (i < size) {
            sink.writeUtf8(headers.name(i))
                    .writeUtf8(": ")
                    .writeUtf8(headers.value(i))
                    .writeUtf8("\r\n")
            i++
        }
        sink.writeUtf8("\r\n")
        state = STATE_OPEN_REQUEST_BODY
    }

    @Throws(IOException::class)
    override fun readResponseHeaders(expectContinue: Boolean): Response.Builder? {
        if (state != STATE_OPEN_REQUEST_BODY && state != STATE_READ_RESPONSE_HEADERS) {
            throw IllegalStateException("state: $state")
        }

        try {
            val statusLine = StatusLine.parse(readHeaderLine())

            val responseBuilder = Response.Builder()
                    .protocol(statusLine.protocol)
                    .code(statusLine.code)
                    .message(statusLine.message)
                    .headers(readHeaders())

            if (expectContinue && statusLine.code == HTTP_CONTINUE) {
                return null
            } else if (statusLine.code == HTTP_CONTINUE) {
                state = STATE_READ_RESPONSE_HEADERS
                return responseBuilder
            }

            state = STATE_OPEN_RESPONSE_BODY
            return responseBuilder
        } catch (e: EOFException) {
            // Provide more context if the server ends the stream before sending a response.
            val address = if (realConnection != null) realConnection.route().address().url().redact() else "unknown"
            throw IOException("unexpected end of stream on $address", e)
        }
    }

    @Throws(IOException::class)
    private fun readHeaderLine(): String {
        val line = source.readUtf8LineStrict(headerLimit)
        headerLimit -= line.length.toLong()
        return line
    }

    /** Reads headers or trailers.  */
    @Throws(IOException::class)
    private fun readHeaders(): Headers {
        val headers = Headers.Builder()
        // parse the result headers until the first blank line
        var line = readHeaderLine()
        while (line.isNotEmpty()) {
            addHeaderLenient(headers, line)
            line = readHeaderLine()
        }
        return headers.build()
    }

    private fun newChunkedSink(): Sink {
        if (state != STATE_OPEN_REQUEST_BODY) throw IllegalStateException("state: $state")
        state = STATE_WRITING_REQUEST_BODY
        return ChunkedSink()
    }

    private fun newKnownLengthSink(): Sink {
        if (state != STATE_OPEN_REQUEST_BODY) throw IllegalStateException("state: $state")
        state = STATE_WRITING_REQUEST_BODY
        return KnownLengthSink()
    }

    private fun newFixedLengthSource(length: Long): Source {
        if (state != STATE_OPEN_RESPONSE_BODY) throw IllegalStateException("state: $state")
        state = STATE_READING_RESPONSE_BODY
        return FixedLengthSource(length)
    }

    private fun newChunkedSource(url: HttpUrl): Source {
        if (state != STATE_OPEN_RESPONSE_BODY) throw IllegalStateException("state: $state")
        state = STATE_READING_RESPONSE_BODY
        return ChunkedSource(url)
    }

    private fun newUnknownLengthSource(): Source {
        if (state != STATE_OPEN_RESPONSE_BODY) throw IllegalStateException("state: $state")
        state = STATE_READING_RESPONSE_BODY
        realConnection!!.noNewExchanges()
        return UnknownLengthSource()
    }

    /**
     * Sets the delegate of `timeout` to [Timeout.NONE] and resets its underlying timeout
     * to the default configuration. Use this to avoid unexpected sharing of timeouts between pooled
     * connections.
     */
    private fun detachTimeout(timeout: ForwardingTimeout) {
        val oldDelegate = timeout.delegate
        timeout.setDelegate(Timeout.NONE)
        oldDelegate.clearDeadline()
        oldDelegate.clearTimeout()
    }

    /**
     * The response body from a CONNECT should be empty, but if it is not then we should consume it
     * before proceeding.
     */
    @Throws(IOException::class)
    fun skipConnectBody(response: Response) {
        val contentLength = HttpHeaders.contentLength(response)
        if (contentLength == -1L) return
        val body = newFixedLengthSource(contentLength)
        Util.skipAll(body, Integer.MAX_VALUE, MILLISECONDS)
        body.close()
    }

    /** An HTTP request body.  */
    private inner class KnownLengthSink : Sink {
        private val timeout = ForwardingTimeout(sink.timeout())
        private var closed: Boolean = false

        override fun timeout(): Timeout {
            return timeout
        }

        @Throws(IOException::class)
        override fun write(source: Buffer, byteCount: Long) {
            if (closed) throw IllegalStateException("closed")
            checkOffsetAndCount(source.size, 0, byteCount)
            sink.write(source, byteCount)
        }

        @Throws(IOException::class)
        override fun flush() {
            if (closed) return // Don't throw; this stream might have been closed on the caller's behalf.
            sink.flush()
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            closed = true
            detachTimeout(timeout)
            state = STATE_READ_RESPONSE_HEADERS
        }
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies. It is the caller's responsibility
     * to buffer chunks; typically by using a buffered sink with this sink.
     */
    private inner class ChunkedSink internal constructor() : Sink {
        private val timeout = ForwardingTimeout(sink.timeout())
        private var closed: Boolean = false

        override fun timeout(): Timeout {
            return timeout
        }

        @Throws(IOException::class)
        override fun write(source: Buffer, byteCount: Long) {
            if (closed) throw IllegalStateException("closed")
            if (byteCount == 0L) return

            sink.writeHexadecimalUnsignedLong(byteCount)
            sink.writeUtf8("\r\n")
            sink.write(source, byteCount)
            sink.writeUtf8("\r\n")
        }

        @Synchronized
        @Throws(IOException::class)
        override fun flush() {
            if (closed) return // Don't throw; this stream might have been closed on the caller's behalf.
            sink.flush()
        }

        @Synchronized
        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            closed = true
            sink.writeUtf8("0\r\n\r\n")
            detachTimeout(timeout)
            state = STATE_READ_RESPONSE_HEADERS
        }
    }

    private abstract inner class AbstractSource : Source {
        protected val timeout = ForwardingTimeout(source.timeout())
        protected var closed: Boolean = false

        override fun timeout(): Timeout {
            return timeout
        }

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            try {
                return source.read(sink, byteCount)
            } catch (e: IOException) {
                realConnection!!.noNewExchanges()
                responseBodyComplete()
                throw e
            }
        }

        /**
         * Closes the cache entry and makes the socket available for reuse. This should be invoked when
         * the end of the body has been reached.
         */
        internal fun responseBodyComplete() {
            if (state == STATE_CLOSED) return
            if (state != STATE_READING_RESPONSE_BODY) throw IllegalStateException("state: $state")

            detachTimeout(timeout)

            state = STATE_CLOSED
        }
    }

    /** An HTTP body with a fixed length specified in advance.  */
    private inner class FixedLengthSource internal constructor(private var bytesRemaining: Long) :
            AbstractSource() {

        init {
            if (bytesRemaining == 0L) {
                responseBodyComplete()
            }
        }

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount < 0) throw IllegalArgumentException("byteCount < 0: $byteCount")
            if (closed) throw IllegalStateException("closed")
            if (bytesRemaining == 0L) return -1

            val read = super.read(sink, Math.min(bytesRemaining, byteCount))
            if (read == -1L) {
                realConnection!!.noNewExchanges() // The server didn't supply the promised content length.
                val e = ProtocolException("unexpected end of stream")
                responseBodyComplete()
                throw e
            }

            bytesRemaining -= read
            if (bytesRemaining == 0L) {
                responseBodyComplete()
            }
            return read
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) return

            if (bytesRemaining != 0L && !Util.discard(this,
                            ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
                realConnection!!.noNewExchanges() // Unread bytes remain on the stream.
                responseBodyComplete()
            }

            closed = true
        }
    }

    /** An HTTP body with alternating chunk sizes and chunk bodies.  */
    private inner class ChunkedSource internal constructor(private val url: HttpUrl) :
            AbstractSource() {
        private var bytesRemainingInChunk = NO_CHUNK_YET
        private var hasMoreChunks = true

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount < 0) throw IllegalArgumentException("byteCount < 0: $byteCount")
            if (closed) throw IllegalStateException("closed")
            if (!hasMoreChunks) return -1

            if (bytesRemainingInChunk == 0L || bytesRemainingInChunk == NO_CHUNK_YET) {
                readChunkSize()
                if (!hasMoreChunks) return -1
            }

            val read = super.read(sink, Math.min(byteCount, bytesRemainingInChunk))
            if (read == -1L) {
                realConnection!!.noNewExchanges() // The server didn't supply the promised chunk length.
                val e = ProtocolException("unexpected end of stream")
                responseBodyComplete()
                throw e
            }
            bytesRemainingInChunk -= read
            return read
        }

        @Throws(IOException::class)
        private fun readChunkSize() {
            // Read the suffix of the previous chunk.
            if (bytesRemainingInChunk != NO_CHUNK_YET) {
                source.readUtf8LineStrict()
            }
            try {
                bytesRemainingInChunk = source.readHexadecimalUnsignedLong()
                val extensions = source.readUtf8LineStrict().trim { it <= ' ' }
                if (bytesRemainingInChunk < 0 || extensions.isNotEmpty() && !extensions.startsWith(
                                ";")) {
                    throw ProtocolException(
                            "expected chunk size and optional extensions but was \"" +
                                    bytesRemainingInChunk + extensions + "\"")
                }
            } catch (e: NumberFormatException) {
                throw ProtocolException(e.message)
            }

            if (bytesRemainingInChunk == 0L) {
                hasMoreChunks = false
                trailers = readHeaders()
                HttpHeaders.receiveHeaders(client!!.cookieJar(), url, trailers)
                responseBodyComplete()
            }
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            if (hasMoreChunks && !Util.discard(this, ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS,
                            MILLISECONDS)) {
                realConnection!!.noNewExchanges() // Unread bytes remain on the stream.
                responseBodyComplete()
            }
            closed = true
        }
    }

    /** An HTTP message body terminated by the end of the underlying stream.  */
    private inner class UnknownLengthSource : AbstractSource() {
        private var inputExhausted: Boolean = false

        @Throws(IOException::class)
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount < 0) throw IllegalArgumentException("byteCount < 0: $byteCount")
            if (closed) throw IllegalStateException("closed")
            if (inputExhausted) return -1

            val read = super.read(sink, byteCount)
            if (read == -1L) {
                inputExhausted = true
                responseBodyComplete()
                return -1
            }
            return read
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            if (!inputExhausted) {
                responseBodyComplete()
            }
            closed = true
        }
    }

    companion object {
        private const val NO_CHUNK_YET = -1L

        private const val STATE_IDLE = 0 // Idle connections are ready to write request headers.
        private const val STATE_OPEN_REQUEST_BODY = 1
        private const val STATE_WRITING_REQUEST_BODY = 2
        private const val STATE_READ_RESPONSE_HEADERS = 3
        private const val STATE_OPEN_RESPONSE_BODY = 4
        private const val STATE_READING_RESPONSE_BODY = 5
        private const val STATE_CLOSED = 6
        private const val HEADER_LIMIT = 256 * 1024
    }
}
