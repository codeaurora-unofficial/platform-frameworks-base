/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.view.menu;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.os.SystemProperties;
import android.text.TextUtils;
/**
 * The item view for each item in the ListView-based MenuViews.
 */
public class ListMenuItemView extends LinearLayout implements MenuView.ItemView {
    private static final String TAG = "ListMenuItemView";
    private MenuItemImpl mItemData; 
    
    private ImageView mIconView;
    private RadioButton mRadioButton;
    private TextView mTitleView;
    private CheckBox mCheckBox;
    private TextView mShortcutView;
    
    private Drawable mBackground;
    private int mTextAppearance;
    private Context mTextAppearanceContext;
    private boolean mPreserveIconSpacing;
    
    private ImageView mSubMenuIconView;
    private int mMenuType;
    
    private LayoutInflater mInflater;

    private boolean mForceShowIcon;

    public ListMenuItemView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);
    
        TypedArray a =
            context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.MenuView, defStyle, 0);
        
        mBackground = a.getDrawable(com.android.internal.R.styleable.MenuView_itemBackground);
        mTextAppearance = a.getResourceId(com.android.internal.R.styleable.
                                          MenuView_itemTextAppearance, -1);
        mPreserveIconSpacing = a.getBoolean(
                com.android.internal.R.styleable.MenuView_preserveIconSpacing, false);
        mTextAppearanceContext = context;
        
        a.recycle();
    }

    public ListMenuItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        
        setBackgroundDrawable(mBackground);
        
        mTitleView = (TextView) findViewById(com.android.internal.R.id.title);
        if (mTextAppearance != -1) {
            mTitleView.setTextAppearance(mTextAppearanceContext,
                                         mTextAppearance);
        }
        
        mShortcutView = (TextView) findViewById(com.android.internal.R.id.shortcut);
    }

    public void initialize(MenuItemImpl itemData, int menuType) {
        mItemData = itemData;
        mMenuType = menuType;

        setVisibility(itemData.isVisible() ? View.VISIBLE : View.GONE);
        
        setTitle(itemData.getTitleForItemView(this));
        setCheckable(itemData.isCheckable());
        mTitleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mTitleView.setSingleLine(true);
        setShortcut(itemData.shouldShowShortcut(), itemData.getShortcut());
        setIcon(itemData.getIcon());
        setEnabled(itemData.isEnabled());
        //NT show the icon if any menu item has submenu
        if(SystemProperties.get("persist.sys.showbottomactionbar","0").equals("1")) {
            setSubMenuIcon(itemData.hasSubMenu()
                && (itemData.getActionProvider() == null));
        }
    }

    public void setForceShowIcon(boolean forceShow) {
        mPreserveIconSpacing = mForceShowIcon = forceShow;
    }

    public void setTitle(CharSequence title) {
        if (title != null) {
            mTitleView.setText(title);
            
            if (mTitleView.getVisibility() != VISIBLE) mTitleView.setVisibility(VISIBLE);
        } else {
            if (mTitleView.getVisibility() != GONE) mTitleView.setVisibility(GONE);
        }
    }
    
    public MenuItemImpl getItemData() {
        return mItemData;
    }

    public void setCheckable(boolean checkable) {
        if (!checkable && mRadioButton == null && mCheckBox == null) {
            return;
        }
        
        // Depending on whether its exclusive check or not, the checkbox or
        // radio button will be the one in use (and the other will be otherCompoundButton)
        final CompoundButton compoundButton;
        final CompoundButton otherCompoundButton; 

        if (mItemData.isExclusiveCheckable()) {
            if (mRadioButton == null) {
                insertRadioButton();
            }
            compoundButton = mRadioButton;
            otherCompoundButton = mCheckBox;
        } else {
            if (mCheckBox == null) {
                insertCheckBox();
            }
            compoundButton = mCheckBox;
            otherCompoundButton = mRadioButton;
        }
        
        if (checkable) {
            compoundButton.setChecked(mItemData.isChecked());
            
            final int newVisibility = checkable ? VISIBLE : GONE;
            if (compoundButton.getVisibility() != newVisibility) {
                compoundButton.setVisibility(newVisibility);
            }
            
            // Make sure the other compound button isn't visible
            if (otherCompoundButton != null && otherCompoundButton.getVisibility() != GONE) {
                otherCompoundButton.setVisibility(GONE);
            }
        } else {
            if (mCheckBox != null) mCheckBox.setVisibility(GONE);
            if (mRadioButton != null) mRadioButton.setVisibility(GONE);
        }
    }
    
    public void setChecked(boolean checked) {
        CompoundButton compoundButton;
        
        if (mItemData.isExclusiveCheckable()) {
            if (mRadioButton == null) {
                insertRadioButton();
            }
            compoundButton = mRadioButton;
        } else {
            if (mCheckBox == null) {
                insertCheckBox();
            }
            compoundButton = mCheckBox;
        }
        
        compoundButton.setChecked(checked);
    }

    public void setShortcut(boolean showShortcut, char shortcutKey) {
        final int newVisibility = (showShortcut && mItemData.shouldShowShortcut())
                ? VISIBLE : GONE;

        if (newVisibility == VISIBLE) {
            mShortcutView.setText(mItemData.getShortcutLabel());
        }

        if (mShortcutView.getVisibility() != newVisibility) {
            mShortcutView.setVisibility(newVisibility);
        }
    }
    
    public void setIcon(Drawable icon) {
        final boolean showIcon = mItemData.shouldShowIcon() || mForceShowIcon;
        if (!showIcon && !mPreserveIconSpacing) {
            return;
        }
        
        if (mIconView == null && icon == null && !mPreserveIconSpacing) {
            return;
        }
        
        if (mIconView == null) {
            insertIconView();
        }
        
        if (icon != null || mPreserveIconSpacing) {
            mIconView.setImageDrawable(showIcon ? icon : null);

            if (mIconView.getVisibility() != VISIBLE) {
                mIconView.setVisibility(VISIBLE);
            }
        } else {
            mIconView.setVisibility(GONE);
        }
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mIconView != null && mPreserveIconSpacing) {
            // Enforce minimum icon spacing
            ViewGroup.LayoutParams lp = getLayoutParams();
            LayoutParams iconLp = (LayoutParams) mIconView.getLayoutParams();
            if (lp.height > 0 && iconLp.width <= 0) {
                iconLp.width = lp.height;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void insertIconView() {
        LayoutInflater inflater = getInflater();
        mIconView = (ImageView) inflater.inflate(com.android.internal.R.layout.list_menu_item_icon,
                this, false);
        addView(mIconView, 0);
    }
    
    private void insertRadioButton() {
        LayoutInflater inflater = getInflater();
        mRadioButton =
                (RadioButton) inflater.inflate(com.android.internal.R.layout.list_menu_item_radio,
                this, false);
        addView(mRadioButton);
    }
    
    private void insertCheckBox() {
        LayoutInflater inflater = getInflater();
        mCheckBox =
                (CheckBox) inflater.inflate(com.android.internal.R.layout.list_menu_item_checkbox,
                this, false);
        addView(mCheckBox);
    }

    public boolean prefersCondensedTitle() {
        return false;
    }

    public boolean showsIcon() {
        return mForceShowIcon;
    }
    
    private LayoutInflater getInflater() {
        if (mInflater == null) {
            mInflater = LayoutInflater.from(mContext);
        }
        return mInflater;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);

        if (mItemData != null && mItemData.hasSubMenu()) {
            info.setCanOpenPopup(true);
        }
    }

    //NT - To add sub menu icon
    private void insertSubMenuIconView() {
        //LayoutInflater inflater = getInflater();
        mSubMenuIconView =
                (ImageView) getInflater().inflate(com.android.internal.R.layout.list_menu_item_submenu_indicator,
                this, false);
        addView(mSubMenuIconView);
    }

    //NT - This method shows the icon for submenu
    public void setSubMenuIcon(boolean showIcon) {
        if (mSubMenuIconView == null) {
            if (!showIcon) return;
            insertSubMenuIconView();
        }

        if (showIcon) {
            mSubMenuIconView.setImageResource(com.android.internal.R.drawable.ic_menu_send);

            if (mSubMenuIconView.getVisibility() != VISIBLE) {
                mSubMenuIconView.setVisibility(VISIBLE);
            }
        } else {
            mSubMenuIconView.setVisibility(GONE);
        }
    }
}
