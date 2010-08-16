/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.contacts.ui.widget;

import com.android.contacts.ContactsUtils;
import com.android.contacts.R;
import com.android.contacts.model.Editor;
import com.android.contacts.model.EntityDelta;
import com.android.contacts.model.EntityModifier;
import com.android.contacts.model.ContactsSource.DataKind;
import com.android.contacts.model.ContactsSource.EditField;
import com.android.contacts.model.ContactsSource.EditType;
import com.android.contacts.model.EntityDelta.ValuesDelta;
import com.android.contacts.ui.ViewIdGenerator;
import com.android.contacts.util.DialogManager;
import com.android.contacts.util.DialogManager.DialogShowingView;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Entity;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.PhoneNumberFormattingTextWatcher;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * Simple editor that handles labels and any {@link EditField} defined for
 * the entry. Uses {@link ValuesDelta} to read any existing
 * {@link Entity} values, and to correctly write any changes values.
 */
public class GenericEditorView extends ViewGroup implements Editor, DialogShowingView {
    private static final int RES_LABEL_ITEM = android.R.layout.simple_list_item_1;

    private static final String DIALOG_ID_KEY = "dialog_id";
    private static final int DIALOG_ID_LABEL = 1;
    private static final int DIALOG_ID_CUSTOM = 2;

    private static final int INPUT_TYPE_CUSTOM = EditorInfo.TYPE_CLASS_TEXT
            | EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS;

    private Button mLabel;
    private ImageButton mDelete;
    private ImageButton mMoreOrLess;

    private DataKind mKind;
    private ValuesDelta mEntry;
    private EntityDelta mState;
    private boolean mReadOnly;
    private EditText[] mFieldEditTexts = null;

    private boolean mHideOptional = true;

    private EditType mType;
    // Used only when a user tries to use custom label.
    private EditType mPendingType;

    private ViewIdGenerator mViewIdGenerator;
    private DialogManager mDialogManager = null;
    private EditorListener mListener;


    public GenericEditorView(Context context) {
        super(context);
    }

    public GenericEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GenericEditorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Subtract padding from the borders ==> x1 variables
        int l1 = getPaddingLeft();
        int t1 = getPaddingTop();
        int r1 = getMeasuredWidth() - getPaddingRight();
        int b1 = getMeasuredHeight() - getPaddingBottom();

        // Label Button
        final boolean hasLabel = mLabel != null;

        if (hasLabel) {
            mLabel.layout(
                    l1, t1,
                    l1 + mLabel.getMeasuredWidth(), t1 + mLabel.getMeasuredHeight());
        }

        // Delete Button
        final boolean hasDelete = mDelete != null;
        if (hasDelete) {
            mDelete.layout(
                    r1 - mDelete.getMeasuredWidth(), t1,
                    r1, t1 + mDelete.getMeasuredHeight());
        }

        // MoreOrLess Button
        final boolean hasMoreOrLess = mMoreOrLess != null;
        if (hasMoreOrLess) {
            mMoreOrLess.layout(
                    r1 - mMoreOrLess.getMeasuredWidth(), b1 - mMoreOrLess.getMeasuredHeight(),
                    r1, b1);
        }

