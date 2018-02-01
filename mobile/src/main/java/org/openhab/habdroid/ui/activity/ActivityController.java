/*
 * Copyright (c) 2018, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.ui.activity;

import android.os.Bundle;
import android.support.annotation.AnimRes;
import android.support.annotation.LayoutRes;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.util.Pair;
import android.view.View;

import org.openhab.habdroid.R;
import org.openhab.habdroid.core.notifications.NotificationSettings;
import org.openhab.habdroid.model.OpenHABLinkedPage;
import org.openhab.habdroid.model.OpenHABSitemap;
import org.openhab.habdroid.model.OpenHABWidget;
import org.openhab.habdroid.ui.OpenHABMainActivity;
import org.openhab.habdroid.ui.OpenHABNotificationFragment;
import org.openhab.habdroid.ui.OpenHABWidgetListFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public abstract class ActivityController {
    private final OpenHABMainActivity mActivity;
    protected final FragmentManager mFm;
    protected OpenHABSitemap mCurrentSitemap;
    protected OpenHABWidgetListFragment mSitemapFragment;
    protected final Stack<Pair<OpenHABLinkedPage, OpenHABWidgetListFragment>> mPageStack = new Stack<>();
    private PageConnectionHolderFragment mConnectionFragment;

    protected ActivityController(OpenHABMainActivity activity) {
        mActivity = activity;
        mFm = activity.getSupportFragmentManager();

        mConnectionFragment = (PageConnectionHolderFragment) mFm.findFragmentByTag("connections");
        if (mConnectionFragment == null) {
            mConnectionFragment = new PageConnectionHolderFragment();
            mFm.beginTransaction().add(mConnectionFragment, "connections").commit();
        }
    }

    public void onSaveInstanceState(Bundle state) {
        ArrayList<OpenHABLinkedPage> pages = new ArrayList<>();
        for (Pair<OpenHABLinkedPage, OpenHABWidgetListFragment> item : mPageStack) {
            pages.add(item.first);
        }
        state.putParcelable("controllerSitemap", mCurrentSitemap);
        state.putParcelableArrayList("controllerPages", pages);
    }

    public void onRestoreInstanceState(Bundle state) {
        mCurrentSitemap = state.getParcelable("controllerSitemap");
        if (mCurrentSitemap != null) {
            mSitemapFragment = makeSitemapFragment(mCurrentSitemap);
        }

        ArrayList<OpenHABLinkedPage> oldStack = state.getParcelableArrayList("controllerPages");
        mPageStack.clear();
        for (OpenHABLinkedPage page : oldStack) {
            mPageStack.add(Pair.create(page, makePageFragment(page)));
        }
    }

    public void resetState() {
        mCurrentSitemap = null;
        mSitemapFragment = null;
        mPageStack.clear();
        updateFragmentState();
    }

    public void openSitemap(OpenHABSitemap sitemap) {
        mCurrentSitemap = sitemap;
        mSitemapFragment = makeSitemapFragment(sitemap);
        mPageStack.clear();
        updateFragmentState();
        updateConnectionState();
    }

    public void openPage(OpenHABLinkedPage page, OpenHABWidgetListFragment source) {
        mPageStack.push(Pair.create(page, makePageFragment(page)));
        updateFragmentState(FragmentUpdateReason.PAGE_ENTER);
        updateConnectionState();
    }

    public final void openPage(String url) {
        int toPop = -1;
        for (int i = 0; i < mPageStack.size(); i++) {
            if (mPageStack.get(i).first.getLink().equals(url)) {
                // page is already present
                toPop = mPageStack.size() - i - 1;
                break;
            }
        }
        if (toPop >= 0) {
            while (toPop-- > 0) {
                mPageStack.pop();
            }
            updateFragmentState();
        } else {
            // we didn't find it
            showTemporaryPage(OpenHABWidgetListFragment.withPage(url, null,
                    mActivity.getOpenHABBaseUrl(), mActivity.getOpenHABUsername(),
                    mActivity.getOpenHABPassword()));
        }
    }

    public void onPageUpdated(String pageUrl, String pageTitle, List<OpenHABWidget> widgets) {
        if (mSitemapFragment != null && pageUrl.equals(mSitemapFragment.getDisplayPageUrl())) {
            mSitemapFragment.update(pageTitle, widgets);
        } else {
            for (Pair<OpenHABLinkedPage, OpenHABWidgetListFragment> item : mPageStack) {
                if (pageUrl.equals(item.second.getDisplayPageUrl())) {
                    item.second.update(pageTitle, widgets);
                    break;
                }
            }
        }
    }

    public void triggerPageUpdate(String pageUrl, boolean forceReload) {
        mConnectionFragment.triggerUpdate(pageUrl, forceReload);
    }

    public final void openNotifications(NotificationSettings settings) {
        showTemporaryPage(makeNotificationsFragment(settings));
    }

    public void initViews(View contentView) {}
    public void updateFragmentState() {
        updateFragmentState(FragmentUpdateReason.PAGE_UPDATE);
        updateConnectionState();
    }

    public abstract String getCurrentTitle();
    public abstract @LayoutRes int getContentLayoutResource();
    protected abstract void updateFragmentState(FragmentUpdateReason reason);
    protected abstract void showTemporaryPage(Fragment page);

    public boolean canGoBack() {
        return !mPageStack.empty() || mFm.getBackStackEntryCount() > 0;
    }

    public boolean goBack() {
        if (mFm.getBackStackEntryCount() > 0) {
            mFm.popBackStackImmediate();
            return true;
        }
        if (!mPageStack.empty()) {
            mPageStack.pop();
            updateFragmentState(FragmentUpdateReason.BACK_NAVIGATION);
            updateConnectionState();
            return true;
        }
        return false;
    }

    protected void updateConnectionState() {
        List<String> pageUrls = new ArrayList<>();
        if (mSitemapFragment != null) {
            pageUrls.add(mSitemapFragment.getDisplayPageUrl());
        }
        for (Pair<OpenHABLinkedPage, OpenHABWidgetListFragment> item : mPageStack) {
            pageUrls.add(item.second.getDisplayPageUrl());
        }
        mConnectionFragment.updateActiveConnections(pageUrls);
    }

    private OpenHABWidgetListFragment makeSitemapFragment(OpenHABSitemap sitemap) {
        return OpenHABWidgetListFragment.withPage(sitemap.getHomepageLink(),
                sitemap.getLabel(), mActivity.getOpenHABBaseUrl(),
                mActivity.getOpenHABUsername(), mActivity.getOpenHABPassword());
    }

    private OpenHABWidgetListFragment makePageFragment(OpenHABLinkedPage page) {
        return OpenHABWidgetListFragment.withPage(page.getLink(),
                page.getTitle(), mActivity.getOpenHABBaseUrl(),
                mActivity.getOpenHABUsername(), mActivity.getOpenHABPassword());
    }

    protected OpenHABNotificationFragment makeNotificationsFragment(NotificationSettings settings) {
        return OpenHABNotificationFragment.newInstance(
                settings.getOpenHABCloudURL().toString(),
                settings.getOpenHABCloudUsername(),
                settings.getOpenHABCloudPassword());
    }

    protected enum FragmentUpdateReason {
        PAGE_ENTER,
        BACK_NAVIGATION,
        PAGE_UPDATE
    }


    protected static @AnimRes int determineEnterAnim(FragmentUpdateReason reason) {
        switch (reason) {
            case PAGE_ENTER: return R.anim.slide_in_right;
            case BACK_NAVIGATION: return R.anim.slide_in_left;
            default: return 0;
        }
    }

    protected static @AnimRes int determineExitAnim(FragmentUpdateReason reason) {
        switch (reason) {
            case PAGE_ENTER: return R.anim.slide_out_left;
            case BACK_NAVIGATION: return R.anim.slide_out_right;
            default: return 0;
        }
    }

    protected static int determineTransition(FragmentUpdateReason reason) {
        switch (reason) {
            case PAGE_ENTER: return FragmentTransaction.TRANSIT_FRAGMENT_OPEN;
            case BACK_NAVIGATION: return FragmentTransaction.TRANSIT_FRAGMENT_CLOSE;
            default: return FragmentTransaction.TRANSIT_FRAGMENT_FADE;
        }
    }
}
