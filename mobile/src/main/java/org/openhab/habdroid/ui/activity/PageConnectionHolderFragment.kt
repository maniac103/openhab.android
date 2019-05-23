package org.openhab.habdroid.ui.activity

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import androidx.core.net.toUri
import androidx.fragment.app.Fragment

import com.here.oksse.ServerSentEvent
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.model.ServerProperties
import org.openhab.habdroid.model.Widget
import org.openhab.habdroid.model.WidgetDataSource
import org.openhab.habdroid.ui.WidgetListFragment
import org.openhab.habdroid.util.AsyncHttpClient
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException

import java.io.IOException
import java.io.StringReader
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Fragment that manages connections for active instances of
 * [WidgetListFragment]
 *
 * It retains the connections over activity recreations, and takes care of stopping
 * and restarting connections if needed.
 */
class PageConnectionHolderFragment : Fragment() {
    private val connections = HashMap<String, ConnectionHandler>()
    private lateinit var callback: ParentCallback
    private var started: Boolean = false

    interface ParentCallback {
        /**
         * Ask parent for the icon format to use
         *
         * @return Icon format ('PNG' or 'SVG')
         */
        val iconFormat: String

        /**
         * Ask parent whether logging should include detailed output
         *
         * @return true if logging should be detailed
         */
        val isDetailedLoggingEnabled: Boolean

        /**
         * Ask parent for properties of connected server
         *
         * @return server properties
         */
        val serverProperties: ServerProperties?

        /**
         * Let parent know about an update to the widget list for a given URL.
         *
         * @param pageUrl   URL of the updated page
         * @param pageTitle Updated page title
         * @param widgets   Updated list of widgets for the given page
         */
        fun onPageUpdated(pageUrl: String, pageTitle: String?, widgets: List<Widget>)

        /**
         * Let parent know about an update to the contents of a single widget.
         *
         * @param pageUrl  URL of the page the updated widget belongs to
         * @param widget   Updated widget
         */
        fun onWidgetUpdated(pageUrl: String, widget: Widget)

        /**
         * Let parent know about an update to the page title
         *
         * @param pageUrl  URL of the page the updated title belongs to
         * @param title    Updated title
         */
        fun onPageTitleUpdated(pageUrl: String, title: String)

