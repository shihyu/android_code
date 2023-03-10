/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.layoutlib.bridge.bars;

import com.android.ide.common.rendering.api.ILayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.impl.ResourceHelper;
import com.android.layoutlib.bridge.resources.IconLoader;
import com.android.layoutlib.bridge.resources.SysUiResources;
import com.android.resources.Density;
import com.android.resources.LayoutDirection;
import com.android.resources.ResourceType;

import android.annotation.NonNull;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

import static android.os._Original_Build.VERSION_CODES.LOLLIPOP;

/**
 * Base "bar" class for the window decor around the the edited layout.
 * This is basically an horizontal layout that loads a given layout on creation (it is read
 * through {@link Class#getResourceAsStream(String)}).
 * <p>
 * The given layout should be a merge layout so that all the children belong to this class directly.
 * <p>
 * It also provides a few utility methods to configure the content of the layout.
 */
abstract class CustomBar extends LinearLayout {
    private final int mSimulatedPlatformVersion;

    protected CustomBar(BridgeContext context, int orientation, String layoutName,
            int simulatedPlatformVersion) {
        super(context);
        mSimulatedPlatformVersion = simulatedPlatformVersion;
        setOrientation(orientation);
        if (orientation == LinearLayout.HORIZONTAL) {
            setGravity(Gravity.CENTER_VERTICAL);
        } else {
            setGravity(Gravity.CENTER_HORIZONTAL);
        }

        LayoutInflater inflater = LayoutInflater.from(mContext);
        BridgeXmlBlockParser bridgeParser = loadXml(layoutName);
        try {
            inflater.inflate(bridgeParser, this, true);
        } finally {
            bridgeParser.ensurePopped();
        }
    }

    protected abstract TextView getStyleableTextView();

    protected BridgeXmlBlockParser loadXml(String layoutName) {
        return SysUiResources.loadXml((BridgeContext) mContext, mSimulatedPlatformVersion,
                layoutName);
    }

    protected ImageView loadIcon(ImageView imageView, String iconName, Density density) {
        return SysUiResources.loadIcon(mContext, mSimulatedPlatformVersion, imageView, iconName,
                density, false);
    }

    protected ImageView loadIcon(int index, String iconName, Density density, boolean isRtl) {
        View child = getChildAt(index);
        if (child instanceof ImageView) {
            ImageView imageView = (ImageView) child;
            return SysUiResources.loadIcon(mContext, mSimulatedPlatformVersion, imageView, iconName,
                    density, isRtl);
        }

        return null;
    }

    protected ImageView loadIcon(ImageView imageView, String iconName, Density density,
            boolean isRtl) {
        LayoutDirection dir = isRtl ? LayoutDirection.RTL : null;
        IconLoader iconLoader = new IconLoader(iconName, density, mSimulatedPlatformVersion, dir);
        InputStream stream = iconLoader.getIcon();

        if (stream != null) {
            density = iconLoader.getDensity();
            String path = iconLoader.getPath();
            // look for a cached bitmap
            Bitmap bitmap = Bridge.getCachedBitmap(path, Boolean.TRUE /*isFramework*/);
            if (bitmap == null) {
                Options options = new Options();
                options.inDensity = density.getDpiValue();
                bitmap = BitmapFactory.decodeStream(stream, null, options);
                Bridge.setCachedBitmap(path, bitmap, Boolean.TRUE /*isFramework*/);
            }

            if (bitmap != null) {
                BitmapDrawable drawable = new BitmapDrawable(getContext().getResources(), bitmap);
                imageView.setImageDrawable(drawable);
            }
        }

        return imageView;
    }

    protected TextView setText(int index, String string) {
        View child = getChildAt(index);
        if (child instanceof TextView) {
            TextView textView = (TextView) child;
            textView.setText(string);
            return textView;
        }

        return null;
    }