        // Fields
        // Subtract buttons left and right if necessary
        final int l2 = hasLabel ? l1 + mLabel.getMeasuredWidth() : l1;
        final int r2 = r1 - Math.max(
                hasDelete ? mDelete.getMeasuredWidth() : 0,
                hasMoreOrLess ? mMoreOrLess.getMeasuredWidth() : 0);
        int y = 0;
        if (mFieldEditTexts != null) {
            for (EditText editText : mFieldEditTexts) {
                if (editText.getVisibility() != View.GONE) {
                    int height = editText.getMeasuredHeight();
                    editText.layout(
                            l2, t1 + y,
                            r2, t1 + y + height);
                    y += height;
                }
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);

        // summarize the EditText heights
        int totalHeight = 0;
        if (mFieldEditTexts != null) {
            for (EditText editText : mFieldEditTexts) {
                if (editText.getVisibility() != View.GONE) {
                    totalHeight += editText.getMeasuredHeight();
                }
            }
        }
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec));
    }

    /**
     * Creates or removes the type/label button. Doesn't do anything if already correctly configured
     */
    private void setupLabelButton(boolean shouldExist) {
        // TODO: Unhardcode the constant 100
        if (shouldExist && mLabel == null) {
            mLabel = new Button(mContext);
            mLabel.setLayoutParams(new LayoutParams(100, LayoutParams.WRAP_CONTENT));
            mLabel.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDialog(DIALOG_ID_LABEL);
                }
            });
            addView(mLabel);
        } else if (!shouldExist && mLabel != null) {
            removeView(mLabel);
            mLabel = null;
        }
    }

    /**
     * Creates or removes the type/label button. Doesn't do anything if already correctly configured
     */
    private void setupDeleteButton(boolean shouldExist) {
        if (shouldExist && mDelete == null) {
            // Unfortunately, the style passed as constructor-parameter is mostly ignored,
            // so we have to set the Background and Image seperately. However, if it is not given
            // the size of the control is wrong
            mDelete = new ImageButton(mContext, null, R.style.MinusButton);
            mDelete.setBackgroundResource(R.drawable.btn_circle);
            mDelete.setImageResource(R.drawable.ic_btn_round_minus);
            mDelete.setContentDescription(
                    getResources().getText(R.string.description_minus_button));
            mDelete.setLayoutParams(
                    new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            mDelete.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Keep around in model, but mark as deleted
                    mEntry.markDeleted();

//                    final ViewGroupAnimator animator = ViewGroupAnimator.captureView(getRootView());

//                    animator.removeView(GenericEditorView.this);
                    ((ViewGroup) getParent()).removeView(GenericEditorView.this);

                    if (mListener != null) {
                        // Notify listener when present
                        mListener.onDeleted(GenericEditorView.this);
                    }

//                    animator.animate();
                }
            });
            addView(mDelete);
        } else if (!shouldExist && mDelete != null) {
            removeView(mDelete);
            mDelete = null;
        }
    }

    /**
     * Creates or removes the type/label button. Doesn't do anything if already correctly configured
     */
    private void setupMoreOrLessButton(boolean shouldExist, boolean collapsed) {
        if (shouldExist) {
            if (mMoreOrLess == null) {
                // Unfortunately, the style passed as constructor-parameter is mostly ignored,
                // so we have to set the Background and Image seperately. However, if it is not
                // given, the size of the control is wrong
                mMoreOrLess = new ImageButton(mContext, null, R.style.EmptyButton);
                mMoreOrLess.setLayoutParams(
                        new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                mMoreOrLess.setBackgroundResource(R.drawable.btn_circle);
                mMoreOrLess.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Save focus
                        final View focusedChild = getFocusedChild();
                        final int focusedViewId = focusedChild == null ? -1 : focusedChild.getId();

                        // Reconfigure GUI
                        mHideOptional = !mHideOptional;
                        rebuildValues();

                        // Restore focus
                        View newFocusView = findViewById(focusedViewId);
                        if (newFocusView == null || newFocusView.getVisibility() == GONE) {
                            // find first visible child
                            newFocusView = GenericEditorView.this;
                        }
                        if (newFocusView != null) {
                            newFocusView.requestFocus();
                        }
                    }
                });
                addView(mMoreOrLess);
            }
            mMoreOrLess.setImageResource(
                    collapsed ? R.drawable.ic_btn_round_more : R.drawable.ic_btn_round_less);
        } else if (mMoreOrLess != null) {
            removeView(mMoreOrLess);
            mMoreOrLess = null;
        }
    }

    public void setEditorListener(EditorListener listener) {
        mListener = listener;
    }

    public void setDeletable(boolean deletable) {
        setupDeleteButton(deletable);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (mLabel != null) mLabel.setEnabled(enabled);

        if (mFieldEditTexts != null) {
            for (int index = 0; index < mFieldEditTexts.length; index++) {
                mFieldEditTexts[index].setEnabled(enabled);
            }
        }
        if (mDelete != null) mDelete.setEnabled(enabled);
        if (mMoreOrLess != null) mMoreOrLess.setEnabled(enabled);
    }

    /**
     * Build the current label state based on selected {@link EditType} and
     * possible custom label string.
     */
    private void rebuildLabel() {
        if (mLabel == null) return;
        // Handle undetected types
        if (mType == null) {
            mLabel.setText(R.string.unknown);
            return;
        }

        if (mType.customColumn != null) {
            // Use custom label string when present
            final String customText = mEntry.getAsString(mType.customColumn);
            if (customText != null) {
                mLabel.setText(customText);
                return;
            }
        }

        // Otherwise fall back to using default label
        mLabel.setText(mType.labelRes);
    }

    /** {@inheritDoc} */
    public void onFieldChanged(String column, String value) {
        // Field changes are saved directly
        mEntry.put(column, value);
        if (mListener != null) {
            mListener.onRequest(EditorListener.FIELD_CHANGED);
        }
    }

    private void rebuildValues() {
        setValues(mKind, mEntry, mState, mReadOnly, mViewIdGenerator);
    }

    /**
     * Prepare this editor using the given {@link DataKind} for defining
     * structure and {@link ValuesDelta} describing the content to edit.
     */
    public void setValues(DataKind kind, ValuesDelta entry, EntityDelta state, boolean readOnly,
            ViewIdGenerator vig) {
        mKind = kind;
        mEntry = entry;
        mState = state;
        mReadOnly = readOnly;
        mViewIdGenerator = vig;
        setId(vig.getId(state, kind, entry, ViewIdGenerator.NO_VIEW_INDEX));

        final boolean enabled = !readOnly;

        if (!entry.isVisible()) {
            // Hide ourselves entirely if deleted
            setVisibility(View.GONE);
            return;
        } else {
            setVisibility(View.VISIBLE);
        }

        // Display label selector if multiple types available
        final boolean hasTypes = EntityModifier.hasEditTypes(kind);
        setupLabelButton(hasTypes);
        if (mLabel != null) mLabel.setEnabled(enabled);
        if (hasTypes) {
            mType = EntityModifier.getCurrentType(entry, kind);
            rebuildLabel();
        }

        // Remove edit texts that we currently have
        if (mFieldEditTexts != null) {
            for (EditText fieldEditText : mFieldEditTexts) {
                removeView(fieldEditText);
            }
        }
        boolean hidePossible = false;

        mFieldEditTexts = new EditText[kind.fieldList.size()];
        for (int index = 0; index < kind.fieldList.size(); index++) {
            final EditField field = kind.fieldList.get(index);
            final EditText fieldView = new EditText(mContext);
            fieldView.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
            mFieldEditTexts[index] = fieldView;
            fieldView.setId(vig.getId(state, kind, entry, index));
            if (field.titleRes > 0) {
                fieldView.setHint(field.titleRes);
            }
            int inputType = field.inputType;
            fieldView.setInputType(inputType);
            if (inputType == InputType.TYPE_CLASS_PHONE) {
                fieldView.addTextChangedListener(new PhoneNumberFormattingTextWatcher(
                        ContactsUtils.getCurrentCountryIso(mContext)));
            }
            fieldView.setMinLines(field.minLines);

            // Read current value from state
            final String column = field.column;
            final String value = entry.getAsString(column);
            fieldView.setText(value);

            // Prepare listener for writing changes
            fieldView.addTextChangedListener(new TextWatcher() {
                public void afterTextChanged(Editable s) {
                    // Trigger event for newly changed value
                    onFieldChanged(column, s.toString());
                }

                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }
            });

            // Hide field when empty and optional value
            final boolean couldHide = (!ContactsUtils.isGraphic(value) && field.optional);
            final boolean willHide = (mHideOptional && couldHide);
            fieldView.setVisibility(willHide ? View.GONE : View.VISIBLE);
            fieldView.setEnabled(enabled);
            hidePossible = hidePossible || couldHide;

            addView(fieldView);
        }

        // When hiding fields, place expandable
        setupMoreOrLessButton(hidePossible, mHideOptional);
        if (mMoreOrLess != null) mMoreOrLess.setEnabled(enabled);
    }

    public ValuesDelta getValues() {
        return mEntry;
    }

    /**
     * Prepare dialog for entering a custom label. The input value is trimmed: white spaces before
     * and after the input text is removed.
     * <p>
     * If the final value is empty, this change request is ignored;
     * no empty text is allowed in any custom label.
     */
    private Dialog createCustomDialog() {
        final EditText customType = new EditText(mContext);
        customType.setInputType(INPUT_TYPE_CUSTOM);
        customType.requestFocus();

        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setTitle(R.string.customLabelPickerTitle);
        builder.setView(customType);

        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                final String customText = customType.getText().toString().trim();
                if (ContactsUtils.isGraphic(customText)) {
                    // Now we're sure it's ok to actually change the type value.
                    mType = mPendingType;
                    mPendingType = null;
                    mEntry.put(mKind.typeColumn, mType.rawValue);
                    mEntry.put(mType.customColumn, customText);
                    rebuildLabel();
                    requestFocusForFirstEditField();
                }
            }
        });

        builder.setNegativeButton(android.R.string.cancel, null);

        return builder.create();
    }

    /**
     * Prepare dialog for picking a new {@link EditType} or entering a
     * custom label. This dialog is limited to the valid types as determined
     * by {@link EntityModifier}.
     */
    public Dialog createLabelDialog() {
        // Build list of valid types, including the current value
        final List<EditType> validTypes = EntityModifier.getValidTypes(mState, mKind, mType);

        // Wrap our context to inflate list items using correct theme
        final Context dialogContext = new ContextThemeWrapper(mContext,
                android.R.style.Theme_Light);
        final LayoutInflater dialogInflater = (LayoutInflater) dialogContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        final ListAdapter typeAdapter = new ArrayAdapter<EditType>(mContext, RES_LABEL_ITEM,
                validTypes) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = dialogInflater.inflate(RES_LABEL_ITEM, parent, false);
                }

                final EditType type = this.getItem(position);
                final TextView textView = (TextView)convertView;
                textView.setText(type.labelRes);
                return textView;
            }
        };

        final DialogInterface.OnClickListener clickListener =
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();

                final EditType selected = validTypes.get(which);
                if (selected.customColumn != null) {
                    // Show custom label dialog if requested by type.
                    //
                    // Only when the custum value input in the next step is correct one.
                    // this method also set the type value to what the user requested here.
                    mPendingType = selected;
                    showDialog(DIALOG_ID_CUSTOM);
                } else {
                    // User picked type, and we're sure it's ok to actually write the entry.
                    mType = selected;
                    mEntry.put(mKind.typeColumn, mType.rawValue);
                    rebuildLabel();
                    requestFocusForFirstEditField();
                }
            }
        };

        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setTitle(R.string.selectLabel);
        builder.setSingleChoiceItems(typeAdapter, 0, clickListener);
        return builder.create();
    }

    /* package */
    void showDialog(int bundleDialogId) {
        Bundle bundle = new Bundle();
        bundle.putInt(DIALOG_ID_KEY, bundleDialogId);
        getDialogManager().showDialogInView(this, bundle);
    }

    private DialogManager getDialogManager() {
        if (mDialogManager == null) {
            Context context = getContext();
            if (!(context instanceof DialogManager.DialogShowingViewActivity)) {
                throw new IllegalStateException(
                        "View must be hosted in an Activity that implements " +
                        "DialogManager.DialogShowingViewActivity");
            }
            mDialogManager = ((DialogManager.DialogShowingViewActivity)context).getDialogManager();
        }
        return mDialogManager;
    }

    private static class SavedState extends BaseSavedState {
        public boolean mHideOptional;
        public int[] mVisibilities;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            mVisibilities = new int[in.readInt()];
            in.readIntArray(mVisibilities);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(mVisibilities.length);
            out.writeIntArray(mVisibilities);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    /**
     * Saves the visibility of the child EditTexts, and mHideOptional.
     */
    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);

        ss.mHideOptional = mHideOptional;

        final int numChildren = mFieldEditTexts.length;
        ss.mVisibilities = new int[numChildren];
        for (int i = 0; i < numChildren; i++) {
            ss.mVisibilities[i] = mFieldEditTexts[i].getVisibility();
        }

        return ss;
    }

    /**
     * Restores the visibility of the child EditTexts, and mHideOptional.
     */
    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        mHideOptional = ss.mHideOptional;

        int numChildren = Math.min(mFieldEditTexts.length, ss.mVisibilities.length);
        for (int i = 0; i < numChildren; i++) {
            mFieldEditTexts[i].setVisibility(ss.mVisibilities[i]);
        }
    }

    public Dialog createDialog(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("bundle must not be null");
        int dialogId = bundle.getInt(DIALOG_ID_KEY);
        switch (dialogId) {
            case DIALOG_ID_CUSTOM:
                return createCustomDialog();
            case DIALOG_ID_LABEL:
                return createLabelDialog();
            default:
                throw new IllegalArgumentException("Invalid dialogId: " + dialogId);
        }
    }

    private void requestFocusForFirstEditField() {
        if (mFieldEditTexts != null && mFieldEditTexts.length != 0) {
            boolean anyFieldHasFocus = false;
            for (EditText editText : mFieldEditTexts) {
                if (editText.hasFocus()) {
                    anyFieldHasFocus = true;
                }
            }
            if (!anyFieldHasFocus)
                mFieldEditTexts[0].requestFocus();
        }
    }
}
