/*
 * Copyright (c) 2018, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.ui.activity;

import android.support.annotation.LayoutRes;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Pair;
import android.view.View;

import org.openhab.habdroid.R;
import org.openhab.habdroid.model.OpenHABLinkedPage;
import org.openhab.habdroid.ui.OpenHABMainActivity;
import org.openhab.habdroid.ui.OpenHABWidgetListFragment;

@SuppressWarnings("unused") // instantiated via reflection
public class ActivityControllerTwoPane extends ActivityController {
    private View mRightContentView;

    public ActivityControllerTwoPane(OpenHABMainActivity activity) {
        super(activity);
    }

    @Override
    public void initViews(View contentView) {
        super.initViews(contentView);
        mRightContentView = contentView.findViewById(R.id.content_right);
        mRightContentView.setVisibility(View.GONE);
    }

    @Override
    protected void updateFragmentState(FragmentUpdateReason reason) {
        Pair<OpenHABLinkedPage, OpenHABWidgetListFragment> rightPair =
                mPageStack.empty() ? null : mPageStack.peek();
        OpenHABWidgetListFragment leftFragment = mPageStack.size() > 1
                ? mPageStack.get(mPageStack.size() - 2).second : mSitemapFragment;
        OpenHABWidgetListFragment rightFragment = rightPair != null ? rightPair.second : null;
        Fragment currentLeftFragment = mFm.findFragmentById(R.id.content_left);
        Fragment currentRightFragment = mFm.findFragmentById(R.id.content_right);

        FragmentTransaction removeTransaction = mFm.beginTransaction();
        boolean needRemove = false;
        if (currentLeftFragment != null && currentLeftFragment != leftFragment) {
            removeTransaction.remove(currentLeftFragment);
            needRemove = true;
        }
        if (currentRightFragment != null && currentRightFragment != rightFragment) {
            removeTransaction.remove(currentRightFragment);
            needRemove = true;
        }
        if (needRemove) {
            removeTransaction.commitNow();
        }

        FragmentTransaction ft = mFm.beginTransaction();
        ft.setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right);
        if (leftFragment != null) {
            ft.setCustomAnimations(determineEnterAnim(reason), determineExitAnim(reason));
            ft.replace(R.id.content_left, leftFragment);
            leftFragment.setHighlightedPageLink(rightPair != null ? rightPair.first.getLink() : null);
        }
        if (rightFragment != null) {
            ft.setCustomAnimations(0, 0);
            ft.setTransition(determineTransition(reason));
            ft.replace(R.id.content_right, rightFragment);
            rightFragment.setHighlightedPageLink(null);
        }
        ft.commit();

        mRightContentView.setVisibility(rightFragment != null ? View.VISIBLE : View.GONE);
    }

    @Override
    public void openPage(OpenHABLinkedPage page, OpenHABWidgetListFragment source) {
        Fragment currentLeftFragment = mFm.findFragmentById(R.id.content_left);
        if (source == currentLeftFragment && !mPageStack.empty()) {
            mPageStack.pop();
        }
        super.openPage(page, source);
    }

    @Override
    protected void showTemporaryPage(Fragment page) {
        mFm.beginTransaction()
                .replace(R.id.content_left, page)
                .addToBackStack(null)
                .commit();
        mRightContentView.setVisibility(View.GONE);
    }

    @Override
    public String getCurrentTitle() {
        return mPageStack.size() > 1 ? mPageStack.get(mPageStack.size() - 2).second.getTitle() : null;
    }

    @Override
    public @LayoutRes int getContentLayoutResource() {
        return R.layout.content_twopane;
    }
}