    protected void setStyle(String themeEntryName) {
        BridgeContext bridgeContext = getContext();
        RenderResources res = bridgeContext.getRenderResources();

        ResourceValue value =
                res.findItemInTheme(BridgeContext.createFrameworkAttrReference(themeEntryName));
        value = res.resolveResValue(value);

        if (!(value instanceof StyleResourceValue)) {
            return;
        }

        StyleResourceValue style = (StyleResourceValue) value;

        // get the background
        ResourceValue backgroundValue = res.findItemInStyle(style,
                BridgeContext.createFrameworkAttrReference("background"));
        backgroundValue = res.resolveResValue(backgroundValue);
        if (backgroundValue != null) {
            Drawable d = ResourceHelper.getDrawable(backgroundValue, bridgeContext);
            if (d != null) {
                setBackground(d);
            }
        }

        TextView textView = getStyleableTextView();
        if (textView != null) {
            // get the text style
            ResourceValue textStyleValue = res.findItemInStyle(style,
                    BridgeContext.createFrameworkAttrReference("titleTextStyle"));
            textStyleValue = res.resolveResValue(textStyleValue);
            if (textStyleValue instanceof StyleResourceValue) {
                StyleResourceValue textStyle = (StyleResourceValue) textStyleValue;

                ResourceValue textSize = res.findItemInStyle(textStyle,
                        BridgeContext.createFrameworkAttrReference("textSize"));
                textSize = res.resolveResValue(textSize);

                if (textSize != null) {
                    TypedValue out = new TypedValue();
                    if (ResourceHelper.parseFloatAttribute("textSize", textSize.getValue(), out,
                            true /*requireUnit*/)) {
                        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                                out.getDimension(bridgeContext.getResources().getDisplayMetrics()));
                    }
                }

                ResourceValue textColor = res.findItemInStyle(textStyle,
                        BridgeContext.createFrameworkAttrReference("textColor"));
                textColor = res.resolveResValue(textColor);
                if (textColor != null) {
                    ColorStateList stateList =
                            ResourceHelper.getColorStateList(textColor, bridgeContext, null);
                    if (stateList != null) {
                        textView.setTextColor(stateList);
                    }
                }
            }
        }
    }

    @Override
    public BridgeContext getContext() {
        return (BridgeContext) mContext;
    }

    /**
     * Find the background color for this bar from the theme attributes. Only relevant to StatusBar
     * and NavigationBar.
     * <p/>
     * Returns 0 if not found.
     *
     * @param colorAttrName the attribute name for the background color
     * @param translucentAttrName the attribute name for the translucency property of the bar.
     *
     * @throws NumberFormatException if color resolved to an invalid string.
     */
    protected int getBarColor(@NonNull String colorAttrName, @NonNull String translucentAttrName) {
        if (!Config.isGreaterOrEqual(mSimulatedPlatformVersion, LOLLIPOP)) {
            return 0;
        }
        RenderResources renderResources = getContext().getRenderResources();
        // First check if the bar is translucent.
        boolean translucent = ResourceHelper.getBooleanThemeFrameworkAttrValue(renderResources,
                translucentAttrName, false);
        if (translucent) {
            // Keep in sync with R.color.system_bar_background_semi_transparent from system ui.
            return 0x66000000;  // 40% black.
        }
        boolean transparent = ResourceHelper.getBooleanThemeFrameworkAttrValue(renderResources,
                "windowDrawsSystemBarBackgrounds", false);
        if (transparent) {
            return getColor(renderResources, colorAttrName);
        }
        return 0;
    }

    private static int getColor(RenderResources renderResources, String attr) {
        // From ?attr/foo to @color/bar. This is most likely an StyleItemResourceValue.
        ResourceValue resource =
                renderResources.findItemInTheme(BridgeContext.createFrameworkAttrReference(attr));
        // Form @color/bar to the #AARRGGBB
        resource = renderResources.resolveResValue(resource);
        if (resource != null) {
            ResourceType type = resource.getResourceType();
            if (type == null || type == ResourceType.COLOR) {
                // if no type is specified, the value may have been specified directly in the style
                // file, rather than referencing a color resource value.
                try {
                    return ResourceHelper.getColor(resource.getValue());
                } catch (NumberFormatException e) {
                    // Conversion failed.
                    Bridge.getLog().warning(ILayoutLog.TAG_RESOURCES_FORMAT,
                            "Theme attribute @android:" + attr +
                                    " does not reference a color, instead is '" +
                                    resource.getValue() + "'.", null, resource);
                }
            }
        }
        return 0;
    }
}
