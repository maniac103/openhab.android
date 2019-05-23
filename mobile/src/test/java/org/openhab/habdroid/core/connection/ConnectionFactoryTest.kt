package org.openhab.habdroid.core.connection

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkInfo
import com.nhaarman.mockitokotlin2.*
import junit.framework.Assert.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openhab.habdroid.core.connection.exception.ConnectionException
import org.openhab.habdroid.core.connection.exception.NetworkNotAvailableException
import org.openhab.habdroid.core.connection.exception.NetworkNotSupportedException
import org.openhab.habdroid.core.connection.exception.NoUrlInformationException
import org.openhab.habdroid.util.Constants
import java.io.File
import java.io.IOException

class ConnectionFactoryTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val mainThread = newSingleThreadContext("UI thread")
    private lateinit var mockContext: Context
    private lateinit var mockConnectivityService: ConnectivityManager
    private lateinit var mockPrefs: SharedPreferences

    @Before
    @Throws(IOException::class)
    fun setup() {
        Dispatchers.setMain(mainThread)

        val cacheFolder = tempFolder.newFolder("cache")
        val appDir = tempFolder.newFolder()

        mockConnectivityService = mock()
        mockPrefs = mock()
        mockContext = mock<Application> {
            on { cacheDir } doReturn cacheFolder
            on { getDir(any(), any()) } doAnswer  { invocation ->
                File(appDir, invocation.getArgument<Any>(0).toString())
            }
            on { getString(any()) } doReturn ""
            on { getSystemService(eq(Context.CONNECTIVITY_SERVICE)) } doReturn mockConnectivityService
        }
        whenever(mockContext.applicationContext) doReturn mockContext

        ConnectionFactory.initialize(mockContext, mockPrefs)
    }

    @After
    fun tearDOwn() {
        Dispatchers.resetMain()
        mainThread.close()
    }

    @Test
    @Throws(IOException::class)
    fun testGetConnectionRemoteWithUrl() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_REMOTE_URL), any())) doReturn server.url("/").toString()
        runBlocking {
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            val conn = ConnectionFactory.getConnection(Connection.TYPE_REMOTE)

            assertNotNull("Requesting a remote connection when a remote url is set, " + " should return a connection.", conn)
            assertEquals("The connection type of a remote connection should be TYPE_REMOTE.",
                    Connection.TYPE_REMOTE, conn!!.connectionType)
        }
    }

    @Test
    fun testGetConnectionRemoteWithoutUrl() {
        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_REMOTE_URL), any())) doReturn ""
        runBlocking {
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            val conn = ConnectionFactory.getConnection(Connection.TYPE_REMOTE)

            assertNull("Requesting a remote connection when a remote url isn't set, " + "should not return a connection.", conn)
        }
    }

    @Test
    fun testGetConnectionLocalWithUrl() {
        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_LOCAL_URL), any())) doReturn "https://openhab.local:8080"
        runBlocking {
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            val conn = ConnectionFactory.getConnection(Connection.TYPE_LOCAL)

            assertNotNull("Requesting a local connection when local url is set, " + "should return a connection.", conn)
            assertEquals("The connection type of a local connection should be LOGLEVEL_LOCAL.",
                    Connection.TYPE_LOCAL, conn!!.connectionType)
        }
    }

    @Test
    fun testGetConnectionLocalWithoutUrl() {
        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_LOCAL_URL), any())) doReturn ""
        runBlocking {
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            val conn = ConnectionFactory.getConnection(Connection.TYPE_LOCAL)

            assertNull("Requesting a remote connection when a local url isn't set, " + "should not return a connection.", conn)
        }
    }

    @Test
    @Throws(IOException::class)
    fun testGetConnectionCloudWithUrl() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{'gcm': { 'senderId': '12345'} }"))
        server.start()

        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_REMOTE_URL), any())) doReturn server.url("/").toString()
        runBlocking {
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            val conn = ConnectionFactory.getConnection(Connection.TYPE_CLOUD)

            assertNotNull("Requesting a cloud connection when a remote url is set, " + "should return a connection.", conn)
            assertEquals(CloudConnection::class.java, conn!!.javaClass)
            assertEquals("The connection type of a cloud connection should be TYPE_CLOUD.",
                    Connection.TYPE_CLOUD, conn.connectionType)
            assertEquals("The sender ID of the cloud connection should be '12345'",
                    "12345", (conn as CloudConnection).messagingSenderId)

            server.shutdown()
        }
    }

    @Test(expected = NetworkNotAvailableException::class)
    @Throws(ConnectionException::class)
    fun testGetAnyConnectionNoNetwork() {
        runBlocking {
            triggerNetworkUpdate(null)
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            ConnectionFactory.usableConnection
        }
    }

    @Test(expected = NetworkNotSupportedException::class)
    @Throws(ConnectionException::class)
    fun testGetAnyConnectionUnsupportedNetwork() {
        runBlocking {
            triggerNetworkUpdate(ConnectivityManager.TYPE_BLUETOOTH)
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            ConnectionFactory.usableConnection
        }
    }

    @Test
    @Throws(ConnectionException::class, IOException::class)
    fun testGetAnyConnectionWifiRemoteOnly() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_REMOTE_URL), any())) doReturn server.url("/").toString()
        runBlocking {
            triggerNetworkUpdate(ConnectivityManager.TYPE_WIFI)
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()

            val conn = ConnectionFactory.usableConnection

            assertNotNull("Requesting any connection in WIFI when only a remote url is set, " + "should return a connection.", conn)
            assertEquals("The connection type of the connection should be TYPE_REMOTE.",
                    Connection.TYPE_REMOTE, conn!!.connectionType)

            server.shutdown()
        }
    }

    @Test
    @Throws(ConnectionException::class, IOException::class)
    fun testGetAnyConnectionWifiLocalRemote() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_REMOTE_URL), any())) doReturn server.url("/").toString()
        whenever(mockPrefs.getString(eq(Constants.PREFERENCE_LOCAL_URL), any())) doReturn "https://myopenhab.org:443"
        runBlocking {
            triggerNetworkUpdate(ConnectivityManager.TYPE_WIFI)
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()

            val conn = ConnectionFactory.usableConnection

            assertNotNull("Requesting any connection in WIFI when a local url is set, " + "should return a connection.", conn)
            assertEquals("The connection type of the connection should be TYPE_LOCAL.",
                    Connection.TYPE_LOCAL, conn!!.connectionType)

            server.shutdown()
        }
    }

    @Test(expected = NoUrlInformationException::class)
    @Throws(ConnectionException::class)
    fun testGetAnyConnectionWifiNoLocalNoRemote() {
        whenever(mockPrefs.getString(any(), any())) doReturn null
        triggerNetworkUpdate(ConnectivityManager.TYPE_WIFI)
        runBlocking {
            ConnectionFactory.instance.updateConnections()
            ConnectionFactory.waitForInitialization()
            ConnectionFactory.usableConnection
        }
    }

    private fun triggerNetworkUpdate(intendedType: Int) {
        val mockNetworkInfo = mock<NetworkInfo> {
            on { type } doReturn intendedType
            on { isConnected } doReturn true
        }
        triggerNetworkUpdate(mockNetworkInfo)
    }

    private fun triggerNetworkUpdate(info: NetworkInfo?) {
        whenever(mockConnectivityService.activeNetworkInfo) doReturn info

        ConnectionFactory.instance.onReceive(mockContext,
                Intent(ConnectivityManager.CONNECTIVITY_ACTION))
    }
}
