package org.openhab.habdroid.core.connection;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.util.Log;

import org.openhab.habdroid.util.AsyncHttpClient;
import org.openhab.habdroid.util.HttpClient;
import org.openhab.habdroid.util.SyncHttpClient;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

public abstract class AbstractConnection implements Connection {
    private static final String TAG = AbstractConnection.class.getSimpleName();

    private SharedPreferences settings;

    private int connectionType;
    private String username;
    private String password;
    private String baseUrl;

    private final AsyncHttpClient asyncHttpClient;
    private final SyncHttpClient syncHttpClient;

    AbstractConnection(Context ctx, SharedPreferences settings, int connectionType, String baseUrl,
            String username, String password) {
        this.settings = settings;
        this.username = username;
        this.password = password;
        this.baseUrl = baseUrl;
        this.connectionType = connectionType;

        asyncHttpClient = new AsyncHttpClient(ctx, settings, baseUrl);
        asyncHttpClient.setTimeout(30000);

        syncHttpClient = new SyncHttpClient(ctx, settings, baseUrl);

        updateHttpClientAuth(asyncHttpClient);
        updateHttpClientAuth(syncHttpClient);
    }

    AbstractConnection(@NonNull AbstractConnection base, int connectionType) {
        this.settings = base.settings;
        this.username = base.username;
        this.password = base.password;
        this.baseUrl = base.baseUrl;
        this.connectionType = connectionType;

        asyncHttpClient = base.getAsyncHttpClient();
        syncHttpClient = base.getSyncHttpClient();
    }

    private void updateHttpClientAuth(HttpClient httpClient) {
        if (hasUsernameAndPassword()) {
            httpClient.setBasicAuth(getUsername(), getPassword());
        }
    }

    private boolean hasUsernameAndPassword() {
        return getUsername() != null && !getUsername().isEmpty() && getPassword() != null &&
                !getPassword().isEmpty();
    }

    public AsyncHttpClient getAsyncHttpClient() {
        return asyncHttpClient;
    }

    public SyncHttpClient getSyncHttpClient() {
        return syncHttpClient;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public int getConnectionType() {
        return connectionType;
    }

    @Override
    @NonNull
    public String getOpenHABUrl() {
        return baseUrl;
    }

    @Override
    public boolean checkReachabilityInBackground() {
        Log.d(TAG, "Checking reachability of " + getOpenHABUrl());
        try {
            URL url = new URL(getOpenHABUrl());
            int checkPort = url.getPort();
            if (url.getProtocol().equals("http") && checkPort == -1)
                checkPort = 80;
            if (url.getProtocol().equals("https") && checkPort == -1)
                checkPort = 443;
            Socket s = new Socket();
            s.connect(new InetSocketAddress(url.getHost(), checkPort), 1000);
            Log.d(TAG, "Socket connected");
            s.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            return false;
        }
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + connectionType;
        result = 31 * result + baseUrl.hashCode();
        result = 31 * result + (username != null ? username.hashCode() : 0);
        result = 31 * result + (password != null ? password.hashCode() : 0);
        return result;
    }
}
