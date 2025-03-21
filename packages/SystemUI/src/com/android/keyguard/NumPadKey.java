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

package com.android.keyguard;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;

public class NumPadKey extends ViewGroup {

    private final TextView mDigitText;
    private final LockPatternUtils mLockPatternUtils;
    private final PowerManager mPM;

    private int mDigit = -1;
    private int mTextViewResId;
    private PasswordTextView mTextView;

    private View.OnClickListener mListener = new View.OnClickListener() {
        @Override
        public void onClick(View thisView) {
            if (mTextView == null && mTextViewResId > 0) {
                final View v = NumPadKey.this.getRootView().findViewById(mTextViewResId);
                if (v != null && v instanceof PasswordTextView) {
                    mTextView = (PasswordTextView) v;
                }
            }
            if (mTextView != null && mTextView.isEnabled()) {
                mTextView.append(Character.forDigit(mDigit, 10));
            }
            userActivity();
        }
    };

    public void userActivity() {
        mPM.userActivity(SystemClock.uptimeMillis(), false);
    }

    public NumPadKey(Context context) {
        this(context, null);
    }

    public NumPadKey(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.numPadKeyStyle);
    }

    public NumPadKey(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, R.layout.keyguard_num_pad_key);
    }

    protected NumPadKey(Context context, AttributeSet attrs, int defStyle, int contentResource) {
        super(context, attrs, defStyle);
        setFocusable(true);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.NumPadKey);

        try {
            mDigit = a.getInt(R.styleable.NumPadKey_digit, mDigit);
            mTextViewResId = a.getResourceId(R.styleable.NumPadKey_textView, 0);
        } finally {
            a.recycle();
        }

        setOnClickListener(mListener);
        setOnHoverListener(new LiftToActivateListener(context));

        mLockPatternUtils = new LockPatternUtils(context);
        mPM = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(contentResource, this, true);

        mDigitText = (TextView) findViewById(R.id.digit_text);
        mDigitText.setText(Integer.toString(mDigit));

        setContentDescription(mDigitText.getText().toString());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            doHapticKeyClick();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        measureChildren(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int digitHeight = mDigitText.getMeasuredHeight();
        int top = getHeight() / 2 - digitHeight / 2;
        int centerX = getWidth() / 2;
        int left = centerX - mDigitText.getMeasuredWidth() / 2;
        int bottom = top + digitHeight;
        mDigitText.layout(left, top, left + mDigitText.getMeasuredWidth(), bottom);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick() {
        if (mLockPatternUtils.isTactileFeedbackEnabled()) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }
}
