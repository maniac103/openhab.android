/*
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.json.JSONArray;
import org.json.JSONException;
import org.openhab.habdroid.R;
import org.openhab.habdroid.core.GcmIntentService;
import org.openhab.habdroid.core.NetworkConnectivityInfo;
import org.openhab.habdroid.core.NotificationDeletedBroadcastReceiver;
import org.openhab.habdroid.core.OnUpdateBroadcastReceiver;
import org.openhab.habdroid.core.OpenHABTracker;
import org.openhab.habdroid.core.OpenHABTrackerReceiver;
import org.openhab.habdroid.core.OpenHABVoiceService;
import org.openhab.habdroid.core.notifications.GoogleCloudMessageConnector;
import org.openhab.habdroid.core.notifications.NotificationSettings;
import org.openhab.habdroid.model.OpenHABLinkedPage;
import org.openhab.habdroid.model.OpenHABSitemap;
import org.openhab.habdroid.model.OpenHABWidget;
import org.openhab.habdroid.ui.activity.ActivityController;
import org.openhab.habdroid.ui.activity.PageConnectionHolderFragment;
import org.openhab.habdroid.ui.drawer.OpenHABDrawerAdapter;
import org.openhab.habdroid.ui.drawer.OpenHABDrawerItem;
import org.openhab.habdroid.util.Constants;
import org.openhab.habdroid.util.MyAsyncHttpClient;
import org.openhab.habdroid.util.MyHttpClient;
import org.openhab.habdroid.util.MySyncHttpClient;
import org.openhab.habdroid.util.Util;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateRevokedException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import de.duenndns.ssl.MTMDecision;
import de.duenndns.ssl.MemorizingResponder;
import de.duenndns.ssl.MemorizingTrustManager;
import okhttp3.Call;
import okhttp3.Headers;

import static org.openhab.habdroid.util.Constants.MESSAGES.DIALOG;
import static org.openhab.habdroid.util.Constants.MESSAGES.LOGLEVEL.ALWAYS;
import static org.openhab.habdroid.util.Constants.MESSAGES.LOGLEVEL.DEBUG;
import static org.openhab.habdroid.util.Constants.MESSAGES.LOGLEVEL.NO_DEBUG;
import static org.openhab.habdroid.util.Util.exceptionHasCause;
import static org.openhab.habdroid.util.Util.removeProtocolFromUrl;

public class OpenHABMainActivity extends AppCompatActivity implements
        OpenHABTrackerReceiver, MemorizingResponder,
        PageConnectionHolderFragment.ParentCallback {

    private abstract class DefaultHttpResponseHandler implements MyHttpClient.ResponseHandler {

        @Override
        public void onFailure(Call call, int statusCode, Headers headers, byte[] responseBody, Throwable error) {
            setProgressIndicatorVisible(false);
            Log.e(TAG, "Error: " + error.toString());
            Log.e(TAG, "HTTP status code: " + statusCode);
            String message;
            if (statusCode >= 400){
                int resourceID;
                try {
                    resourceID = getResources().getIdentifier("error_http_code_" + statusCode, "string", getPackageName());
                    message = getString(resourceID);
                } catch (android.content.res.Resources.NotFoundException e) {
                    message = String.format(getString(R.string.error_http_connection_failed), statusCode);
                }
            } else if (error instanceof UnknownHostException) {
                Log.e(TAG, "Unable to resolve hostname");
                message = getString(R.string.error_unable_to_resolve_hostname);
            } else if (error instanceof SSLException) {
                // if ssl exception, check for some common problems
                if (exceptionHasCause(error, CertPathValidatorException.class)) {
                    message = getString(R.string.error_certificate_not_trusted);
                } else if (exceptionHasCause(error, CertificateExpiredException.class)) {
                    message = getString(R.string.error_certificate_expired);
                } else if (exceptionHasCause(error, CertificateNotYetValidException.class)) {
                    message = getString(R.string.error_certificate_not_valid_yet);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && exceptionHasCause(error, CertificateRevokedException.class)) {
                    message = getString(R.string.error_certificate_revoked);
                } else if (exceptionHasCause(error, SSLPeerUnverifiedException.class)) {
                    message = String.format(getString(R.string.error_certificate_wrong_host),
                            removeProtocolFromUrl(openHABBaseUrl));
                } else {
                    message = getString(R.string.error_connection_sslhandshake_failed);
                }
            } else if (error instanceof ConnectException) {
                message = getString(R.string.error_connection_failed);
            } else {
                Log.e(TAG, error.getClass().toString());
                message = error.getMessage();
            }
            showMessageToUser(message, DIALOG, NO_DEBUG);
            message += "\nURL: " + openHABBaseUrl + "\nUsername: " + openHABUsername + "\nPassword: " + openHABPassword;
            message += "\nStacktrace:\n" + Log.getStackTraceString(error);
            showMessageToUser(message, DIALOG, DEBUG);
        }
    }

    // GCM Registration expiration
    public static final long REGISTRATION_EXPIRY_TIME_MS = 1000 * 3600 * 24 * 7;
    // Logging TAG
    private static final String TAG = OpenHABMainActivity.class.getSimpleName();
    // Activities request codes
    private static final int SETTINGS_REQUEST_CODE = 1002;
    private static final int WRITE_NFC_TAG_REQUEST_CODE = 1003;
    private static final int INFO_REQUEST_CODE = 1004;
    // Drawer item codes
    private static final int DRAWER_NOTIFICATIONS = 100;
    private static final int DRAWER_ABOUT = 101;
    private static final int DRAWER_PREFERENCES = 102;

    // Loopj
    private MyAsyncHttpClient mAsyncHttpClient;
    // Base URL of current openHAB connection
    private String openHABBaseUrl = "http://demo.openhab.org:8080/";
    // openHAB username
    private String openHABUsername = "";
    // openHAB password
    private String openHABPassword = "";
    // openHAB Bonjour service name
    private String openHABServiceType;
    // preferences
    private SharedPreferences mSettings;
    // OpenHAB tracker
    private OpenHABTracker mOpenHABTracker;
    // Progress dialog
    private ProgressDialog mProgressDialog;
    // NFC Launch data
    private String mNfcData;
    // Toolbar / Actionbar
    private Toolbar mToolbar;
    // Drawer Layout
    private DrawerLayout mDrawerLayout;
    // Drawer Toggler
    private ActionBarDrawerToggle mDrawerToggle;
    // Google Cloud Messaging
    private GoogleCloudMessaging mGcm;
    private OpenHABDrawerAdapter mDrawerAdapter;
    private RecyclerView.RecycledViewPool mViewPool;
    private ArrayList<OpenHABSitemap> mSitemapList;
    private NetworkConnectivityInfo mLastKnownNetworkInfo;
    private int mOpenHABVersion;
    private List<OpenHABDrawerItem> mDrawerItemList;
    private ProgressBar mProgressBar;
    private NotificationSettings mNotifySettings = null;
    // select sitemap dialog
    private Dialog selectSitemapDialog;
    public static String GCM_SENDER_ID;

    private OpenHABSitemap mSelectedSitemap;
    private ActivityController mController;

    /**
     * Daydreaming gets us into a funk when in fullscreen, this allows us to
     * reset ourselves to fullscreen.
     * @author Dan Cunningham
     */
    private BroadcastReceiver dreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("INTENTFILTER", "Recieved intent: " + intent.toString());
            checkFullscreen();
        }
    };

    public MyAsyncHttpClient getAsyncHttpClient() {
        return mAsyncHttpClient;
    }

    /**
     * This method is called when activity receives a new intent while running
     */
    @Override
    protected void onNewIntent(Intent intent) {
        processIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");

        // Set default values, false means do it one time during the very first launch
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Set the theme to one from preferences
        mSettings = PreferenceManager.getDefaultSharedPreferences(this);

        // initialize loopj async http client
        mAsyncHttpClient = new MyAsyncHttpClient(this, mSettings.getBoolean(Constants.PREFERENCE_SSLHOST,
                false), mSettings.getBoolean(Constants.PREFERENCE_SSLCERT, false));

        // Disable screen timeout if set in preferences
        if (mSettings.getBoolean(Constants.PREFERENCE_SCREENTIMEROFF, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Fetch openHAB service type name from strings.xml
        openHABServiceType = getString(R.string.openhab_service_type);

        // Get username/password from preferences
        openHABUsername = mSettings.getString(Constants.PREFERENCE_REMOTE_USERNAME, null);
        openHABPassword = mSettings.getString(Constants.PREFERENCE_REMOTE_PASSWORD, null);
        mAsyncHttpClient.setBasicAuth(openHABUsername, openHABPassword, true);
        mAsyncHttpClient.setTimeout(30000);

        Util.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        String controllerClassName = getResources().getString(R.string.controller_class);
        try {
            Class<?> controllerClass = Class.forName(controllerClassName);
            Constructor<?> constructor = controllerClass.getConstructor(OpenHABMainActivity.class);
            mController = (ActivityController) constructor.newInstance(this);
        } catch (Exception e) {
            Log.wtf(TAG, "Could not instantiate activity controller class '"
                    + controllerClassName + "'");
            throw new RuntimeException(e);
        }

        setContentView(R.layout.activity_main);

        // inflate the controller dependent content view
        ViewStub contentStub = findViewById(R.id.content_stub);
        contentStub.setLayoutResource(mController.getContentLayoutResource());
        mController.initViews(contentStub.inflate());

        setupToolbar();
        setupDrawer();
        gcmRegisterBackground();

        mViewPool = new RecyclerView.RecycledViewPool();
        MemorizingTrustManager.setResponder(this);

        // Check if we have openHAB page url in saved instance state?
        if (savedInstanceState != null) {
            openHABBaseUrl = savedInstanceState.getString("openHABBaseUrl");
            mLastKnownNetworkInfo = savedInstanceState.getParcelable("lastNetworkInfo");
            mOpenHABVersion = savedInstanceState.getInt("openHABVersion");
            mSitemapList = savedInstanceState.getParcelableArrayList("sitemapList");
            mSelectedSitemap = savedInstanceState.getParcelable("sitemap");
            mController.onRestoreInstanceState(savedInstanceState);
            String lastControllerClass = savedInstanceState.getString("controller");
            if (mSelectedSitemap != null
                    && !mController.getClass().getCanonicalName().equals(lastControllerClass)) {
                // Our controller type changed, so we need to make the new controller aware of the
                // page hierarchy. If the controller didn't change, the hierarchy will be restored
                // via the fragment state restoration.
                mController.updateFragmentState();
            }
        }

        if (mSitemapList == null) {
            mSitemapList = new ArrayList<>();
        }

        if (getIntent() != null) {
            processIntent(getIntent());
        }

        if (isFullscreenEnabled()) {
            registerReceiver(dreamReceiver, new IntentFilter("android.intent.action.DREAMING_STARTED"));
            registerReceiver(dreamReceiver, new IntentFilter("android.intent.action.DREAMING_STOPPED"));
            checkFullscreen();
        }

        //  Create a new boolean and preference and set it to true
        boolean isFirstStart = mSettings.getBoolean(Constants.PREFERENCE_FIRST_START, true);

        SharedPreferences.Editor prefsEdit = sharedPrefs.edit();
        //  If the activity has never started before...
        if (isFirstStart) {

            //  Launch app intro
            final Intent i = new Intent(OpenHABMainActivity.this, IntroActivity.class);
            startActivity(i);

            prefsEdit.putBoolean("firstStart", false).apply();
        }

        OnUpdateBroadcastReceiver.updateComparableVersion(prefsEdit);
        prefsEdit.apply();
    }

    private void processIntent(Intent intent) {
        Log.d(TAG, "Intent != null");
        if (intent.getAction() != null) {
            Log.d(TAG, "Intent action = " + intent.getAction());
            if (intent.getAction().equals("android.nfc.action.NDEF_DISCOVERED")) {
                Log.d(TAG, "This is NFC action");
                if (intent.getDataString() != null) {
                    Log.d(TAG, "NFC data = " + intent.getDataString());
                    mNfcData = intent.getDataString();
                }
            } else if (intent.getAction().equals(GcmIntentService.ACTION_NOTIFICATION_SELECTED)) {
                onNotificationSelected(intent);
            } else if (intent.getAction().equals("android.intent.action.VIEW")) {
                Log.d(TAG, "This is URL Action");
                mNfcData = intent.getDataString();
            }
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Sync the toggle state after onRestoreInstanceState has occurred.
        mDrawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();

        NetworkConnectivityInfo networkInfo =
                NetworkConnectivityInfo.currentNetworkConnectivityInfo(this);
        if (mLastKnownNetworkInfo == null || !mLastKnownNetworkInfo.equals(networkInfo)) {
            // Connectivity changed since last resume, reload UI
            mController.resetState();
            mLastKnownNetworkInfo = networkInfo;

            mOpenHABTracker = new OpenHABTracker(this, openHABServiceType);
            mOpenHABTracker.start();
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, ((Object) this).getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        if (NfcAdapter.getDefaultAdapter(this) != null)
            NfcAdapter.getDefaultAdapter(this).enableForegroundDispatch(this, pendingIntent, null, null);
        if (!TextUtils.isEmpty(mNfcData)) {
            Log.d(TAG, "We have NFC data from launch");
        }

        updateTitle();
        checkFullscreen();
    }

    /**
     * Overriding onStop to enable Google Analytics stats collection
     */
    @Override
    public void onStop() {
        Log.d(TAG, "onStop()");
        super.onStop();
        if (mOpenHABTracker != null) {
            mOpenHABTracker.stop();
        }
        if(selectSitemapDialog != null && selectSitemapDialog.isShowing()) {
            selectSitemapDialog.dismiss();
        }
    }

    @Override
    public boolean serverReturnsJson() {
        return mOpenHABVersion != 1;
    }

    @Override
    public void onPageUpdated(String pageUrl, String pageTitle, List<OpenHABWidget> widgets) {
        mController.onPageUpdated(pageUrl, pageTitle, widgets);
    }

    public void triggerPageUpdate(String pageUrl, boolean forceReload) {
        mController.triggerPageUpdate(pageUrl, forceReload);
    }

    private void setupToolbar() {
        mToolbar = (Toolbar) findViewById(R.id.openhab_toolbar);
        setSupportActionBar(mToolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        // ProgressBar layout params inside the toolbar have to be done programmatically
        // because it doesn't work through layout file :-(
        mProgressBar = (ProgressBar) mToolbar.findViewById(R.id.toolbar_progress_bar);
        mProgressBar.setLayoutParams(new Toolbar.LayoutParams(Gravity.END | Gravity.CENTER_VERTICAL));
        setProgressIndicatorVisible(true);
    }

    private void setupDrawer() {

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        final ListView drawerList = (ListView) findViewById(R.id.left_drawer);
        mDrawerToggle = new ActionBarDrawerToggle(OpenHABMainActivity.this, mDrawerLayout,
                R.string.drawer_open, R.string.drawer_close) {
            @Override
            public void onDrawerOpened(View drawerView) {
                loadSitemapList(openHABBaseUrl);
                super.onDrawerOpened(drawerView);
            }
        };
        mDrawerLayout.setDrawerListener(mDrawerToggle);
        mDrawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);

        mDrawerItemList = new ArrayList<>();
        mDrawerAdapter = new OpenHABDrawerAdapter(this, R.layout.openhabdrawer_sitemap_item, mDrawerItemList);
        mDrawerAdapter.setOpenHABUsername(openHABUsername);
        mDrawerAdapter.setOpenHABPassword(openHABPassword);
        drawerList.setAdapter(mDrawerAdapter);
        drawerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int item, long l) {
                Log.d(TAG, "Drawer selected item " + String.valueOf(item));
                if (mDrawerItemList != null && mDrawerItemList.get(item).getItemType() == OpenHABDrawerItem.DrawerItemType.SITEMAP_ITEM) {
                    Log.d(TAG, "This is sitemap " + mDrawerItemList.get(item).getSiteMap().getLink());
                    mDrawerLayout.closeDrawers();
                    openSitemap(mDrawerItemList.get(item).getSiteMap());
                } else {
                    Log.d(TAG, "This is not sitemap");
                    if (mDrawerItemList.get(item).getTag() == DRAWER_NOTIFICATIONS) {
                        Log.d(TAG, "Notifications selected");
                        mDrawerLayout.closeDrawers();
                        OpenHABMainActivity.this.openNotifications();
                    } else if (mDrawerItemList.get(item).getTag() == DRAWER_PREFERENCES) {
                        Intent settingsIntent = new Intent(OpenHABMainActivity.this, OpenHABPreferencesActivity.class);
                        startActivityForResult(settingsIntent, SETTINGS_REQUEST_CODE);
                        Util.overridePendingTransition(OpenHABMainActivity.this, false);
                    } else if (mDrawerItemList.get(item).getTag() == DRAWER_ABOUT) {
                        OpenHABMainActivity.this.openAbout();
                    }
                }
            }
        });
        loadDrawerItems();
    }

    private void openAbout() {
        Intent aboutIntent = new Intent(this, OpenHABAboutActivity.class);
        aboutIntent.putExtra(OpenHABVoiceService.OPENHAB_BASE_URL_EXTRA, openHABBaseUrl);
        aboutIntent.putExtra("username", openHABUsername);
        aboutIntent.putExtra("password", openHABPassword);
        aboutIntent.putExtra("openHABVersion", mOpenHABVersion);

        startActivityForResult(aboutIntent, INFO_REQUEST_CODE);
        Util.overridePendingTransition(this, false);
    }

    public void onOpenHABTracked(String baseUrl) {
        openHABBaseUrl = baseUrl;
        if (baseUrl.equals(
                Util.normalizeUrl(mSettings.getString(Constants.PREFERENCE_URL, null)))) {
            openHABUsername = mSettings.getString(Constants.PREFERENCE_LOCAL_USERNAME, null);
            openHABPassword = mSettings.getString(Constants.PREFERENCE_LOCAL_PASSWORD, null);
        } else {
            openHABUsername = mSettings.getString(Constants.PREFERENCE_REMOTE_USERNAME, null);
            openHABPassword = mSettings.getString(Constants.PREFERENCE_REMOTE_PASSWORD, null);
        }

        mAsyncHttpClient.setBasicAuth(openHABUsername, openHABPassword, true);

        mDrawerAdapter.setOpenHABBaseUrl(openHABBaseUrl);
        mDrawerAdapter.setOpenHABUsername(openHABUsername);
        mDrawerAdapter.setOpenHABPassword(openHABPassword);

        if (!TextUtils.isEmpty(mNfcData)) {
            onNfcTag(mNfcData);
        } else {
            final String url = baseUrl + "rest/bindings";
            mAsyncHttpClient.get(url, new MyHttpClient.TextResponseHandler() {
                @Override
                public void onFailure(Call call, int statusCode, Headers headers, String responseString, Throwable throwable) {
                    mOpenHABVersion = 1;
                    Log.d(TAG, "openHAB version 1 - got error " + throwable + " accessing " + url);
                    mAsyncHttpClient.addHeader("Accept", "application/xml");
                    selectSitemap(openHABBaseUrl);
                }

                @Override
                public void onSuccess(Call call, int statusCode, Headers headers, String responseString) {
                    mOpenHABVersion = 2;
                    Log.d(TAG, "openHAB version 2");
                    selectSitemap(openHABBaseUrl);
                }
            });
        }
    }

    public void onError(String error) {
        showMessageToUser(error, DIALOG, ALWAYS);
    }

    public void showMessageToUser(String message, int messageType, int logLevel) {
        showMessageToUser(message, messageType, logLevel, 0, null);
    }
    /**
     * Shows a message to the user.
     * You might want to send two messages: One detailed one with
     * logLevel Constants.MESSAGES.LOGLEVEL.DEBUG and one simple message with
     * Constants.MESSAGES.LOGLEVEL.NO_DEBUG
     *
     * @param message message to show
     * @param messageType can be one of Constants.MESSAGES.*
     * @param logLevel can be on of Constants.MESSAGES.LOGLEVEL.*
     * @param actionMessage A StringRes to use as a message for an action, if supported by the
     *                      messageType (can be 0)
     * @param actionListener A listener that should be executed when the action message is clicked.
     */
    public void showMessageToUser(String message, int messageType, int logLevel,
                                  int actionMessage, @Nullable View.OnClickListener actionListener) {
        if (isFinishing() || message == null) {
            return;
        }
        boolean debugEnabled = mSettings.getBoolean(Constants.PREFERENCE_DEBUG_MESSAGES, false);
        String remoteUrl = mSettings.getString(Constants.PREFERENCE_ALTURL, "");
        String localUrl = mSettings.getString(Constants.PREFERENCE_URL, "");

        // if debug mode is enabled, show all messages, except those with logLevel 4
        if((debugEnabled && logLevel == Constants.MESSAGES.LOGLEVEL.NO_DEBUG) ||
                (!debugEnabled && logLevel == Constants.MESSAGES.LOGLEVEL.DEBUG)) {
            return;
        }
        switch (logLevel) {
            case Constants.MESSAGES.LOGLEVEL.REMOTE:
                if (remoteUrl.length() > 1) {
                    Log.d(TAG, "Remote URL set, show message: " + message);
                } else {
                    Log.d(TAG, "No remote URL set, don't show message: " + message);
                    return;
                }
                break;
            case Constants.MESSAGES.LOGLEVEL.LOCAL:
                if (localUrl.length() > 1) {
                    Log.d(TAG, "Local URL set, show message: " + message);
                } else {
                    Log.d(TAG, "No local URL set, don't show message: " + message);
                    return;
                }
                break;
        }

        switch (messageType) {
            case Constants.MESSAGES.DIALOG:
                AlertDialog.Builder builder = new AlertDialog.Builder(OpenHABMainActivity.this);
                builder.setMessage(message)
                        .setPositiveButton(getText(android.R.string.ok), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                            }
                        });
                AlertDialog alert = builder.create();
                alert.show();
                break;
            case Constants.MESSAGES.SNACKBAR:
                Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG);
                if (actionListener != null && actionMessage != 0) {
                    snackbar.setAction(actionMessage, actionListener);
                    snackbar.setDuration(Snackbar.LENGTH_INDEFINITE);
                }
                snackbar.show();
                break;
            case Constants.MESSAGES.TOAST:
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                break;
            default:
                throw new IllegalArgumentException("Message type not implemented");
        }
    }

    public void onBonjourDiscoveryStarted() {
        mProgressDialog = ProgressDialog.show(this, "",
                getString(R.string.info_discovery), true);
    }

    public void onBonjourDiscoveryFinished() {
        try {
            mProgressDialog.dismiss();
            mProgressDialog = null;
        } catch (Exception e) {
            // This is to catch "java.lang.IllegalArgumentException: View not attached to window manager"
            // exception which happens if user quited app during discovery
        }
    }

    private void loadSitemapList(String baseUrl) {
        Log.d(TAG, "Loading sitemap list from " + baseUrl + "rest/sitemaps");
        setProgressIndicatorVisible(true);
        mAsyncHttpClient.get(baseUrl + "rest/sitemaps", new DefaultHttpResponseHandler() {
            @Override
            public void onSuccess(Call call, int statusCode, Headers headers, byte[] responseBody) {
                setProgressIndicatorVisible(false);
                mSitemapList.clear();
                // If openHAB's version is 1, get sitemap list from XML
                if (mOpenHABVersion == 1) {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    try {
                        DocumentBuilder builder = dbf.newDocumentBuilder();
                        Document sitemapsXml = builder.parse(new ByteArrayInputStream(responseBody));
                        mSitemapList.addAll(Util.parseSitemapList(sitemapsXml));
                    } catch (ParserConfigurationException | SAXException | IOException e) {
                        e.printStackTrace();
                    }
                    // Later versions work with JSON
                } else {
                    try {
                        String jsonString = new String(responseBody, "UTF-8");
                        JSONArray jsonArray = new JSONArray(jsonString);
                        mSitemapList.addAll(Util.parseSitemapList(jsonArray));
                        Log.d(TAG, jsonArray.toString());
                    } catch (UnsupportedEncodingException | JSONException e) {
                        e.printStackTrace();
                    }
                }
                if (mSitemapList.size() == 0) {
                    return;
                }
                loadDrawerItems();
            }
        });
    }

    /**
     * Get sitemaps from openHAB, if user already configured preffered sitemap
     * just open it. If no preffered sitemap is configured - let user select one.
     *
     * @param baseUrl an absolute base URL of openHAB to open
     * @return void
     */

    private void selectSitemap(final String baseUrl) {
        Log.d(TAG, "Loading sitemap list from " + baseUrl + "rest/sitemaps");
        setProgressIndicatorVisible(true);
        mAsyncHttpClient.get(baseUrl + "rest/sitemaps", new DefaultHttpResponseHandler() {

            @Override
            public void onSuccess(Call call, int statusCode, Headers headers, byte[] responseBody) {
                Log.d(TAG, new String(responseBody));
                setProgressIndicatorVisible(false);
                mSitemapList.clear();
                // If openHAB's version is 1, get sitemap list from XML
                if (mOpenHABVersion == 1) {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    try {
                        DocumentBuilder builder = dbf.newDocumentBuilder();
                        Document sitemapsXml = builder.parse(new ByteArrayInputStream(responseBody));
                        mSitemapList.addAll(Util.parseSitemapList(sitemapsXml));
                    } catch (ParserConfigurationException | SAXException | IOException e) {
                        e.printStackTrace();
                    }
                    // Later versions work with JSON
                } else {
                    try {
                        String jsonString = new String(responseBody, "UTF-8");
                        JSONArray jsonArray = new JSONArray(jsonString);
                        mSitemapList.addAll(Util.parseSitemapList(jsonArray));
                        Log.d(TAG, jsonArray.toString());
                    } catch (UnsupportedEncodingException | JSONException e) {
                        e.printStackTrace();
                    }
                }
                // Now work with sitemaps list
                if (mSitemapList.size() == 0) {
                    // Got an empty sitemap list!
                    Log.e(TAG, "openHAB returned empty sitemap list");
                    showAlertDialog(getString(R.string.error_empty_sitemap_list));
                    return;
                }
                loadDrawerItems();

                // Check if we have a sitemap configured to use
                SharedPreferences settings =
                        PreferenceManager.getDefaultSharedPreferences(OpenHABMainActivity.this);
                String configuredSitemap = settings.getString(Constants.PREFERENCE_SITEMAP_NAME, "");
                // If we have sitemap configured
                if (configuredSitemap.length() > 0) {
                    // Configured sitemap is on the list we got, open it!
                    if (Util.sitemapExists(mSitemapList, configuredSitemap)) {
                        Log.d(TAG, "Configured sitemap is on the list");
                        OpenHABSitemap selectedSitemap = Util.getSitemapByName(mSitemapList,
                                configuredSitemap);
                        openSitemap(selectedSitemap);
                        // Configured sitemap is not on the list we got!
                    } else {
                        Log.d(TAG, "Configured sitemap is not on the list");
                        if (mSitemapList.size() == 1) {
                            Log.d(TAG, "Got only one sitemap");
                            SharedPreferences.Editor preferencesEditor = settings.edit();
                            preferencesEditor.putString(Constants.PREFERENCE_SITEMAP_NAME,
                                    mSitemapList.get(0).getName());
                            preferencesEditor.putString(Constants.PREFERENCE_SITEMAP_LABEL,
                                    mSitemapList.get(0).getLabel());
                            preferencesEditor.apply();
                            openSitemap(mSitemapList.get(0));
                        } else {
                            Log.d(TAG, "Got multiply sitemaps, user have to select one");
                            showSitemapSelectionDialog(mSitemapList);
                        }
                    }
                    // No sitemap is configured to use
                } else {
                    // We got only one single sitemap from openHAB, use it
                    if (mSitemapList.size() == 1) {
                        Log.d(TAG, "Got only one sitemap");
                        SharedPreferences.Editor preferencesEditor = settings.edit();
                        preferencesEditor.putString(Constants.PREFERENCE_SITEMAP_NAME,
                                mSitemapList.get(0).getName());
                        preferencesEditor.putString(Constants.PREFERENCE_SITEMAP_LABEL,
                                mSitemapList.get(0).getLabel());
                        preferencesEditor.apply();
                        openSitemap(mSitemapList.get(0));
                    } else {
                        Log.d(TAG, "Got multiply sitemaps, user have to select one");
                        showSitemapSelectionDialog(mSitemapList);
                    }
                }
            }
        });
    }

    private void showSitemapSelectionDialog(final List<OpenHABSitemap> sitemapList) {
        Log.d(TAG, "Opening sitemap selection dialog");
        final List<String> sitemapLabelList = new ArrayList<String>();
        for (int i = 0; i < sitemapList.size(); i++) {
            sitemapLabelList.add(sitemapList.get(i).getLabel());
        }
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(OpenHABMainActivity.this);
        dialogBuilder.setTitle(getString(R.string.mainmenu_openhab_selectsitemap));
        try {
            selectSitemapDialog = dialogBuilder.setItems(sitemapLabelList.toArray(new CharSequence[sitemapLabelList.size()]),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int item) {
                            Log.d(TAG, "Selected sitemap " + sitemapList.get(item).getName());
                            SharedPreferences settings =
                                    PreferenceManager.getDefaultSharedPreferences(OpenHABMainActivity.this);
                            SharedPreferences.Editor preferencesEditor = settings.edit();
                            preferencesEditor.putString(Constants.PREFERENCE_SITEMAP_NAME, sitemapList.get(item).getName());
                            preferencesEditor.putString(Constants.PREFERENCE_SITEMAP_LABEL, sitemapList.get(item).getLabel());
                            preferencesEditor.apply();
                            openSitemap(sitemapList.get(item));
                        }
                    }).show();
        } catch (WindowManager.BadTokenException e) {
            e.printStackTrace();
        }
    }

    public void openNotifications() {
        mController.openNotifications(getNotificationSettings());
        mDrawerToggle.setDrawerIndicatorEnabled(false);
    }

    private void openSitemap(OpenHABSitemap sitemap) {
        Log.i(TAG, "Opening sitemap at " + sitemap.getHomepageLink());
        if (mSelectedSitemap != null && mSelectedSitemap.equals(sitemap)) {
            return;
        }
        mSelectedSitemap = sitemap;
        mController.openSitemap(sitemap);
        updateTitle();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem voiceRecognitionItem = menu.findItem(R.id.mainmenu_voice_recognition);
        voiceRecognitionItem.setVisible(SpeechRecognizer.isRecognitionAvailable(this));
        voiceRecognitionItem.getIcon()
                .setColorFilter(ContextCompat.getColor(this, R.color.light), PorterDuff.Mode.SRC_IN);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        //clicking the back navigation arrow
        if (item.getItemId() == android.R.id.home && mController.canGoBack()) {
            mController.goBack();
            updateTitle();
            return true;
        }

        //clicking the hamburger menu
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        //menu items
        switch (item.getItemId()) {
            case R.id.mainmenu_voice_recognition:
                launchVoiceRecognition();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, String.format("onActivityResult requestCode = %d, resultCode = %d", requestCode, resultCode));
        switch (requestCode) {
            case SETTINGS_REQUEST_CODE:
                // Restart app after preferences
                Log.d(TAG, "Restarting after settings");
                // Get launch intent for application
                Intent restartIntent = getPackageManager()
                        .getLaunchIntentForPackage(getPackageName());
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                // Finish current activity
                finish();
                // Start launch activity
                startActivity(restartIntent);
                break;
            case WRITE_NFC_TAG_REQUEST_CODE:
                Log.d(TAG, "Got back from Write NFC tag");
                break;
            default:
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        Log.d(TAG, "onSaveInstanceState");
        // Save UI state changes to the savedInstanceState.
        // This bundle will be passed to onCreate if the process is
        // killed and restarted.
        savedInstanceState.putString("openHABBaseUrl", openHABBaseUrl);
        savedInstanceState.putParcelable("lastNetworkInfo", mLastKnownNetworkInfo);
        savedInstanceState.putInt("openHABVersion", mOpenHABVersion);
        savedInstanceState.putParcelableArrayList("sitemapList", mSitemapList);
        savedInstanceState.putParcelable("sitemap", mSelectedSitemap);
        savedInstanceState.putString("controller", mController.getClass().getCanonicalName());
        mController.onSaveInstanceState(savedInstanceState);
        super.onSaveInstanceState(savedInstanceState);
    }

    private void onNotificationSelected(Intent intent) {
        Log.d(TAG, "Notification was selected");
        if (intent.hasExtra(GcmIntentService.EXTRA_NOTIFICATION_ID)) {
            Log.d(TAG, String.format("Notification id = %d",
                    intent.getExtras().getInt(GcmIntentService.EXTRA_NOTIFICATION_ID)));
            // Make a fake broadcast intent to hide intent on other devices
            Intent deleteIntent = new Intent(this, NotificationDeletedBroadcastReceiver.class);
            deleteIntent.setAction(GcmIntentService.ACTION_NOTIFICATION_DELETED);
            deleteIntent.putExtra(GcmIntentService.EXTRA_NOTIFICATION_ID, intent.getExtras().getInt(GcmIntentService.EXTRA_NOTIFICATION_ID));
            sendBroadcast(deleteIntent);
        }

        if (getNotificationSettings() != null) {
            openNotifications();
        }

        if (intent.hasExtra(GcmIntentService.EXTRA_MSG)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.dlg_notification_title));
            builder.setMessage(intent.getExtras().getString(GcmIntentService.EXTRA_MSG));
            builder.setPositiveButton(getString(android.R.string.ok), null);
            AlertDialog dialog = builder.create();
            dialog.show();

        }
    }

    /**
     * This method processes new intents generated by NFC subsystem
     *
     * @param nfcData - a data which NFC subsystem got from the NFC tag
     */
    public void onNfcTag(String nfcData) {
        Log.d(TAG, "onNfcTag()");
        Uri openHABURI = Uri.parse(nfcData);
        Log.d(TAG, "NFC Scheme = " + openHABURI.getScheme());
        Log.d(TAG, "NFC Host = " + openHABURI.getHost());
        Log.d(TAG, "NFC Path = " + openHABURI.getPath());
        String nfcItem = openHABURI.getQueryParameter("item");
        String nfcCommand = openHABURI.getQueryParameter("command");
        String nfcItemType = openHABURI.getQueryParameter("itemType");
        // If there is no item parameter it means tag contains only sitemap page url
        if (TextUtils.isEmpty(nfcItem)) {
            Log.d(TAG, "This is a sitemap tag without parameters");
            // Form the new sitemap page url
            String newPageUrl = openHABBaseUrl + "rest/sitemaps" + openHABURI.getPath();
            mController.openPage(newPageUrl);
        } else {
            Log.d(TAG, "Target item = " + nfcItem);
            String url = openHABBaseUrl + "rest/items/" + nfcItem;
            Util.sendItemCommand(mAsyncHttpClient, url, nfcCommand);
            // if mNfcData is not empty, this means we were launched with NFC touch
            // and thus need to autoexit after an item action
            if (!TextUtils.isEmpty(mNfcData))
                finish();
        }
        mNfcData = "";
    }

    public void onWidgetSelected(OpenHABLinkedPage linkedPage, OpenHABWidgetListFragment source) {
        Log.i(TAG, "Got widget link = " + linkedPage.getLink());
        mController.openPage(linkedPage, source);
        updateTitle();
    }

    void updateTitle() {
        String title = mController.getCurrentTitle();
        if (title != null) {
            setTitle(title);
        } else if (mSelectedSitemap != null) {
            setTitle(mSelectedSitemap.getLabel());
        } else {
            setTitle(R.string.app_name);
        }
        mDrawerToggle.setDrawerIndicatorEnabled(!mController.canGoBack());
    }

    @Override
    public void onBackPressed() {
        if (mController.canGoBack()) {
            mController.goBack();
            updateTitle();
        } else if (!isFullscreenEnabled()) { //in fullscreen don't continue back which would exit the app
            super.onBackPressed();
        }
    }

    public RecyclerView.RecycledViewPool getViewPool() {
        return mViewPool;
    }

    public void setProgressIndicatorVisible(boolean visible) {
        if (mProgressBar != null) {
            mProgressBar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void launchVoiceRecognition() {
        Intent callbackIntent = new Intent(this, OpenHABVoiceService.class);
        callbackIntent.putExtra(OpenHABVoiceService.OPENHAB_BASE_URL_EXTRA, openHABBaseUrl);
        PendingIntent openhabPendingIntent = PendingIntent.getService(this, 0, callbackIntent, 0);

        Intent speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // Display an hint to the user about what he should say.
        speechIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.info_voice_input));
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechIntent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, openhabPendingIntent);

        try {
            startActivity(speechIntent);
        } catch(ActivityNotFoundException e) {
            // Speech not installed?
            // todo url doesnt seem to work anymore
            // not sure, if this is called
            Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://market.android.com/details?id=com.google.android.voicesearch"));
            startActivity(browserIntent);
        }
    }

    private void showAlertDialog(String alertMessage) {
        if (this.isFinishing())
            return;
       showMessageToUser(alertMessage, DIALOG, ALWAYS);
    }

    private void showCertificateDialog(final int decisionId, String certMessage) {
        if (this.isFinishing())
            return;
        AlertDialog.Builder builder = new AlertDialog.Builder(OpenHABMainActivity.this);
        builder.setMessage(certMessage)
                .setTitle(R.string.mtm_accept_cert);
        builder.setPositiveButton(R.string.mtm_decision_always, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialogInterface, int i) {
                Log.d(TAG, "User decided to always accept unknown certificate");
//                MemorizingTrustManager.interactResult(decisionId, MTMDecision.DECISION_ALWAYS);
                sendMTMDecision(decisionId, MTMDecision.DECISION_ALWAYS);
            }
        });
        builder.setNeutralButton(R.string.mtm_decision_once, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialogInterface, int i) {
                Log.d(TAG, "User decided to accept unknown certificate once");
//                MemorizingTrustManager.interactResult(decisionId, MTMDecision.DECISION_ONCE);
                sendMTMDecision(decisionId, MTMDecision.DECISION_ONCE);
            }
        });
        builder.setNegativeButton(R.string.mtm_decision_abort, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialogInterface, int i) {
                Log.d(TAG, "User decided to abort unknown certificate");
//                MemorizingTrustManager.interactResult(decisionId, MTMDecision.DECISION_ABORT);
                sendMTMDecision(decisionId, MTMDecision.DECISION_ABORT);
            }
        });
        AlertDialog certAlert = builder.create();
        certAlert.show();
    }

    void sendMTMDecision(int decisionId, int decision) {
        Log.d(TAG, "Sending decision to MTM");
        Intent i = new Intent(MemorizingTrustManager.DECISION_INTENT + "/" + getPackageName());
        i.putExtra(MemorizingTrustManager.DECISION_INTENT_ID, decisionId);
        i.putExtra(MemorizingTrustManager.DECISION_INTENT_CHOICE, decision);
        sendBroadcast(i);
    }

    public void makeDecision(int decisionId, String certMessage) {
        Log.d(TAG, String.format("MTM is asking for decision on id = %d", decisionId));
        if (mSettings.getBoolean(Constants.PREFERENCE_SSLCERT, false))
            MemorizingTrustManager.interactResult(decisionId, MTMDecision.DECISION_ONCE);
        else
            showCertificateDialog(decisionId, certMessage);
    }

    public String getOpenHABBaseUrl() {
        return openHABBaseUrl;
    }

    public void setOpenHABBaseUrl(String openHABBaseUrl) {
        this.openHABBaseUrl = openHABBaseUrl;
    }

    public String getOpenHABUsername() {
        return openHABUsername;
    }

    public void setOpenHABUsername(String openHABUsername) {
        this.openHABUsername = openHABUsername;
    }

    public String getOpenHABPassword() {
        return openHABPassword;
    }

    public void setOpenHABPassword(String openHABPassword) {
        this.openHABPassword = openHABPassword;
    }

    public void gcmRegisterBackground() {
        OpenHABMainActivity.GCM_SENDER_ID = null;
        // if no notification settings can be constructed, no GCM registration can be made.
        if (getNotificationSettings() == null)
            return;

        if (mGcm == null)
            mGcm = GoogleCloudMessaging.getInstance(this);

        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                GoogleCloudMessageConnector connector =
                        new GoogleCloudMessageConnector(getNotificationSettings(), deviceId, mGcm);

                if (connector.register()) {
                    OpenHABMainActivity.GCM_SENDER_ID = getNotificationSettings().getSenderId();
                }
                return null;
            }

            @Override
            protected void onPostExecute(String regId) {}
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null, null, null);
    }

    /**
     * Returns the notification settings object
     * @return Returns the NotificationSettings or null, if openHAB-cloud isn't used
     */
    public NotificationSettings getNotificationSettings() {
        if (mNotifySettings == null) {
            // We need settings
            if (mSettings == null)
                return null;

            // We need remote URL, username and password, without them we can't connect to openhab-cloud
            String remoteUrl = mSettings.getString(Constants.PREFERENCE_ALTURL, null);
            if (TextUtils.isEmpty(remoteUrl) || TextUtils.isEmpty(openHABUsername) || TextUtils.isEmpty(openHABPassword)) {
                Log.d(TAG, "Remote URL, username or password are empty, no GCM registration will be made");
                return null;
            }
            final URL baseUrl;
            try {
                baseUrl = new URL(remoteUrl);
            } catch(MalformedURLException ex) {
                Log.d(TAG, "Could not parse the baseURL to an URL: " + ex.getMessage());
                return null;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            MySyncHttpClient syncHttpClient = new MySyncHttpClient(this,
                    prefs.getBoolean(Constants.PREFERENCE_SSLHOST, false),
                    prefs.getBoolean(Constants.PREFERENCE_SSLCERT, false));
            mNotifySettings = new NotificationSettings(baseUrl, syncHttpClient);
            mNotifySettings.setOpenHABCloudUsername(
                    mSettings.getString(Constants.PREFERENCE_REMOTE_USERNAME, openHABUsername));
            mNotifySettings.setOpenHABCloudPassword(
                    mSettings.getString(Constants.PREFERENCE_REMOTE_PASSWORD, openHABPassword));
        }
        return mNotifySettings;
    }

    /**
     * If fullscreen is enabled and we are on at least android 4.4 set
     * the system visibility to fullscreen + immersive + noNav
     *
     * @author Dan Cunningham
     */
    protected void checkFullscreen() {
        if (isFullscreenEnabled()) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    /**
     * If we are 4.4 we can use fullscreen mode and Daydream features
     */
    protected boolean isFullscreenEnabled() {
        boolean supportsKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        boolean fullScreen = mSettings.getBoolean("default_openhab_fullscreen", false);
        return supportsKitKat && fullScreen;
    }


    private void loadDrawerItems() {
        mDrawerItemList.clear();
        if (mSitemapList != null) {
            mDrawerItemList.add(OpenHABDrawerItem.headerItem(getString(R.string.mainmenu_openhab_sitemaps)));
            for (OpenHABSitemap sitemap : mSitemapList) {
                mDrawerItemList.add(new OpenHABDrawerItem(sitemap));
            }
        }
        mDrawerItemList.add(OpenHABDrawerItem.dividerItem());
        int iconColor = ContextCompat.getColor(this, R.color.colorAccent_themeDark);
        Drawable notificationDrawable = getResources().getDrawable(R.drawable
                .ic_notifications_black_24dp);
        notificationDrawable.setColorFilter(
                iconColor,
                PorterDuff.Mode.SRC_IN
        );
        if (getNotificationSettings() != null) {
            mDrawerItemList.add(OpenHABDrawerItem.menuItem(
                    "Notifications",
                    notificationDrawable,
                    DRAWER_NOTIFICATIONS
            ));
        }

        Drawable settingsDrawable = getResources().getDrawable(R.drawable
                .ic_settings_black_24dp);
        settingsDrawable.setColorFilter(
                iconColor,
                PorterDuff.Mode.SRC_IN
        );
        mDrawerItemList.add(OpenHABDrawerItem.menuItem(
                getString(R.string.mainmenu_openhab_preferences),
                settingsDrawable,
                DRAWER_PREFERENCES
        ));

        Drawable aboutDrawable = getResources().getDrawable(R.drawable.ic_info_outline);
        aboutDrawable.setColorFilter(
                iconColor,
                PorterDuff.Mode.SRC_IN);
        mDrawerItemList.add(OpenHABDrawerItem.menuItem(
                getString(R.string.about_title),
                aboutDrawable,
                DRAWER_ABOUT
        ));
        mDrawerAdapter.notifyDataSetChanged();
    }
}
