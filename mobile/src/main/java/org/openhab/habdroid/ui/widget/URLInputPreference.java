package org.openhab.habdroid.ui.widget;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.preference.EditTextPreference;
import android.support.v7.preference.EditTextPreferenceDialogFragmentCompat;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import org.openhab.habdroid.R;

import java.net.MalformedURLException;
import java.net.URL;

public class URLInputPreference extends EditTextPreference {
    public URLInputPreference(Context context) {
        super(context);
    }

    public URLInputPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public URLInputPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public static class URLInputFragment extends EditTextPreferenceDialogFragmentCompat
            implements TextWatcher {
        private EditText mEditor;
        private boolean mUrlIsValid;

        public static URLInputFragment newInstance(String key) {
            final URLInputFragment fragment = new URLInputFragment();
            final Bundle b = new Bundle(1);
            b.putString(ARG_KEY, key);
            fragment.setArguments(b);
            return fragment;
        }

        @Override
        protected void onBindDialogView(View view) {
            super.onBindDialogView(view);
            mEditor = (EditText) view.findViewById(android.R.id.edit);
            mEditor.addTextChangedListener(this);
        }

        @Override
        public void onStart() {
            super.onStart();
            updateOkButtonState();
        }

        @Override
        public void beforeTextChanged(CharSequence charSequence, int start, int before, int count) {
        }

        @Override
        public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable editable) {
            if (TextUtils.isEmpty(editable)) {
                mUrlIsValid = true;
            } else {
                String url = editable.toString();
                if (url.contains("\n") || url.contains(" ")) {
                    mUrlIsValid = false;
                } else {
                    try {
                        new URL(url);
                        mUrlIsValid = true;
                    } catch (MalformedURLException e) {
                        mUrlIsValid = false;
                    }
                }
            }
            mEditor.setError(mUrlIsValid ? null : mEditor.getResources().getString(R.string.erorr_invalid_url));
            updateOkButtonState();
        }

        private void updateOkButtonState() {
            if (getDialog() instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) getDialog();
                Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (okButton != null) {
                    okButton.setEnabled(mUrlIsValid);
                }
            }
        }
    }
}
