/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.widget;

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.DisplayListCanvas;
import android.view.PixelCopy;
import android.view.RenderNode;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceSession;
import android.view.SurfaceView;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewRootImpl;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

/**
 * Android magnifier widget. Can be used by any view which is attached to a window.
 */
@UiThread
public final class Magnifier {
    // Use this to specify that a previous configuration value does not exist.
    private static final int NONEXISTENT_PREVIOUS_CONFIG_VALUE = -1;
    // The callbacks of the pixel copy requests will be invoked on
    // the Handler of this Thread when the copy is finished.
    private static final HandlerThread sPixelCopyHandlerThread =
            new HandlerThread("magnifier pixel copy result handler");

    // The view to which this magnifier is attached.
    private final View mView;
    // The coordinates of the view in the surface.
    private final int[] mViewCoordinatesInSurface;
    // The window containing the magnifier.
    private InternalPopupWindow mWindow;
    // The width of the window containing the magnifier.
    private final int mWindowWidth;
    // The height of the window containing the magnifier.
    private final int mWindowHeight;
    // The zoom applied to the view region copied to the magnifier view.
    private float mZoom;
    // The width of the content that will be copied to the magnifier.
    private int mSourceWidth;
    // The height of the content that will be copied to the magnifier.
    private int mSourceHeight;
    // Whether the zoom of the magnifier has changed since last content copy.
    private boolean mDirtyZoom;
    // The elevation of the window containing the magnifier.
    private final float mWindowElevation;
    // The corner radius of the window containing the magnifier.
    private final float mWindowCornerRadius;
    // The horizontal offset between the source and window coords when #show(float, float) is used.
    private final int mDefaultHorizontalSourceToMagnifierOffset;
    // The vertical offset between the source and window coords when #show(float, float) is used.
    private final int mDefaultVerticalSourceToMagnifierOffset;
    // The parent surface for the magnifier surface.
    private SurfaceInfo mParentSurface;
    // The surface where the content will be copied from.
    private SurfaceInfo mContentCopySurface;
    // The center coordinates of the window containing the magnifier.
    private final Point mWindowCoords = new Point();
    // The center coordinates of the content to be magnified,
    // clamped inside the visible region of the magnified view.
    private final Point mClampedCenterZoomCoords = new Point();
    // Variables holding previous states, used for detecting redundant calls and invalidation.
    private final Point mPrevStartCoordsInSurface = new Point(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    private final PointF mPrevShowSourceCoords = new PointF(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    private final PointF mPrevShowWindowCoords = new PointF(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    // Rectangle defining the view surface area we pixel copy content from.
    private final Rect mPixelCopyRequestRect = new Rect();
    // Lock to synchronize between the UI thread and the thread that handles pixel copy results.
    // Only sync mWindow writes from UI thread with mWindow reads from sPixelCopyHandlerThread.
    private final Object mLock = new Object();

    /**
     * Initializes a magnifier.
     *
     * @param view the view for which this magnifier is attached
     *
     * @deprecated Please use {@link Builder} instead
     */
    @Deprecated
    public Magnifier(@NonNull View view) {
        this(new Builder(view));
    }

    private Magnifier(@NonNull Builder params) {
        // Copy params from builder.
        mView = params.mView;
        mWindowWidth = params.mWidth;
        mWindowHeight = params.mHeight;
        mZoom = params.mZoom;
        mSourceWidth = Math.round(mWindowWidth / mZoom);
        mSourceHeight = Math.round(mWindowHeight / mZoom);
        mWindowElevation = params.mElevation;
        mWindowCornerRadius = params.mCornerRadius;
        mDefaultHorizontalSourceToMagnifierOffset =
                params.mHorizontalDefaultSourceToMagnifierOffset;
        mDefaultVerticalSourceToMagnifierOffset =
                params.mVerticalDefaultSourceToMagnifierOffset;
        // The view's surface coordinates will not be updated until the magnifier is first shown.
        mViewCoordinatesInSurface = new int[2];
    }

    static {
        sPixelCopyHandlerThread.start();
    }

    /**
     * Shows the magnifier on the screen. The method takes the coordinates of the center
     * of the content source going to be magnified and copied to the magnifier. The coordinates
     * are relative to the top left corner of the magnified view. The magnifier will be
     * positioned such that its center will be at the default offset from the center of the source.
     * The default offset can be specified using the method
     * {@link Builder#setDefaultSourceToMagnifierOffset(int, int)}. If the offset should
     * be different across calls to this method, you should consider to use method
     * {@link #show(float, float, float, float)} instead.
     *
     * @param sourceCenterX horizontal coordinate of the source center, relative to the view
     * @param sourceCenterY vertical coordinate of the source center, relative to the view
     *
     * @see Builder#setDefaultSourceToMagnifierOffset(int, int)
     * @see Builder#getDefaultHorizontalSourceToMagnifierOffset()
     * @see Builder#getDefaultVerticalSourceToMagnifierOffset()
     * @see #show(float, float, float, float)
     */
    public void show(@FloatRange(from = 0) float sourceCenterX,
            @FloatRange(from = 0) float sourceCenterY) {
        show(sourceCenterX, sourceCenterY,
                sourceCenterX + mDefaultHorizontalSourceToMagnifierOffset,
                sourceCenterY + mDefaultVerticalSourceToMagnifierOffset);
    }

    /**
     * Shows the magnifier on the screen at a position that is independent from its content
     * position. The first two arguments represent the coordinates of the center of the
     * content source going to be magnified and copied to the magnifier. The last two arguments
     * represent the coordinates of the center of the magnifier itself. All four coordinates
     * are relative to the top left corner of the magnified view. If you consider using this
     * method such that the offset between the source center and the magnifier center coordinates
     * remains constant, you should consider using method {@link #show(float, float)} instead.
     *
     * @param sourceCenterX horizontal coordinate of the source center relative to the view
     * @param sourceCenterY vertical coordinate of the source center, relative to the view
     * @param magnifierCenterX horizontal coordinate of the magnifier center, relative to the view
     * @param magnifierCenterY vertical coordinate of the magnifier center, relative to the view
     */
    public void show(@FloatRange(from = 0) float sourceCenterX,
            @FloatRange(from = 0) float sourceCenterY,
            float magnifierCenterX, float magnifierCenterY) {
        sourceCenterX = Math.max(0, Math.min(sourceCenterX, mView.getWidth()));
        sourceCenterY = Math.max(0, Math.min(sourceCenterY, mView.getHeight()));

        obtainSurfaces();
        obtainContentCoordinates(sourceCenterX, sourceCenterY);
        obtainWindowCoordinates(magnifierCenterX, magnifierCenterY);

        final int startX = mClampedCenterZoomCoords.x - mSourceWidth / 2;
        final int startY = mClampedCenterZoomCoords.y - mSourceHeight / 2;
        if (sourceCenterX != mPrevShowSourceCoords.x || sourceCenterY != mPrevShowSourceCoords.y
                || mDirtyZoom) {
            if (mWindow == null) {
                synchronized (mLock) {
                    mWindow = new InternalPopupWindow(mView.getContext(), mView.getDisplay(),
                            mParentSurface.mSurface,
                            mWindowWidth, mWindowHeight, mWindowElevation, mWindowCornerRadius,
                            Handler.getMain() /* draw the magnifier on the UI thread */, mLock,
                            mCallback);
                }
            }
            performPixelCopy(startX, startY, true /* update window position */);
        } else if (magnifierCenterX != mPrevShowWindowCoords.x
                || magnifierCenterY != mPrevShowWindowCoords.y) {
            final Point windowCoords = getCurrentClampedWindowCoordinates();
            final InternalPopupWindow currentWindowInstance = mWindow;
            sPixelCopyHandlerThread.getThreadHandler().post(() -> {
                synchronized (mLock) {
                    if (mWindow != currentWindowInstance) {
                        // The magnifier was dismissed (and maybe shown again) in the meantime.
                        return;
                    }
                    mWindow.setContentPositionForNextDraw(windowCoords.x, windowCoords.y);
                }
            });
        }
        mPrevShowSourceCoords.x = sourceCenterX;
        mPrevShowSourceCoords.y = sourceCenterY;
        mPrevShowWindowCoords.x = magnifierCenterX;
        mPrevShowWindowCoords.y = magnifierCenterY;
    }

    /**
     * Dismisses the magnifier from the screen. Calling this on a dismissed magnifier is a no-op.
     */
    public void dismiss() {
        if (mWindow != null) {
            synchronized (mLock) {
                mWindow.destroy();
                mWindow = null;
            }
            mPrevShowSourceCoords.x = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevShowSourceCoords.y = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevShowWindowCoords.x = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevShowWindowCoords.y = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevStartCoordsInSurface.x = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevStartCoordsInSurface.y = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
        }
    }

    /**
     * Asks the magnifier to update its content. It uses the previous coordinates passed to
     * {@link #show(float, float)} or {@link #show(float, float, float, float)}. The
     * method only has effect if the magnifier is currently showing.
     */
    public void update() {
        if (mWindow != null) {
            obtainSurfaces();
            if (!mDirtyZoom) {
                // Update the content shown in the magnifier.
                performPixelCopy(mPrevStartCoordsInSurface.x, mPrevStartCoordsInSurface.y,
                        false /* update window position */);
            } else {
                // If the zoom has changed, we cannot use the same top left coordinates
                // as before, so just #show again to have them recomputed.
                show(mPrevShowSourceCoords.x, mPrevShowSourceCoords.y,
                        mPrevShowWindowCoords.x, mPrevShowWindowCoords.y);
            }
        }
    }

    /**
     * @return the width of the magnifier window, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     */
    @Px
    public int getWidth() {
        return mWindowWidth;
    }

    /**
     * @return the height of the magnifier window, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     */
    @Px
    public int getHeight() {
        return mWindowHeight;
    }

    /**
     * @return the initial width of the content magnified and copied to the magnifier, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     * @see Magnifier.Builder#setZoom(float)
     */
    @Px
    public int getSourceWidth() {
        return mSourceWidth;
    }

    /**
     * @return the initial height of the content magnified and copied to the magnifier, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     * @see Magnifier.Builder#setZoom(float)
     */
    @Px
    public int getSourceHeight() {
        return mSourceHeight;
    }

    /**
     * Sets the zoom to be applied to the chosen content before being copied to the magnifier popup.
     * @param zoom the zoom to be set
     */
    public void setZoom(@FloatRange(from = 0f) float zoom) {
        Preconditions.checkArgumentPositive(zoom, "Zoom should be positive");
        mZoom = zoom;
        mSourceWidth = Math.round(mWindowWidth / mZoom);
        mSourceHeight = Math.round(mWindowHeight / mZoom);
        mDirtyZoom = true;
    }

    /**
     * Returns the zoom to be applied to the magnified view region copied to the magnifier.
     * If the zoom is x and the magnifier window size is (width, height), the original size
     * of the content being magnified will be (width / x, height / x).
     * @return the zoom applied to the content
     * @see Magnifier.Builder#setZoom(float)
     */
    public float getZoom() {
        return mZoom;
    }

    /**
     * @return the elevation set for the magnifier window, in pixels
     * @see Magnifier.Builder#setElevation(float)
     */
    @Px
    public float getElevation() {
        return mWindowElevation;
    }

    /**
     * @return the corner radius of the magnifier window, in pixels
     * @see Magnifier.Builder#setCornerRadius(float)
     */
    @Px
    public float getCornerRadius() {
        return mWindowCornerRadius;
    }

    /**
     * Returns the horizontal offset, in pixels, to be applied to the source center position
     * to obtain the magnifier center position when {@link #show(float, float)} is called.
     * The value is ignored when {@link #show(float, float, float, float)} is used instead.
     *
     * @return the default horizontal offset between the source center and the magnifier
     * @see Magnifier.Builder#setDefaultSourceToMagnifierOffset(int, int)
     * @see Magnifier#show(float, float)
     */
    @Px
    public int getDefaultHorizontalSourceToMagnifierOffset() {
        return mDefaultHorizontalSourceToMagnifierOffset;
    }

    /**
     * Returns the vertical offset, in pixels, to be applied to the source center position
     * to obtain the magnifier center position when {@link #show(float, float)} is called.
     * The value is ignored when {@link #show(float, float, float, float)} is used instead.
     *
     * @return the default vertical offset between the source center and the magnifier
     * @see Magnifier.Builder#setDefaultSourceToMagnifierOffset(int, int)
     * @see Magnifier#show(float, float)
     */
    @Px
    public int getDefaultVerticalSourceToMagnifierOffset() {
        return mDefaultVerticalSourceToMagnifierOffset;
    }

    /**
     * Returns the top left coordinates of the magnifier, relative to the surface of the
     * main application window. They will be determined by the coordinates of the last
     * {@link #show(float, float)} or {@link #show(float, float, float, float)} call, adjusted
     * to take into account any potential clamping behavior. The method can be used immediately
     * after a #show call to find out where the magnifier will be positioned. However, the
     * position of the magnifier will not be updated in the same frame due to the async
     * copying of the content copying and of the magnifier rendering.
     * The method will return {@code null} if #show has not yet been called, or if the last
     * operation performed was a #dismiss.
     *
     * @return the top left coordinates of the magnifier
     */
    @Nullable
    public Point getPosition() {
        if (mWindow == null) {
            return null;
        }
        return new Point(getCurrentClampedWindowCoordinates());
    }

    /**
     * Returns the top left coordinates of the magnifier source (i.e. the view region going to
     * be magnified and copied to the magnifier), relative to the surface the content is copied
     * from. The content will be copied:
     * - if the magnified view is a {@link SurfaceView}, from the surface backing it
     * - otherwise, from the surface of the main application window
     * The method will return {@code null} if #show has not yet been called, or if the last
     * operation performed was a #dismiss.
     *
     * @return the top left coordinates of the magnifier source
     */
    @Nullable
    public Point getSourcePosition() {
        if (mWindow == null) {
            return null;
        }
        return new Point(mPixelCopyRequestRect.left, mPixelCopyRequestRect.top);
    }

    /**
     * Retrieves the surfaces used by the magnifier:
     * - a parent surface for the magnifier surface. This will usually be the main app window.
     * - a surface where the magnified content will be copied from. This will be the main app
     *   window unless the magnified view is a SurfaceView, in which case its backing surface
     *   will be used.
     */
    private void obtainSurfaces() {
        // Get the main window surface.
        SurfaceInfo validMainWindowSurface = SurfaceInfo.NULL;
        if (mView.getViewRootImpl() != null) {
            final ViewRootImpl viewRootImpl = mView.getViewRootImpl();
            final Surface mainWindowSurface = viewRootImpl.mSurface;
            if (mainWindowSurface != null && mainWindowSurface.isValid()) {
                final Rect surfaceInsets = viewRootImpl.mWindowAttributes.surfaceInsets;
                final int surfaceWidth =
                        viewRootImpl.getWidth() + surfaceInsets.left + surfaceInsets.right;
                final int surfaceHeight =
                        viewRootImpl.getHeight() + surfaceInsets.top + surfaceInsets.bottom;
                validMainWindowSurface =
                        new SurfaceInfo(mainWindowSurface, surfaceWidth, surfaceHeight, true);
            }
        }
        // Get the surface backing the magnified view, if it is a SurfaceView.
        SurfaceInfo validSurfaceViewSurface = SurfaceInfo.NULL;
        if (mView instanceof SurfaceView) {
            final SurfaceHolder surfaceHolder = ((SurfaceView) mView).getHolder();
            final Surface surfaceViewSurface = surfaceHolder.getSurface();
            if (surfaceViewSurface != null && surfaceViewSurface.isValid()) {
                final Rect surfaceFrame = surfaceHolder.getSurfaceFrame();
                validSurfaceViewSurface = new SurfaceInfo(surfaceViewSurface,
                        surfaceFrame.right, surfaceFrame.bottom, false);
            }
        }

        // Choose the parent surface for the magnifier and the source surface for the content.
        mParentSurface = validMainWindowSurface != SurfaceInfo.NULL
                ? validMainWindowSurface : validSurfaceViewSurface;
        mContentCopySurface = mView instanceof SurfaceView
                ? validSurfaceViewSurface : validMainWindowSurface;
    }

    /**
     * Computes the coordinates of the center of the content going to be displayed in the
     * magnifier. These are relative to the surface the content is copied from.
     */
    private void obtainContentCoordinates(final float xPosInView, final float yPosInView) {
        mView.getLocationInSurface(mViewCoordinatesInSurface);
        final int zoomCenterX;
        final int zoomCenterY;
        if (mView instanceof SurfaceView) {
            // No offset required if the backing Surface matches the size of the SurfaceView.
            zoomCenterX = Math.round(xPosInView);
            zoomCenterY = Math.round(yPosInView);
        } else {
            zoomCenterX = Math.round(xPosInView + mViewCoordinatesInSurface[0]);
            zoomCenterY = Math.round(yPosInView + mViewCoordinatesInSurface[1]);
        }

        // Clamp the x location to avoid magnifying content which does not belong
        // to the magnified view. This will not take into account overlapping views.
        final Rect viewVisibleRegion = new Rect();
        mView.getGlobalVisibleRect(viewVisibleRegion);
        if (mView.getViewRootImpl() != null) {
            // Clamping coordinates relative to the surface, not to the window.
            final Rect surfaceInsets = mView.getViewRootImpl().mWindowAttributes.surfaceInsets;
            viewVisibleRegion.offset(surfaceInsets.left, surfaceInsets.top);
        }
        if (mView instanceof SurfaceView) {
            // If we copy content from a SurfaceView, clamp coordinates relative to it.
            viewVisibleRegion.offset(-mViewCoordinatesInSurface[0], -mViewCoordinatesInSurface[1]);
        }
        mClampedCenterZoomCoords.x = Math.max(viewVisibleRegion.left + mSourceWidth / 2, Math.min(
                zoomCenterX, viewVisibleRegion.right - mSourceWidth / 2));
        mClampedCenterZoomCoords.y = zoomCenterY;
    }

    /**
     * Computes the coordinates of the top left corner of the magnifier window.
     * These are relative to the surface the magnifier window is attached to.
     */
    private void obtainWindowCoordinates(final float xWindowPos, final float yWindowPos) {
        final int windowCenterX;
        final int windowCenterY;
        if (mView instanceof SurfaceView) {
            // No offset required if the backing Surface matches the size of the SurfaceView.
            windowCenterX = Math.round(xWindowPos);
            windowCenterY = Math.round(yWindowPos);
        } else {
            windowCenterX = Math.round(xWindowPos + mViewCoordinatesInSurface[0]);
            windowCenterY = Math.round(yWindowPos + mViewCoordinatesInSurface[1]);
        }

        mWindowCoords.x = windowCenterX - mWindowWidth / 2;
        mWindowCoords.y = windowCenterY - mWindowHeight / 2;
        if (mParentSurface != mContentCopySurface) {
            mWindowCoords.x += mViewCoordinatesInSurface[0];
            mWindowCoords.y += mViewCoordinatesInSurface[1];
        }
    }

    private void performPixelCopy(final int startXInSurface, final int startYInSurface,
            final boolean updateWindowPosition) {
        if (mContentCopySurface.mSurface == null || !mContentCopySurface.mSurface.isValid()) {
            return;
        }
        // Clamp copy coordinates inside the surface to avoid displaying distorted content.
        final int clampedStartXInSurface = Math.max(0,
                Math.min(startXInSurface, mContentCopySurface.mWidth - mSourceWidth));
        final int clampedStartYInSurface = Math.max(0,
                Math.min(startYInSurface, mContentCopySurface.mHeight - mSourceHeight));
        // Clamp window coordinates inside the parent surface, to avoid displaying
        // the magnifier out of screen or overlapping with system insets.
        final Point windowCoords = getCurrentClampedWindowCoordinates();

        // Perform the pixel copy.
        mPixelCopyRequestRect.set(clampedStartXInSurface,
                clampedStartYInSurface,
                clampedStartXInSurface + mSourceWidth,
                clampedStartYInSurface + mSourceHeight);
        final InternalPopupWindow currentWindowInstance = mWindow;
        final Bitmap bitmap =
                Bitmap.createBitmap(mSourceWidth, mSourceHeight, Bitmap.Config.ARGB_8888);
        PixelCopy.request(mContentCopySurface.mSurface, mPixelCopyRequestRect, bitmap,
                result -> {
                    synchronized (mLock) {
                        if (mWindow != currentWindowInstance) {
                            // The magnifier was dismissed (and maybe shown again) in the meantime.
                            return;
                        }
                        if (updateWindowPosition) {
                            // TODO: pull the position update outside #performPixelCopy
                            mWindow.setContentPositionForNextDraw(windowCoords.x, windowCoords.y);
                        }
                        mWindow.updateContent(bitmap);
                    }
                },
                sPixelCopyHandlerThread.getThreadHandler());
        mPrevStartCoordsInSurface.x = startXInSurface;
        mPrevStartCoordsInSurface.y = startYInSurface;
        mDirtyZoom = false;
    }

    /**
     * Clamp window coordinates inside the surface the magnifier is attached to, to avoid
     * displaying the magnifier out of screen or overlapping with system insets.
     * @return the current window coordinates, after they are clamped inside the parent surface
     */
    private Point getCurrentClampedWindowCoordinates() {
        final Rect windowBounds;
        if (mParentSurface.mIsMainWindowSurface) {
            final Rect systemInsets = mView.getRootWindowInsets().getSystemWindowInsets();
            windowBounds = new Rect(systemInsets.left, systemInsets.top,
                    mParentSurface.mWidth - systemInsets.right,
                    mParentSurface.mHeight - systemInsets.bottom);
        } else {
            windowBounds = new Rect(0, 0, mParentSurface.mWidth, mParentSurface.mHeight);
        }
        final int windowCoordsX = Math.max(windowBounds.left,
                Math.min(windowBounds.right - mWindowWidth, mWindowCoords.x));
        final int windowCoordsY = Math.max(windowBounds.top,
                Math.min(windowBounds.bottom - mWindowHeight, mWindowCoords.y));
        return new Point(windowCoordsX, windowCoordsY);
    }

    /**
     * Contains a surface and metadata corresponding to it.
     */
    private static class SurfaceInfo {
        public static final SurfaceInfo NULL = new SurfaceInfo(null, 0, 0, false);

        private Surface mSurface;
        private int mWidth;
        private int mHeight;
        private boolean mIsMainWindowSurface;

        SurfaceInfo(final Surface surface, final int width, final int height,
                final boolean isMainWindowSurface) {
            mSurface = surface;
            mWidth = width;
            mHeight = height;
            mIsMainWindowSurface = isMainWindowSurface;
        }
    }

    /**
     * Magnifier's own implementation of PopupWindow-similar floating window.
     * This exists to ensure frame-synchronization between window position updates and window
     * content updates. By using a PopupWindow, these events would happen in different frames,
     * producing a shakiness effect for the magnifier content.
     */
    private static class InternalPopupWindow {
        // The alpha set on the magnifier's content, which defines how
        // prominent the white background is.
        private static final int CONTENT_BITMAP_ALPHA = 242;
        // The z of the magnifier surface, defining its z order in the list of
        // siblings having the same parent surface (usually the main app surface).
        private static final int SURFACE_Z = 5;

        // Display associated to the view the magnifier is attached to.
        private final Display mDisplay;
        // The size of the content of the magnifier.
        private final int mContentWidth;
        private final int mContentHeight;
        // The size of the allocated surface.
        private final int mSurfaceWidth;
        private final int mSurfaceHeight;
        // The insets of the content inside the allocated surface.
        private final int mOffsetX;
        private final int mOffsetY;
        // The surface we allocate for the magnifier content + shadow.
        private final SurfaceSession mSurfaceSession;
        private final SurfaceControl mSurfaceControl;
        private final Surface mSurface;
        // The renderer used for the allocated surface.
        private final ThreadedRenderer.SimpleRenderer mRenderer;
        // The RenderNode used to draw the magnifier content in the surface.
        private final RenderNode mBitmapRenderNode;
        // The job that will be post'd to apply the pending magnifier updates to the surface.
        private final Runnable mMagnifierUpdater;
        // The handler where the magnifier updater jobs will be post'd.
        private final Handler mHandler;
        // The callback to be run after the next draw.
        private Callback mCallback;
        // The position of the magnifier content when the last draw was requested.
        private int mLastDrawContentPositionX;
        private int mLastDrawContentPositionY;

        // Members below describe the state of the magnifier. Reads/writes to them
        // have to be synchronized between the UI thread and the thread that handles
        // the pixel copy results. This is the purpose of mLock.
        private final Object mLock;
        // Whether a magnifier frame draw is currently pending in the UI thread queue.
        private boolean mFrameDrawScheduled;
        // The content bitmap.
        private Bitmap mBitmap;
        // Whether the next draw will be the first one for the current instance.
        private boolean mFirstDraw = true;
        // The window position in the parent surface. Might be applied during the next draw,
        // when mPendingWindowPositionUpdate is true.
        private int mWindowPositionX;
        private int mWindowPositionY;
        private boolean mPendingWindowPositionUpdate;

        // The lock used to synchronize the UI and render threads when a #destroy
        // is performed on the UI thread and a frame callback on the render thread.
        // When both mLock and mDestroyLock need to be held at the same time,
        // mDestroyLock should be acquired before mLock in order to avoid deadlocks.
        private final Object mDestroyLock = new Object();

        InternalPopupWindow(final Context context, final Display display,
                final Surface parentSurface,
                final int width, final int height, final float elevation, final float cornerRadius,
                final Handler handler, final Object lock, final Callback callback) {
            mDisplay = display;
            mLock = lock;
            mCallback = callback;

            mContentWidth = width;
            mContentHeight = height;
            mOffsetX = (int) (0.1f * width);
            mOffsetY = (int) (0.1f * height);
            // Setup the surface we will use for drawing the content and shadow.
            mSurfaceWidth = mContentWidth + 2 * mOffsetX;
            mSurfaceHeight = mContentHeight + 2 * mOffsetY;
            mSurfaceSession = new SurfaceSession(parentSurface);
            mSurfaceControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setSize(mSurfaceWidth, mSurfaceHeight)
                    .setName("magnifier surface")
                    .setFlags(SurfaceControl.HIDDEN)
                    .build();
            mSurface = new Surface();
            mSurface.copyFrom(mSurfaceControl);

            // Setup the RenderNode tree. The root has only one child, which contains the bitmap.
            mRenderer = new ThreadedRenderer.SimpleRenderer(
                    context,
                    "magnifier renderer",
                    mSurface
            );
            mBitmapRenderNode = createRenderNodeForBitmap(
                    "magnifier content",
                    elevation,
                    cornerRadius
            );

            final DisplayListCanvas canvas = mRenderer.getRootNode().start(width, height);
            try {
                canvas.insertReorderBarrier();
                canvas.drawRenderNode(mBitmapRenderNode);
                canvas.insertInorderBarrier();
            } finally {
                mRenderer.getRootNode().end(canvas);
            }

            // Initialize the update job and the handler where this will be post'd.
            mHandler = handler;
            mMagnifierUpdater = this::doDraw;
            mFrameDrawScheduled = false;
        }

        private RenderNode createRenderNodeForBitmap(final String name,
                final float elevation, final float cornerRadius) {
            final RenderNode bitmapRenderNode = RenderNode.create(name, null);

            // Define the position of the bitmap in the parent render node. The surface regions
            // outside the bitmap are used to draw elevation.
            bitmapRenderNode.setLeftTopRightBottom(mOffsetX, mOffsetY,
                    mOffsetX + mContentWidth, mOffsetY + mContentHeight);
            bitmapRenderNode.setElevation(elevation);

            final Outline outline = new Outline();
            outline.setRoundRect(0, 0, mContentWidth, mContentHeight, cornerRadius);
            outline.setAlpha(1.0f);
            bitmapRenderNode.setOutline(outline);
            bitmapRenderNode.setClipToOutline(true);

            // Create a dummy draw, which will be replaced later with real drawing.
            final DisplayListCanvas canvas = bitmapRenderNode.start(mContentWidth, mContentHeight);
            try {
                canvas.drawColor(0xFF00FF00);
            } finally {
                bitmapRenderNode.end(canvas);
            }

            return bitmapRenderNode;
        }

        /**
         * Sets the position of the magnifier content relative to the parent surface.
         * The position update will happen in the same frame with the next draw.
         * The method has to be called in a context that holds {@link #mLock}.
         *
         * @param contentX the x coordinate of the content
         * @param contentY the y coordinate of the content
         */
        public void setContentPositionForNextDraw(final int contentX, final int contentY) {
            mWindowPositionX = contentX - mOffsetX;
            mWindowPositionY = contentY - mOffsetY;
            mPendingWindowPositionUpdate = true;
            requestUpdate();
        }

        /**
         * Sets the content that should be displayed in the magnifier.
         * The update happens immediately, and possibly triggers a pending window movement set
         * by {@link #setContentPositionForNextDraw(int, int)}.
         * The method has to be called in a context that holds {@link #mLock}.
         *
         * @param bitmap the content bitmap
         */
        public void updateContent(final @NonNull Bitmap bitmap) {
            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mBitmap = bitmap;
            requestUpdate();
        }

        private void requestUpdate() {
            if (mFrameDrawScheduled) {
                return;
            }
            final Message request = Message.obtain(mHandler, mMagnifierUpdater);
            request.setAsynchronous(true);
            request.sendToTarget();
            mFrameDrawScheduled = true;
        }

        /**
         * Destroys this instance.
         */
        public void destroy() {
            synchronized (mDestroyLock) {
                mSurface.destroy();
            }
            synchronized (mLock) {
                mRenderer.destroy();
                mSurfaceControl.destroy();
                mSurfaceSession.kill();
                mHandler.removeCallbacks(mMagnifierUpdater);
                if (mBitmap != null) {
                    mBitmap.recycle();
                }
            }
        }

        private void doDraw() {
            final ThreadedRenderer.FrameDrawingCallback callback;

            // Draw the current bitmap to the surface, and prepare the callback which updates the
            // surface position. These have to be in the same synchronized block, in order to
            // guarantee the consistency between the bitmap content and the surface position.
            synchronized (mLock) {
                if (!mSurface.isValid()) {
                    // Probably #destroy() was called for the current instance, so we skip the draw.
                    return;
                }

                final DisplayListCanvas canvas =
                        mBitmapRenderNode.start(mContentWidth, mContentHeight);
                try {
                    canvas.drawColor(Color.WHITE);

                    final Rect srcRect = new Rect(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
                    final Rect dstRect = new Rect(0, 0, mContentWidth, mContentHeight);
                    final Paint paint = new Paint();
                    paint.setFilterBitmap(true);
                    paint.setAlpha(CONTENT_BITMAP_ALPHA);
                    canvas.drawBitmap(mBitmap, srcRect, dstRect, paint);
                } finally {
                    mBitmapRenderNode.end(canvas);
                }

                if (mPendingWindowPositionUpdate || mFirstDraw) {
                    // If the window has to be shown or moved, defer this until the next draw.
                    final boolean firstDraw = mFirstDraw;
                    mFirstDraw = false;
                    final boolean updateWindowPosition = mPendingWindowPositionUpdate;
                    mPendingWindowPositionUpdate = false;
                    final int pendingX = mWindowPositionX;
                    final int pendingY = mWindowPositionY;

                    callback = frame -> {
                        synchronized (mDestroyLock) {
                            if (!mSurface.isValid()) {
                                return;
                            }
                            synchronized (mLock) {
                                mRenderer.setLightCenter(mDisplay, pendingX, pendingY);
                                // Show or move the window at the content draw frame.
                                SurfaceControl.openTransaction();
                                mSurfaceControl.deferTransactionUntil(mSurface, frame);
                                if (updateWindowPosition) {
                                    mSurfaceControl.setPosition(pendingX, pendingY);
                                }
                                if (firstDraw) {
                                    mSurfaceControl.setLayer(SURFACE_Z);
                                    mSurfaceControl.show();
                                }
                                SurfaceControl.closeTransaction();
                            }
                        }
                    };
                } else {
                    callback = null;
                }

                mLastDrawContentPositionX = mWindowPositionX + mOffsetX;
                mLastDrawContentPositionY = mWindowPositionY + mOffsetY;
                mFrameDrawScheduled = false;
            }

            mRenderer.draw(callback);
            if (mCallback != null) {
                mCallback.onOperationComplete();
            }
        }
    }

    /**
     * Builder class for {@link Magnifier} objects.
     */
    public static class Builder {
        private @NonNull View mView;
        private @Px @IntRange(from = 0) int mWidth;
        private @Px @IntRange(from = 0) int mHeight;
        private float mZoom;
        private @FloatRange(from = 0f) float mElevation;
        private @FloatRange(from = 0f) float mCornerRadius;
        private int mHorizontalDefaultSourceToMagnifierOffset;
        private int mVerticalDefaultSourceToMagnifierOffset;

        /**
         * Construct a new builder for {@link Magnifier} objects.
         * @param view the view this magnifier is attached to
         */
        public Builder(@NonNull View view) {
            mView = Preconditions.checkNotNull(view);
            applyDefaults();
        }

        private void applyDefaults() {
            final Context context = mView.getContext();
            final TypedArray a = context.obtainStyledAttributes(null, R.styleable.Magnifier,
                    R.attr.magnifierStyle, 0);
            mWidth = a.getDimensionPixelSize(R.styleable.Magnifier_magnifierWidth, 0);
            mHeight = a.getDimensionPixelSize(R.styleable.Magnifier_magnifierHeight, 0);
            mElevation = a.getDimension(R.styleable.Magnifier_magnifierElevation, 0);
            mCornerRadius = getDeviceDefaultDialogCornerRadius();
            mZoom = a.getFloat(R.styleable.Magnifier_magnifierZoom, 0);
            mHorizontalDefaultSourceToMagnifierOffset =
                    a.getDimensionPixelSize(R.styleable.Magnifier_magnifierHorizontalOffset, 0);
            mVerticalDefaultSourceToMagnifierOffset =
                    a.getDimensionPixelSize(R.styleable.Magnifier_magnifierVerticalOffset, 0);
            a.recycle();
        }

        /**
         * Returns the device default theme dialog corner radius attribute.
         * We retrieve this from the device default theme to avoid
         * using the values set in the custom application themes.
         */
        private float getDeviceDefaultDialogCornerRadius() {
            final Context deviceDefaultContext =
                    new ContextThemeWrapper(mView.getContext(), R.style.Theme_DeviceDefault);
            final TypedArray ta = deviceDefaultContext.obtainStyledAttributes(
                    new int[]{android.R.attr.dialogCornerRadius});
            final float dialogCornerRadius = ta.getDimension(0, 0);
            ta.recycle();
            return dialogCornerRadius;
        }

        /**
         * Sets the size of the magnifier window, in pixels. Defaults to (100dp, 48dp).
         * Note that the size of the content being magnified and copied to the magnifier
         * will be computed as (window width / zoom, window height / zoom).
         * @param width the window width to be set
         * @param height the window height to be set
         */
        public Builder setSize(@Px @IntRange(from = 0) int width,
                @Px @IntRange(from = 0) int height) {
            Preconditions.checkArgumentPositive(width, "Width should be positive");
            Preconditions.checkArgumentPositive(height, "Height should be positive");
            mWidth = width;
            mHeight = height;
            return this;
        }

        /**
         * Sets the zoom to be applied to the chosen content before being copied to the magnifier.
         * A content of size (content_width, content_height) will be magnified to
         * (content_width * zoom, content_height * zoom), which will coincide with the size
         * of the magnifier. A zoom of 1 will translate to no magnification (the content will
         * be just copied to the magnifier with no scaling). The zoom defaults to 1.25.
         * @param zoom the zoom to be set
         */
        public Builder setZoom(@FloatRange(from = 0f) float zoom) {
            Preconditions.checkArgumentPositive(zoom, "Zoom should be positive");
            mZoom = zoom;
            return this;
        }

        /**
         * Sets the elevation of the magnifier window, in pixels. Defaults to 4dp.
         * @param elevation the elevation to be set
         */
        public Builder setElevation(@Px @FloatRange(from = 0) float elevation) {
            Preconditions.checkArgumentNonNegative(elevation, "Elevation should be non-negative");
            mElevation = elevation;
            return this;
        }

        /**
         * Sets the corner radius of the magnifier window, in pixels.
         * Defaults to the corner radius defined in the device default theme.
         * @param cornerRadius the corner radius to be set
         */
        public Builder setCornerRadius(@Px @FloatRange(from = 0) float cornerRadius) {
            Preconditions.checkArgumentNonNegative(cornerRadius,
                    "Corner radius should be non-negative");
            mCornerRadius = cornerRadius;
            return this;
        }

        /**
         * Sets an offset, in pixels, that should be added to the content source center to obtain
         * the position of the magnifier window, when the {@link #show(float, float)}
         * method is called. The offset is ignored when {@link #show(float, float, float, float)}
         * is used. The offset can be negative, and it defaults to (0dp, -42dp).
         * @param horizontalOffset the horizontal component of the offset
         * @param verticalOffset the vertical component of the offset
         */
        public Builder setDefaultSourceToMagnifierOffset(@Px int horizontalOffset,
                @Px int verticalOffset) {
            mHorizontalDefaultSourceToMagnifierOffset = horizontalOffset;
            mVerticalDefaultSourceToMagnifierOffset = verticalOffset;
            return this;
        }

        /**
         * Builds a {@link Magnifier} instance based on the configuration of this {@link Builder}.
         */
        public @NonNull Magnifier build() {
            return new Magnifier(this);
        }
    }

    // The rest of the file consists of test APIs.

    /**
     * See {@link #setOnOperationCompleteCallback(Callback)}.
     */
    @TestApi
    private Callback mCallback;

    /**
     * Sets a callback which will be invoked at the end of the next
     * {@link #show(float, float)} or {@link #update()} operation.
     *
     * @hide
     */
    @TestApi
    public void setOnOperationCompleteCallback(final Callback callback) {
        mCallback = callback;
        if (mWindow != null) {
            mWindow.mCallback = callback;
        }
    }

    /**
     * @return the content being currently displayed in the magnifier, as bitmap
     *
     * @hide
     */
    @TestApi
    public @Nullable Bitmap getContent() {
        if (mWindow == null) {
            return null;
        }
        synchronized (mWindow.mLock) {
            return Bitmap.createScaledBitmap(mWindow.mBitmap, mWindowWidth, mWindowHeight, true);
        }
    }

    /**
     * @return the content to be magnified, as bitmap
     *
     * @hide
     */
    @TestApi
    public @Nullable Bitmap getOriginalContent() {
        if (mWindow == null) {
            return null;
        }
        synchronized (mWindow.mLock) {
            return Bitmap.createBitmap(mWindow.mBitmap);
        }
    }

    /**
     * @return the size of the magnifier window in dp
     *
     * @hide
     */
    @TestApi
    public static PointF getMagnifierDefaultSize() {
        final Resources resources = Resources.getSystem();
        final float density = resources.getDisplayMetrics().density;
        final PointF size = new PointF();
        size.x = resources.getDimension(R.dimen.magnifier_width) / density;
        size.y = resources.getDimension(R.dimen.magnifier_height) / density;
        return size;
    }

    /**
     * @hide
     */
    @TestApi
    public interface Callback {
        /**
         * Callback called after the drawing for a magnifier update has happened.
         */
        void onOperationComplete();
    }
}
