/**
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

package com.android.launcher3;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Switch;

import com.android.launcher3.FocusHelper.PagedFolderKeyEventListener;
import com.android.launcher3.PageIndicator.PageMarkerResources;
import com.android.launcher3.Workspace.ItemOperator;
import com.android.launcher3.util.Thunk;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class FolderPagedView extends PagedView {

    private static final String TAG = "FolderPagedView";

    private static final boolean ALLOW_FOLDER_SCROLL = true;

    // To enable this flag, user_folder.xml needs to be modified to add sort button.
    private static final boolean ALLOW_ITEM_SORTING = false;

    private static final int REORDER_ANIMATION_DURATION = 230;
    private static final int START_VIEW_REORDER_DELAY = 30;
    private static final float VIEW_REORDER_DELAY_FACTOR = 0.9f;

    private static final int SPAN_TO_PAGE_DURATION = 350;
    private static final int SORT_ANIM_HIDE_DURATION = 130;
    private static final int SORT_ANIM_SHOW_DURATION = 160;

    /**
     * Fraction of the width to scroll when showing the next page hint.
     */
    private static final float SCROLL_HINT_FRACTION = 0.07f;

    private static final int[] sTempPosArray = new int[2];

    // TODO: Remove this restriction
    private static final int MAX_ITEMS_PER_PAGE = 4;

    public final boolean rtlLayout;

    private final LayoutInflater mInflater;
    private final IconCache mIconCache;

    @Thunk final HashMap<View, Runnable> mPendingAnimations = new HashMap<>();

    private final int mMaxCountX;
    private final int mMaxCountY;
    private final int mMaxItemsPerPage;

    private int mAllocatedContentSize;
    private int mGridCountX;
    private int mGridCountY;

    private Folder mFolder;
    private FocusIndicatorView mFocusIndicatorView;
    private PagedFolderKeyEventListener mKeyListener;

    private View mSortButton;
    private Switch mSortSwitch;
    private View mPageIndicator;

    private boolean mSortOperationPending;
    boolean mIsSorted;

    public FolderPagedView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LauncherAppState app = LauncherAppState.getInstance();
        setDataIsReady();

        DeviceProfile grid = app.getDynamicGrid().getDeviceProfile();
        if (ALLOW_FOLDER_SCROLL) {
            mMaxCountX = Math.min((int) grid.numColumns, MAX_ITEMS_PER_PAGE);
            mMaxCountY = Math.min((int) grid.numRows, MAX_ITEMS_PER_PAGE);
        } else {
            mMaxCountX = (int) grid.numColumns;
            mMaxCountY = (int) grid.numRows;
        }

        mMaxItemsPerPage = mMaxCountX * mMaxCountY;

        mInflater = LayoutInflater.from(context);
        mIconCache = app.getIconCache();

        rtlLayout = getResources().getConfiguration().getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    public void setFolder(Folder folder) {
        mFolder = folder;
        mFocusIndicatorView = (FocusIndicatorView) folder.findViewById(R.id.focus_indicator);
        mKeyListener = new PagedFolderKeyEventListener(folder);
        mPageIndicator = folder.findViewById(R.id.folder_page_indicator);

        if (ALLOW_ITEM_SORTING) {
            // Initialize {@link #mSortSwitch} and {@link #mSortButton}.
        }
    }

    /**
     * Called when sort button is clicked.
     */
    private void onSortClicked() {
        if (mSortOperationPending) {
            return;
        }
        if (mIsSorted) {
            setIsSorted(false, true);
        } else {
            mSortOperationPending = true;
            doSort();
        }
    }

    private void setIsSorted(boolean isSorted, boolean saveChanges) {
        mIsSorted = isSorted;
        if (ALLOW_ITEM_SORTING) {
            mSortSwitch.setChecked(isSorted);
            mFolder.mInfo.setOption(FolderInfo.FLAG_ITEMS_SORTED, isSorted,
                    saveChanges ? mFolder.mLauncher : null);
        }
    }

    /**
     * Sorts the contents of the folder and animates the icons on the first page to reflect
     * the changes.
     * Steps:
     *      1. Scroll to first page
     *      2. Sort all icons in one go
     *      3. Re-apply the old IconInfos on the first page (so that there is no instant change)
     *      4. Animate each view individually to reflect the new icon.
     */
    private void doSort() {
        if (!mSortOperationPending) {
            return;
        }
        if (getNextPage() != 0) {
            snapToPage(0, SPAN_TO_PAGE_DURATION, new DecelerateInterpolator());
            return;
        }

        mSortOperationPending = false;
        ShortcutInfo[][] oldItems = new ShortcutInfo[mGridCountX][mGridCountY];
        CellLayout currentPage = getCurrentCellLayout();
        for (int x = 0; x < mGridCountX; x++) {
            for (int y = 0; y < mGridCountY; y++) {
                View v = currentPage.getChildAt(x, y);
                if (v != null) {
                    oldItems[x][y] = (ShortcutInfo) v.getTag();
                }
            }
        }

        ArrayList<View> views = new ArrayList<View>(mFolder.getItemsInReadingOrder());
        Collections.sort(views, new ViewComparator());
        arrangeChildren(views, views.size());

        int delay = 0;
        float delayAmount = START_VIEW_REORDER_DELAY;
        final Interpolator hideInterpolator = new DecelerateInterpolator(2);
        final Interpolator showInterpolator = new OvershootInterpolator(0.8f);

        currentPage = getCurrentCellLayout();
        for (int x = 0; x < mGridCountX; x++) {
            for (int y = 0; y < mGridCountY; y++) {
                final BubbleTextView v = (BubbleTextView) currentPage.getChildAt(x, y);
                if (v != null) {
                    final ShortcutInfo info = (ShortcutInfo) v.getTag();
                    final Runnable clearPending = new Runnable() {

                        @Override
                        public void run() {
                            mPendingAnimations.remove(v);
                            v.setScaleX(1);
                            v.setScaleY(1);
                        }
                    };
                    if (oldItems[x][y] == null) {
                        v.setScaleX(0);
                        v.setScaleY(0);
                        v.animate().setDuration(SORT_ANIM_SHOW_DURATION)
                            .setStartDelay(SORT_ANIM_HIDE_DURATION + delay)
                            .scaleX(1).scaleY(1).setInterpolator(showInterpolator)
                            .withEndAction(clearPending);
                        mPendingAnimations.put(v, clearPending);
                    } else {
                        // Apply the old iconInfo so that there is no sudden change.
                        v.applyFromShortcutInfo(oldItems[x][y], mIconCache, false);
                        v.animate().setStartDelay(delay).setDuration(SORT_ANIM_HIDE_DURATION)
                            .scaleX(0).scaleY(0)
                            .setInterpolator(hideInterpolator)
                            .withEndAction(new Runnable() {

                                @Override
                                public void run() {
                                    // Apply the new iconInfo as part of the animation.
                                    v.applyFromShortcutInfo(info, mIconCache, false);
                                    v.animate().scaleX(1).scaleY(1)
                                        .setDuration(SORT_ANIM_SHOW_DURATION).setStartDelay(0)
                                        .setInterpolator(showInterpolator)
                                        .withEndAction(clearPending);
                                }
                       });
                       mPendingAnimations.put(v, new Runnable() {

                           @Override
                           public void run() {
                               clearPending.run();
                               v.applyFromShortcutInfo(info, mIconCache, false);
                           }
                        });
                    }
                    delay += delayAmount;
                    delayAmount *= VIEW_REORDER_DELAY_FACTOR;
                }
            }
        }

        setIsSorted(true, true);
    }

    /**
     * Sets up the grid size such that {@param count} items can fit in the grid.
     * The grid size is calculated such that countY <= countX and countX = ceil(sqrt(count)) while
     * maintaining the restrictions of {@link #mMaxCountX} &amp; {@link #mMaxCountY}.
     */
    private void setupContentDimensions(int count) {
        mAllocatedContentSize = count;
        boolean done;
        if (count >= mMaxItemsPerPage) {
            mGridCountX = mMaxCountX;
            mGridCountY = mMaxCountY;
            done = true;
        } else {
            done = false;
        }

        while (!done) {
            int oldCountX = mGridCountX;
            int oldCountY = mGridCountY;
            if (mGridCountX * mGridCountY < count) {
                // Current grid is too small, expand it
                if ((mGridCountX <= mGridCountY || mGridCountY == mMaxCountY) && mGridCountX < mMaxCountX) {
                    mGridCountX++;
                } else if (mGridCountY < mMaxCountY) {
                    mGridCountY++;
                }
                if (mGridCountY == 0) mGridCountY++;
            } else if ((mGridCountY - 1) * mGridCountX >= count && mGridCountY >= mGridCountX) {
                mGridCountY = Math.max(0, mGridCountY - 1);
            } else if ((mGridCountX - 1) * mGridCountY >= count) {
                mGridCountX = Math.max(0, mGridCountX - 1);
            }
            done = mGridCountX == oldCountX && mGridCountY == oldCountY;
        }

        // Update grid size
        for (int i = getPageCount() - 1; i >= 0; i--) {
            getPageAt(i).setGridSize(mGridCountX, mGridCountY);
        }
    }

    /**
     * Binds items to the layout.
     * @return list of items that could not be bound, probably because we hit the max size limit.
     */
    public ArrayList<ShortcutInfo> bindItems(ArrayList<ShortcutInfo> items) {
        mIsSorted = ALLOW_ITEM_SORTING && mFolder.mInfo.hasOption(FolderInfo.FLAG_ITEMS_SORTED);
        ArrayList<View> icons = new ArrayList<View>();
        ArrayList<ShortcutInfo> extra = new ArrayList<ShortcutInfo>();

        for (ShortcutInfo item : items) {
            if (!ALLOW_FOLDER_SCROLL && icons.size() >= mMaxItemsPerPage) {
                extra.add(item);
            } else {
                icons.add(createNewView(item));
            }
        }
        arrangeChildren(icons, icons.size(), false);
        return extra;
    }

    /**
     * Create space for a new item at the end, and returns the rank for that item.
     * Also sets the current page to the last page.
     */
    public int allocateRankForNewItem(ShortcutInfo info) {
        int rank = getItemCount();
        ArrayList<View> views = new ArrayList<View>(mFolder.getItemsInReadingOrder());
        if (ALLOW_ITEM_SORTING && mIsSorted) {
            View tmp = new View(getContext());
            tmp.setTag(info);
            int index = Collections.binarySearch(views, tmp, new ViewComparator());
            if (index < 0) {
                rank = -index - 1;
            } else {
                // Item with same name already exists.
                // We will just insert it before that item.
                rank = index;
            }

        }

        views.add(rank, null);
        arrangeChildren(views, views.size(), false);
        setCurrentPage(rank / mMaxItemsPerPage);
        return rank;
    }

    public View createAndAddViewForRank(ShortcutInfo item, int rank) {
        View icon = createNewView(item);
        addViewForRank(icon, item, rank);
        return icon;
    }

    /**
     * Adds the {@param view} to the layout based on {@param rank} and updated the position
     * related attributes. It assumes that {@param item} is already attached to the view.
     */
    public void addViewForRank(View view, ShortcutInfo item, int rank) {
        int pagePos = rank % mMaxItemsPerPage;
        int pageNo = rank / mMaxItemsPerPage;

        item.rank = rank;
        item.cellX = pagePos % mGridCountX;
        item.cellY = pagePos / mGridCountX;

        CellLayout.LayoutParams lp = (CellLayout.LayoutParams) view.getLayoutParams();
        lp.cellX = item.cellX;
        lp.cellY = item.cellY;
        getPageAt(pageNo).addViewToCellLayout(
                view, -1, mFolder.mLauncher.getViewIdForItem(item), lp, true);
    }

    @SuppressLint("InflateParams")
    private View createNewView(ShortcutInfo item) {
        final BubbleTextView textView = (BubbleTextView) mInflater.inflate(
                R.layout.folder_application, null, false);
        textView.applyFromShortcutInfo(item, mIconCache, false);
        textView.setOnClickListener(mFolder);
        textView.setOnLongClickListener(mFolder);
        textView.setOnFocusChangeListener(mFocusIndicatorView);
        textView.setOnKeyListener(mKeyListener);

        textView.setLayoutParams(new CellLayout.LayoutParams(
                item.cellX, item.cellY, item.spanX, item.spanY));
        return textView;
    }

    @Override
    public CellLayout getPageAt(int index) {
        return (CellLayout) getChildAt(index);
    }

    public void removeCellLayoutView(View view) {
        for (int i = getChildCount() - 1; i >= 0; i --) {
            getPageAt(i).removeView(view);
        }
    }

    public CellLayout getCurrentCellLayout() {
        return getPageAt(getNextPage());
    }

    private CellLayout createAndAddNewPage() {
        DeviceProfile grid = LauncherAppState.getInstance().getDynamicGrid().getDeviceProfile();
        CellLayout page = new CellLayout(getContext());
        page.setCellDimensions(grid.folderCellWidthPx, grid.folderCellHeightPx);
        page.getShortcutsAndWidgets().setMotionEventSplittingEnabled(false);
        page.setInvertIfRtl(true);
        page.setGridSize(mGridCountX, mGridCountY);

        LayoutParams lp = generateDefaultLayoutParams();
        lp.isFullScreenPage = true;
        addView(page, -1, lp);
        return page;
    }

    public void setFixedSize(int width, int height) {
        for (int i = getChildCount() - 1; i >= 0; i --) {
            ((CellLayout) getChildAt(i)).setFixedSize(width, height);
        }
    }

    public void removeItem(View v) {
        for (int i = getChildCount() - 1; i >= 0; i --) {
            getPageAt(i).removeView(v);
        }
    }

    /**
     * Updates position and rank of all the children in the view.
     * It essentially removes all views from all the pages and then adds them again in appropriate
     * page.
     *
     * @param list the ordered list of children.
     * @param itemCount if greater than the total children count, empty spaces are left
     * at the end, otherwise it is ignored.
     *
     */
    public void arrangeChildren(ArrayList<View> list, int itemCount) {
        arrangeChildren(list, itemCount, true);
    }

    private void arrangeChildren(ArrayList<View> list, int itemCount, boolean saveChanges) {
        ArrayList<CellLayout> pages = new ArrayList<CellLayout>();
        for (int i = 0; i < getChildCount(); i++) {
            CellLayout page = (CellLayout) getChildAt(i);
            page.removeAllViews();
            pages.add(page);
        }
        setupContentDimensions(itemCount);

        Iterator<CellLayout> pageItr = pages.iterator();
        CellLayout currentPage = null;

        int position = 0;
        int newX, newY, rank;

        boolean isSorted = mIsSorted;

        ViewComparator comparator = new ViewComparator();
        View lastView = null;
        rank = 0;
        for (int i = 0; i < itemCount; i++) {
            View v = list.size() > i ? list.get(i) : null;
            if (currentPage == null || position >= mMaxItemsPerPage) {
                // Next page
                if (pageItr.hasNext()) {
                    currentPage = pageItr.next();
                } else {
                    currentPage = createAndAddNewPage();
                }
                position = 0;
            }

            if (v != null) {
                if (lastView != null) {
                    isSorted &= comparator.compare(lastView, v) <= 0;
                }

                CellLayout.LayoutParams lp = (CellLayout.LayoutParams) v.getLayoutParams();
                newX = position % mGridCountX;
                newY = position / mGridCountX;
                ItemInfo info = (ItemInfo) v.getTag();
                if (info.cellX != newX || info.cellY != newY || info.rank != rank) {
                    info.cellX = newX;
                    info.cellY = newY;
                    info.rank = rank;
                    if (saveChanges) {
                        LauncherModel.addOrMoveItemInDatabase(getContext(), info,
                                mFolder.mInfo.id, 0, info.cellX, info.cellY);
                    }
                }
                lp.cellX = info.cellX;
                lp.cellY = info.cellY;
                currentPage.addViewToCellLayout(
                        v, -1, mFolder.mLauncher.getViewIdForItem(info), lp, true);
            }

            lastView = v;
            rank ++;
            position++;
        }

        // Remove extra views.
        boolean removed = false;
        while (pageItr.hasNext()) {
            removeView(pageItr.next());
            removed = true;
        }
        if (removed) {
            setCurrentPage(0);
        }

        setEnableOverscroll(getPageCount() > 1);

        // Update footer
        if (ALLOW_ITEM_SORTING) {
            setIsSorted(isSorted, saveChanges);
            if (getPageCount() > 1) {
                mPageIndicator.setVisibility(View.VISIBLE);
                mSortButton.setVisibility(View.VISIBLE);
                mFolder.mFolderName.setGravity(rtlLayout ? Gravity.RIGHT : Gravity.LEFT);
            } else {
                mPageIndicator.setVisibility(View.GONE);
                mSortButton.setVisibility(View.GONE);
                mFolder.mFolderName.setGravity(Gravity.CENTER_HORIZONTAL);
            }
        } else {
            int indicatorVisibility = mPageIndicator.getVisibility();
            mPageIndicator.setVisibility(getPageCount() > 1 ? View.VISIBLE : View.GONE);
            if (indicatorVisibility != mPageIndicator.getVisibility()) {
                mFolder.updateFooterHeight();
            }
        }
    }

    @Override
    protected void loadAssociatedPages(int page, boolean immediateAndOnly) { }

    @Override
    public void syncPages() { }

    @Override
    public void syncPageItems(int page, boolean immediate) { }

    public int getDesiredWidth() {
        return getPageCount() > 0 ? getPageAt(0).getDesiredWidth() : 0;
    }

    public int getDesiredHeight()  {
        return  getPageCount() > 0 ? getPageAt(0).getDesiredHeight() : 0;
    }

    public int getItemCount() {
        int lastPageIndex = getChildCount() - 1;
        if (lastPageIndex < 0) {
            // If there are no pages, nothing has yet been added to the folder.
            return 0;
        }
        return getPageAt(lastPageIndex).getShortcutsAndWidgets().getChildCount()
                + lastPageIndex * mMaxItemsPerPage;
    }

    /**
     * @return the rank of the cell nearest to the provided pixel position.
     */
    public int findNearestArea(int pixelX, int pixelY) {
        int pageIndex = getNextPage();
        CellLayout page = getPageAt(pageIndex);
        page.findNearestArea(pixelX, pixelY, 1, 1, null, false, sTempPosArray);
        if (mFolder.isLayoutRtl()) {
            sTempPosArray[0] = page.getCountX() - sTempPosArray[0] - 1;
        }
        return Math.min(mAllocatedContentSize - 1,
                pageIndex * mMaxItemsPerPage + sTempPosArray[1] * mGridCountX + sTempPosArray[0]);
    }

    @Override
    protected PageMarkerResources getPageIndicatorMarker(int pageIndex) {
        return new PageMarkerResources(R.drawable.ic_pageindicator_current_folder,
                R.drawable.ic_pageindicator_default_folder);
    }

    public boolean isFull() {
        return !ALLOW_FOLDER_SCROLL && getItemCount() >= mMaxItemsPerPage;
    }

    public View getLastItem() {
        if (getChildCount() < 1) {
            return null;
        }
        ShortcutAndWidgetContainer lastContainer = getCurrentCellLayout().getShortcutsAndWidgets();
        int lastRank = lastContainer.getChildCount() - 1;
        if (mGridCountX > 0) {
            return lastContainer.getChildAt(lastRank % mGridCountX, lastRank / mGridCountX);
        } else {
            return lastContainer.getChildAt(lastRank);
        }
    }

    /**
     * Iterates over all its items in a reading order.
     * @return the view for which the operator returned true.
     */
    public View iterateOverItems(ItemOperator op) {
        for (int k = 0 ; k < getChildCount(); k++) {
            CellLayout page = getPageAt(k);
            for (int j = 0; j < page.getCountY(); j++) {
                for (int i = 0; i < page.getCountX(); i++) {
                    View v = page.getChildAt(i, j);
                    if ((v != null) && op.evaluate((ItemInfo) v.getTag(), v, this)) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    public String getAccessibilityDescription() {
        return String.format(getContext().getString(R.string.folder_opened),
                mGridCountX, mGridCountY);
    }

    /**
     * Sets the focus on the first visible child.
     */
    public void setFocusOnFirstChild() {
        View firstChild = getCurrentCellLayout().getChildAt(0, 0);
        if (firstChild != null) {
            firstChild.requestFocus();
        }
    }

    @Override
    protected void notifyPageSwitchListener() {
        super.notifyPageSwitchListener();
        if (mFolder != null) {
            mFolder.updateTextViewFocus();
        }
        if (ALLOW_ITEM_SORTING && mSortOperationPending && getNextPage() == 0) {
            post(new Runnable() {

                @Override
                public void run() {
                    if (mSortOperationPending) {
                        doSort();
                    }
                }
            });
        }
    }

    /**
     * Scrolls the current view by a fraction
     */
    public void showScrollHint(int direction) {
        float fraction = (direction == DragController.SCROLL_LEFT) ^ rtlLayout
                ? -SCROLL_HINT_FRACTION : SCROLL_HINT_FRACTION;
        int hint = (int) (fraction * getWidth());
        int scroll = getScrollForPage(getNextPage()) + hint;
        int delta = scroll - mUnboundedScrollX;
        if (delta != 0) {
            mScroller.setInterpolator(new DecelerateInterpolator());
            mScroller.startScroll(mUnboundedScrollX, 0, delta, 0, Folder.SCROLL_HINT_DURATION);
            invalidate();
        }
    }

    public void clearScrollHint() {
        if (mUnboundedScrollX != getScrollForPage(getNextPage())) {
            snapToPage(getNextPage());
        }
    }

    /**
     * Finish animation all the views which are animating across pages
     */
    public void completePendingPageChanges() {
        if (!mPendingAnimations.isEmpty()) {
            HashMap<View, Runnable> pendingViews = new HashMap<>(mPendingAnimations);
            for (Map.Entry<View, Runnable> e : pendingViews.entrySet()) {
                e.getKey().animate().cancel();
                e.getValue().run();
            }
        }
    }

    public boolean rankOnCurrentPage(int rank) {
        int p = rank / mMaxItemsPerPage;
        return p == getNextPage();
    }

    @Override
    protected void onPageBeginMoving() {
        super.onPageBeginMoving();
        getVisiblePages(sTempPosArray);
        for (int i = sTempPosArray[0]; i <= sTempPosArray[1]; i++) {
            verifyVisibleHighResIcons(i);
        }
    }

    /**
     * Ensures that all the icons on the given page are of high-res
     */
    public void verifyVisibleHighResIcons(int pageNo) {
        CellLayout page = getPageAt(pageNo);
        if (page != null) {
            ShortcutAndWidgetContainer parent = page.getShortcutsAndWidgets();
            for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                ((BubbleTextView) parent.getChildAt(i)).verifyHighRes();
            }
        }
    }

    /**
     * Reorders the items such that the {@param empty} spot moves to {@param target}
     */
    public void realTimeReorder(int empty, int target) {
        completePendingPageChanges();
        int delay = 0;
        float delayAmount = START_VIEW_REORDER_DELAY;

        // Animation only happens on the current page.
        int pageToAnimate = getNextPage();

        int pageT = target / mMaxItemsPerPage;
        int pagePosT = target % mMaxItemsPerPage;

        if (pageT != pageToAnimate) {
            Log.e(TAG, "Cannot animate when the target cell is invisible");
        }
        int pagePosE = empty % mMaxItemsPerPage;
        int pageE = empty / mMaxItemsPerPage;

        int startPos, endPos;
        int moveStart, moveEnd;
        int direction;

        if (target == empty) {
            // No animation
            return;
        } else if (target > empty) {
            // Items will move backwards to make room for the empty cell.
            direction = 1;

            // If empty cell is in a different page, move them instantly.
            if (pageE < pageToAnimate) {
                moveStart = empty;
                // Instantly move the first item in the current page.
                moveEnd = pageToAnimate * mMaxItemsPerPage;
                // Animate the 2nd item in the current page, as the first item was already moved to
                // the last page.
                startPos = 0;
            } else {
                moveStart = moveEnd = -1;
                startPos = pagePosE;
            }

            endPos = pagePosT;
        } else {
            // The items will move forward.
            direction = -1;

            if (pageE > pageToAnimate) {
                // Move the items immediately.
                moveStart = empty;
                // Instantly move the last item in the current page.
                moveEnd = (pageToAnimate + 1) * mMaxItemsPerPage - 1;

                // Animations start with the second last item in the page
                startPos = mMaxItemsPerPage - 1;
            } else {
                moveStart = moveEnd = -1;
                startPos = pagePosE;
            }

            endPos = pagePosT;
        }

        // Instant moving views.
        while (moveStart != moveEnd) {
            int rankToMove = moveStart + direction;
            int p = rankToMove / mMaxItemsPerPage;
            int pagePos = rankToMove % mMaxItemsPerPage;
            int x = pagePos % mGridCountX;
            int y = pagePos / mGridCountX;

            final CellLayout page = getPageAt(p);
            final View v = page.getChildAt(x, y);
            if (v != null) {
                if (pageToAnimate != p) {
                    page.removeView(v);
                    addViewForRank(v, (ShortcutInfo) v.getTag(), moveStart);
                } else {
                    // Do a fake animation before removing it.
                    final int newRank = moveStart;
                    final float oldTranslateX = v.getTranslationX();

                    Runnable endAction = new Runnable() {

                        @Override
                        public void run() {
                            mPendingAnimations.remove(v);
                            v.setTranslationX(oldTranslateX);
                            ((CellLayout) v.getParent().getParent()).removeView(v);
                            addViewForRank(v, (ShortcutInfo) v.getTag(), newRank);
                        }
                    };
                    v.animate()
                        .translationXBy((direction > 0 ^ rtlLayout) ? -v.getWidth() : v.getWidth())
                        .setDuration(REORDER_ANIMATION_DURATION)
                        .setStartDelay(0)
                        .withEndAction(endAction);
                    mPendingAnimations.put(v, endAction);
                }
            }
            moveStart = rankToMove;
        }

        if ((endPos - startPos) * direction <= 0) {
            // No animation
            return;
        }

        CellLayout page = getPageAt(pageToAnimate);
        for (int i = startPos; i != endPos; i += direction) {
            int nextPos = i + direction;
            View v = page.getChildAt(nextPos % mGridCountX, nextPos / mGridCountX);
            if (v != null) {
                ((ItemInfo) v.getTag()).rank -= direction;
            }
            if (page.animateChildToPosition(v, i % mGridCountX, i / mGridCountX,
                    REORDER_ANIMATION_DURATION, delay, true, true)) {
                delay += delayAmount;
                delayAmount *= VIEW_REORDER_DELAY_FACTOR;
            }
        }
    }

    private static class ViewComparator implements Comparator<View> {
        private final Collator mCollator = Collator.getInstance();

        @Override
        public int compare(View lhs, View rhs) {
            return mCollator.compare( ((ShortcutInfo) lhs.getTag()).title.toString(),
                    ((ShortcutInfo) rhs.getTag()).title.toString());
        }
    }
}