        /**
         * Let parent know about a failure during the load of data.
         */
        fun onLoadFailure(url: String, statusCode: Int, error: Throwable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart(), started $started")
        if (!started) {
            connections.values.forEach { handler -> handler.load() }
            started = true
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop()")
        // If the activity is only changing configuration (e.g. orientation or locale)
        // we know it'll be immediately recreated, thus there's no point in shutting down
        // the connections in that case
        if (!(activity?.isChangingConfigurations ?: false)) {
            connections.values.forEach { handler -> handler.cancel() }
            started = false
        }
    }

    /**
     * Assign parent callback
     *
     *
     * To be called by the parent as early as possible,
     * as it's expected to be non-null at all times
     *
     * @param callback Callback for parent
     */
    fun setCallback(callback: ParentCallback) {
        this.callback = callback
        connections.values.forEach { handler -> handler.callback = callback }
    }

    /**
     * Update list of page URLs to track
     *
     * @param urls       New list of URLs to track
     * @param connection Connection to use, or null if none is available
     */
    fun updateActiveConnections(urls: List<String>, connection: Connection?) {
        Log.d(TAG, "updateActiveConnections: URL list $urls, connection $connection")
        if (connection == null) {
            connections.values.forEach { handler -> handler.cancel() }
            connections.clear()
            return
        }

        connections.keys.filter { url -> !urls.contains(url) }
                .forEach { url -> connections.remove(url)?.cancel() }
        for (url in urls) {
            var handler = connections[url]
            if (handler == null) {
                Log.d(TAG, "Creating new handler for URL $url")
                handler = ConnectionHandler(url, connection, callback)
                connections[url] = handler
                if (started) {
                    handler.load()
                }
            } else if (handler.updateFromConnection(connection) && started) {
                handler.load()
            }
        }
    }

    /**
     * Ask for new data to be delivered for a given page
     *
     * @param pageUrl     URL of page to trigger update for
     * @param forceReload true if existing data should be discarded and new data be loaded,
     * false if only existing data should be delivered, if it exists
     */
    fun triggerUpdate(pageUrl: String, forceReload: Boolean) {
        connections[pageUrl]?.triggerUpdate(forceReload)
    }

    override fun toString(): String {
        return String.format(Locale.US, "%s [%d connections, started=%s]",
                super.toString(), connections.size, started)
    }

    private class ConnectionHandler(private val url: String, connection: Connection, internal var callback: ParentCallback) :
            AsyncHttpClient.StringResponseHandler() {
        private var httpClient: AsyncHttpClient
        private var requestHandle: Call? = null
        private var longPolling: Boolean = false
        private var atmosphereTrackingId: String? = null
        private var lastPageTitle: String? = null
        private var lastWidgetList: MutableList<Widget>? = null
        private var eventHelper: EventHelper? = null

        init {
            httpClient = connection.asyncHttpClient
            if (callback.serverProperties?.hasSseSupport() ?: false) {
                val segments = url.toUri().pathSegments
                if (segments.size > 2) {
                    val sitemap = segments[segments.size - 2]
                    val pageId = segments[segments.size - 1]
                    Log.d(TAG, "Creating new SSE helper for sitemap " + sitemap
                            + ", page " + pageId)
                    eventHelper = EventHelper(httpClient, sitemap, pageId,
                            this::handleUpdateEvent, this::handleSseSubscriptionFailure)
                }
            }
        }

        fun updateFromConnection(c: Connection): Boolean {
            val oldClient = httpClient
            httpClient = c.asyncHttpClient
            return oldClient != httpClient
        }

        fun cancel() {
            Log.d(TAG, "Canceling connection for URL $url")
            requestHandle?.cancel()
            requestHandle = null
            eventHelper?.shutdown()
            longPolling = false
        }

        fun triggerUpdate(forceReload: Boolean) {
            Log.d(TAG, "Trigger update for URL $url, force $forceReload")
            if (forceReload) {
                longPolling = false
                load()
            } else {
                val lastWidgets = lastWidgetList
                if (lastWidgets != null) {
                    callback.onPageUpdated(url, lastPageTitle, lastWidgets)
                }
            }
        }

        internal fun load() {
            if (eventHelper != null && longPolling) {
                // We update via events
                return
            }

            Log.d(TAG, "Loading data for $url, long polling $longPolling")
            val headers = HashMap<String, String>()
            if (!(callback.serverProperties?.hasJsonApi() ?: false)) {
                headers["Accept"] = "application/xml"
            }

            if (longPolling) {
                headers["X-Atmosphere-Transport"] = "long-polling"
            } else {
                atmosphereTrackingId = null
            }

            headers["X-Atmosphere-Framework"] = "1.0"
            headers["X-Atmosphere-tracking-id"] = atmosphereTrackingId ?: "0"

            requestHandle?.cancel()

            val timeoutMillis = if (longPolling) 300000L else 10000L
            requestHandle = httpClient.get(url, headers, timeoutMillis, this)
            eventHelper?.connect()
        }

        override fun onFailure(request: Request, statusCode: Int, error: Throwable) {
            Log.d(TAG, "Data load for $url failed", error)
            atmosphereTrackingId = null
            longPolling = false
            callback.onLoadFailure(request.url().toString(), statusCode, error)
        }

        override fun onSuccess(response: String, headers: Headers) {
            val id = headers.get("X-Atmosphere-tracking-id")
            if (id != null) {
                atmosphereTrackingId = id
            }

            // We can receive empty response, probably when no items was changed
            // so we needn't process it
            if (response.isEmpty()) {
                Log.d(TAG, "Got empty data response for $url")
                longPolling = true
                load()
                return
            }

            val dataSource = WidgetDataSource(callback.iconFormat)
            val hasUpdate = if (callback.serverProperties?.hasJsonApi() ?: false)
                    parseResponseJson(dataSource, response) else parseResponseXml(dataSource, response)

            if (hasUpdate) {
                // Remove frame widgets with no label text
                val widgetList = dataSource.widgets.filter { w -> w.type !== Widget.Type.Frame || !w.label.isEmpty() }
                Log.d(TAG, "Updated page data for URL $url (" + widgetList.size + " widgets)")
                if (callback.isDetailedLoggingEnabled) {
                    widgetList.forEachIndexed { index, widget ->
                        Log.d(TAG, "Widget " + (index + 1) + ": $widget")
                    }
                }
                lastPageTitle = dataSource.title
                lastWidgetList = widgetList.toMutableList()
                callback.onPageUpdated(url, lastPageTitle, widgetList)
            }

            load()
        }

        private fun parseResponseXml(dataSource: WidgetDataSource, response: String): Boolean {
            val dbf = DocumentBuilderFactory.newInstance()
            try {
                val builder = dbf.newDocumentBuilder()
                val document = builder.parse(InputSource(StringReader(response)))
                if (document == null) {
                    Log.d(TAG, "Got empty XML document for $url")
                    longPolling = false
                    return false
                }
                val rootNode = document.firstChild
                dataSource.setSourceNode(rootNode)
                longPolling = true
                return true
            } catch (e: ParserConfigurationException) {
                Log.d(TAG, "Parsing data for $url failed", e)
                longPolling = false
                return false
            } catch (e: SAXException) {
                Log.d(TAG, "Parsing data for $url failed", e)
                longPolling = false
                return false
            } catch (e: IOException) {
                Log.d(TAG, "Parsing data for $url failed", e)
                longPolling = false
                return false
            }

        }

        private fun parseResponseJson(dataSource: WidgetDataSource, response: String): Boolean {
            try {
                val pageJson = JSONObject(response)
                // In case of a server timeout in the long polling request, nothing is done
                // and the request is restarted
                if (longPolling && pageJson.optBoolean("timeout", false)) {
                    Log.d(TAG, "Long polling timeout for $url")
                    return false
                }
                dataSource.setSourceJson(pageJson)
                longPolling = true
                return true
            } catch (e: JSONException) {
                Log.d(TAG, "Parsing data for $url failed", e)
                longPolling = false
                return false
            }

        }

        internal fun handleUpdateEvent(pageId: String, payload: String) {
            val widgetList = lastWidgetList
            if (widgetList == null) {
                return
            }
            try {
                val jsonObject = JSONObject(payload)

                val eventType = jsonObject.optString("TYPE")
                if ("SITEMAP_CHANGED" == eventType) {
                    Log.d(TAG, "Got SITEMAP_CHANGED event, reload sitemap")
                    cancel()
                    load()
                    return
                } else if ("ALIVE" == eventType) {
                    // We ignore 'server alive' events
                    Log.d(TAG, "Got ALIVE event")
                    return
                }

                val widgetId = jsonObject.getString("widgetId")
                if (TextUtils.equals(widgetId, pageId)) {
                    callback.onPageTitleUpdated(url, jsonObject.getString("label"))
                    return
                }
                val pos = widgetList.indexOfFirst {  w -> w.id == widgetId }
                if (pos >= 0) {
                    val updatedWidget = Widget.updateFromEvent(widgetList[pos], jsonObject, callback.iconFormat)
                    widgetList[pos] = updatedWidget
                    callback.onWidgetUpdated(url, updatedWidget)
                } else  {
                    // We didn't find the widget, so the widget in question probably
                    // just became visible. Reload the page in that case.
                    if (jsonObject.optBoolean("visibility")) {
                        cancel()
                        load()
                    }
                }
            } catch (e: JSONException) {
                Log.w(TAG, "Could not parse SSE event ('$payload')", e)
            }

        }

        internal fun handleSseSubscriptionFailure() {
            Log.w(TAG, "SSE processing failed for $url, using long polling")
            eventHelper = null
            if (longPolling) {
                load()
            }
        }

        private class EventHelper internal constructor(private val client: AsyncHttpClient,
                                                       private val sitemap: String,
                                                       private val pageId: String,
                                                       private val updateCb: (pageId: String, message: String) -> Unit,
                                                       private val failureCb: () -> Unit) : ServerSentEvent.Listener {
            private val handler: Handler = Handler(Looper.getMainLooper())
            private var subscribeHandle: Call? = null
            private var eventStream: ServerSentEvent? = null
            private var retries: Int = 0

            internal fun connect() {
                shutdown()

                subscribeHandle = client.post("/rest/sitemaps/events/subscribe",
                        "{}", "application/json", object : AsyncHttpClient.StringResponseHandler() {
                    override fun onFailure(request: Request, statusCode: Int, error: Throwable) {
                        if (statusCode == 404) {
                            Log.d(TAG, "Server does not have SSE support")
                        } else {
                            Log.w(TAG, "Failed subscribing for SSE", error)
                        }
                        failureCb()
                    }

                    override fun onSuccess(response: String, headers: Headers) {
                        try {
                            val result = JSONObject(response)
                            val status = result.getString("status")
                            if (status != "CREATED") {
                                throw JSONException("Unexpected status $status")
                            }
                            val headerObject = result.getJSONObject("context").getJSONObject("headers")
                            val url = HttpUrl.parse(headerObject.getJSONArray("Location").getString(0))
                            if (url != null) {
                                val u = url.newBuilder()
                                        .addQueryParameter("sitemap", sitemap)
                                        .addQueryParameter("pageid", pageId)
                                        .build()
                                eventStream = client.makeSse(u, this@EventHelper)
                            }
                        } catch (e: JSONException) {
                            Log.w(TAG, "Failed parsing SSE subscription", e)
                            failureCb()
                        }

                    }
                })
            }

            internal fun shutdown() {
                eventStream?.close()
                eventStream = null
                subscribeHandle?.cancel()
                subscribeHandle = null
            }


            override fun onOpen(sse: ServerSentEvent, response: Response) {
                retries = 0
            }

            override fun onMessage(sse: ServerSentEvent, id: String, event: String, message: String) {
                handler.post { updateCb(pageId, message) }
            }

            override fun onComment(sse: ServerSentEvent, comment: String) {}

            override fun onRetryTime(sse: ServerSentEvent, milliseconds: Long): Boolean {
                return true
            }

            override fun onRetryError(sse: ServerSentEvent,
                                      throwable: Throwable, response: Response?): Boolean {
                val statusCode = response?.code() ?: 0
                Log.w(TAG, "SSE stream failed for page $pageId with status $statusCode (retry $retries)")
                // Stop retrying after maximum amount of subsequent retries is reached
                return ++retries < MAX_RETRIES
            }

            override fun onClosed(sse: ServerSentEvent) {
                // We're only interested in permanent failure here, not in callbacks we caused
                // ourselves by calling close(), so check for both
                // - the reporter matching our expectations (mismatch means shutdown was called)
                // - retry count exhaustion
                if (retries >= MAX_RETRIES && sse === eventStream) {
                    failureCb
                }
            }

            override fun onPreRetry(sse: ServerSentEvent, originalRequest: Request): Request {
                return originalRequest
            }

            companion object {
                private val MAX_RETRIES = 10
            }
        }
    }

    companion object {
        private val TAG = PageConnectionHolderFragment::class.java.simpleName
    }
}
