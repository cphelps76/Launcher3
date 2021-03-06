/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.launcher3.widget;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.launcher3.CellLayout;
import com.android.launcher3.DeleteDropTarget;
import com.android.launcher3.DragController;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget.DragObject;
import com.android.launcher3.FastBitmapDrawable;
import com.android.launcher3.Folder;
import com.android.launcher3.IconCache;
import com.android.launcher3.Insettable;
import com.android.launcher3.ItemInfo;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.PendingAddItemInfo;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.WidgetPreviewLoader;
import com.android.launcher3.Workspace;

import java.util.ArrayList;

/**
 * The widgets list view container.
 */
public class WidgetsContainerView extends FrameLayout implements Insettable, View.OnTouchListener,
        View.OnLongClickListener, DragSource{

    private static final String TAG = "WidgetContainerView";
    private static final boolean DEBUG = false;

    /* {@link RecyclerView} will keep following # of views in cache, before recycling. */
    private static final int WIDGET_CACHE_SIZE = 2;

    /* Global instances that are used inside this container. */
    private Launcher mLauncher;
    private DragController mDragController;
    private IconCache mIconCache;

    /* Data model for the widget */
    private WidgetsModel mWidgets;

    /* Recycler view related member variables */
    private RecyclerView mView;
    private WidgetsListAdapter mAdapter;

    /* Dragging related. */
    private boolean mDraggingWidget = false;    // TODO(hyunyoungs): seems not needed? check!
    private Point mLastTouchDownPos = new Point();

    /* Rendering related. */
    private WidgetPreviewLoader mWidgetPreviewLoader;
    private Rect mPadding = new Rect();

    public WidgetsContainerView(Context context) {
        this(context, null);
    }

    public WidgetsContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WidgetsContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mLauncher = (Launcher) context;
        mDragController = mLauncher.getDragController();

        mAdapter = new WidgetsListAdapter(context, this, mLauncher, this, mLauncher);
        mWidgets = new WidgetsModel(context, mAdapter);
        mAdapter.setWidgetsModel(mWidgets);
        mIconCache = (LauncherAppState.getInstance()).getIconCache();

        if (DEBUG) {
            Log.d(TAG, "WidgetsContainerView constructor");
        }
    }

    @Override
    protected void onFinishInflate() {
        if (DEBUG) {
            Log.d(TAG, String.format("onFinishInflate [widgets size=%d]",
                    mWidgets.getPackageSize()));
        }
        mView = (RecyclerView) findViewById(R.id.widgets_list_view);
        mView.setAdapter(mAdapter);
        mView.setLayoutManager(new LinearLayoutManager(getContext()));
        mView.setItemViewCacheSize(WIDGET_CACHE_SIZE);

        mPadding.set(getPaddingLeft(), getPaddingTop(), getPaddingRight(),
                getPaddingBottom());
    }

    //
    // Returns views used for launcher transitions.
    //

    public View getContentView() {
        return findViewById(R.id.widgets_content);
    }

    public View getRevealView() {
        // TODO(hyunyoungs): temporarily use apps view transition.
        return findViewById(R.id.widgets_reveal_view);
    }

    public void scrollToTop() {
        mView.scrollToPosition(0);
        if (DEBUG) {
            Log.d(TAG, String.format("scrollToTop, [widgets size=%d]",
                    mWidgets.getPackageSize()));
        }
    }

    //
    // Touch related handling.
    //

    @Override
    public boolean onLongClick(View v) {
        if (DEBUG) {
            Log.d(TAG, String.format("onLonglick [v=%s]", v));
        }

        // Return early if this is not initiated from a touch
        if (!v.isInTouchMode()) return false;
        // When we have exited all apps or are in transition, disregard long clicks
        if (!mLauncher.isWidgetsViewVisible() ||
                mLauncher.getWorkspace().isSwitchingState()) return false;
        // Return if global dragging is not enabled
        Log.d(TAG, String.format("onLonglick dragging enabled?.", v));
        if (!mLauncher.isDraggingEnabled()) return false;

        return beginDragging(v);
    }

    private boolean beginDragging(View v) {
        if (v instanceof WidgetCell) {
            if (!beginDraggingWidget((WidgetCell) v)) {
                return false;
            }
        } else {
            Log.e(TAG, "Unexpected dragging view: " + v);
        }

        // We delay entering spring-loaded mode slightly to make sure the UI
        // thready is free of any work.
        postDelayed(new Runnable() {
            @Override
            public void run() {
                // We don't enter spring-loaded mode if the drag has been cancelled
                if (mLauncher.getDragController().isDragging()) {
                    // Go into spring loaded mode (must happen before we startDrag())
                    mLauncher.enterSpringLoadedDragMode();
                }
            }
        }, 150);

        return true;
    }

    private boolean beginDraggingWidget(WidgetCell v) {
        mDraggingWidget = true;
        // Get the widget preview as the drag representation
        ImageView image = (ImageView) v.findViewById(R.id.widget_preview);
        PendingAddItemInfo createItemInfo = (PendingAddItemInfo) v.getTag();

        // If the ImageView doesn't have a drawable yet, the widget preview hasn't been loaded and
        // we abort the drag.
        if (image.getDrawable() == null) {
            mDraggingWidget = false;
            return false;
        }

        // Compose the drag image
        Bitmap preview;
        Bitmap outline;
        float scale = 1f;
        Point previewPadding = null;

        if (createItemInfo instanceof PendingAddWidgetInfo) {
            // This can happen in some weird cases involving multi-touch. We can't start dragging
            // the widget if this is null, so we break out.

            PendingAddWidgetInfo createWidgetInfo = (PendingAddWidgetInfo) createItemInfo;
            int[] size = mLauncher.getWorkspace().estimateItemSize(createWidgetInfo, true);

            FastBitmapDrawable previewDrawable = (FastBitmapDrawable) image.getDrawable();
            float minScale = 1.25f;
            int maxWidth = Math.min((int) (previewDrawable.getIntrinsicWidth() * minScale), size[0]);

            int[] previewSizeBeforeScale = new int[1];
            preview = getWidgetPreviewLoader().generateWidgetPreview(createWidgetInfo.info,
                    maxWidth, null, previewSizeBeforeScale);
            // Compare the size of the drag preview to the preview in the AppsCustomize tray
            int previewWidthInAppsCustomize = Math.min(previewSizeBeforeScale[0],
                    v.getActualItemWidth());
            scale = previewWidthInAppsCustomize / (float) preview.getWidth();

            // The bitmap in the AppsCustomize tray is always the the same size, so there
            // might be extra pixels around the preview itself - this accounts for that
            if (previewWidthInAppsCustomize < previewDrawable.getIntrinsicWidth()) {
                int padding =
                        (previewDrawable.getIntrinsicWidth() - previewWidthInAppsCustomize) / 2;
                previewPadding = new Point(padding, 0);
            }
        } else {
            PendingAddShortcutInfo createShortcutInfo = (PendingAddShortcutInfo) v.getTag();
            Drawable icon = mIconCache.getFullResIcon(createShortcutInfo.activityInfo);
            preview = Utilities.createIconBitmap(icon, mLauncher);
            createItemInfo.spanX = createItemInfo.spanY = 1;
        }

        // Don't clip alpha values for the drag outline if we're using the default widget preview
        boolean clipAlpha = !(createItemInfo instanceof PendingAddWidgetInfo &&
                (((PendingAddWidgetInfo) createItemInfo).previewImage == 0));

        // Save the preview for the outline generation, then dim the preview
        outline = Bitmap.createScaledBitmap(preview, preview.getWidth(), preview.getHeight(),
                false);

        // Start the drag
        mLauncher.lockScreenOrientation();
        mLauncher.getWorkspace().onDragStartedWithItem(createItemInfo, outline, clipAlpha);
        mDragController.startDrag(image, preview, this, createItemInfo,
                DragController.DRAG_ACTION_COPY, previewPadding, scale);
        outline.recycle();
        preview.recycle();
        return true;
    }

    /*
     * @see android.view.View.OnTouchListener#onTouch(android.view.View, android.view.MotionEvent)
     */
    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        Log.d(TAG, String.format("onTouch [MotionEvent=%s]", ev));
        if (ev.getAction() == MotionEvent.ACTION_DOWN ||
                ev.getAction() == MotionEvent.ACTION_MOVE) {
            mLastTouchDownPos.set((int) ev.getX(), (int) ev.getY());
        }
        return false;
    }

    //
    // Drag related handling methods that implement {@link DragSource} interface.
    //

    @Override
    public boolean supportsFlingToDelete() {
        return false;
    }

    @Override
    public boolean supportsAppInfoDropTarget() {
        return true;
    }

    /*
     * Both this method and {@link #supportsFlingToDelete} has to return {@code false} for the
     * {@link DeleteDropTarget} to be invisible.)
     */
    @Override
    public boolean supportsDeleteDropTarget() {
        return false;
    }

    @Override
    public float getIntrinsicIconScaleFactor() {
        return 0;
    }

    @Override
    public void onFlingToDeleteCompleted() {
        // We just dismiss the drag when we fling, so cleanup here
        mLauncher.exitSpringLoadedDragModeDelayed(true,
                Launcher.EXIT_SPRINGLOADED_MODE_SHORT_TIMEOUT, null);
        mLauncher.unlockScreenOrientation(false);
    }

    @Override
    public void onDropCompleted(View target, DragObject d, boolean isFlingToDelete,
            boolean success) {
        if (isFlingToDelete || !success || (target != mLauncher.getWorkspace() &&
                !(target instanceof DeleteDropTarget) && !(target instanceof Folder))) {
            // Exit spring loaded mode if we have not successfully dropped or have not handled the
            // drop in Workspace
            mLauncher.exitSpringLoadedDragModeDelayed(true,
                    Launcher.EXIT_SPRINGLOADED_MODE_SHORT_TIMEOUT, null);
        }
        mLauncher.unlockScreenOrientation(false);

        // Display an error message if the drag failed due to there not being enough space on the
        // target layout we were dropping on.
        if (!success) {
            boolean showOutOfSpaceMessage = false;
            if (target instanceof Workspace) {
                int currentScreen = mLauncher.getCurrentWorkspaceScreen();
                Workspace workspace = (Workspace) target;
                CellLayout layout = (CellLayout) workspace.getChildAt(currentScreen);
                ItemInfo itemInfo = (ItemInfo) d.dragInfo;
                if (layout != null) {
                    layout.calculateSpans(itemInfo);
                    showOutOfSpaceMessage =
                            !layout.findCellForSpan(null, itemInfo.spanX, itemInfo.spanY);
                }
            }
            if (showOutOfSpaceMessage) {
                mLauncher.showOutOfSpaceMessage(false);
            }
            d.deferDragViewCleanupPostAnimation = false;
        }
    }

    //
    // Container rendering related.
    //

    /*
     * @see Insettable#setInsets(Rect)
     */
    @Override
    public void setInsets(Rect insets) {
        setPadding(mPadding.left + insets.left, mPadding.top + insets.top,
                mPadding.right + insets.right, mPadding.bottom + insets.bottom);
    }

    /**
     * Initialize the widget data model.
     */
    public void addWidgets(ArrayList<Object> widgetsShortcuts, PackageManager pm) {
        mWidgets.addWidgetsAndShortcuts(widgetsShortcuts, pm);
    }

    private WidgetPreviewLoader getWidgetPreviewLoader() {
        if (mWidgetPreviewLoader == null) {
            mWidgetPreviewLoader = LauncherAppState.getInstance().getWidgetCache();
        }
        return mWidgetPreviewLoader;
    }

}