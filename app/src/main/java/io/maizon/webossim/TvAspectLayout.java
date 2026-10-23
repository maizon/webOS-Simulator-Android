package io.maizon.webossim;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Centered native ViewGroup that letterboxes its WebView child to 16:9.
 * No View scales, translations or overlays: Android hit testing remains aligned.
 */
public final class TvAspectLayout extends ViewGroup {
    public TvAspectLayout(Context context) { super(context); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        if (getChildCount() == 0) return;
        int[] size = TvDisplayMetrics.fit(width, height);
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(size[0], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(size[1], MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) return;
        View content = getChildAt(0);
        int x = (r - l - content.getMeasuredWidth()) / 2;
        int y = (b - t - content.getMeasuredHeight()) / 2;
        content.layout(x, y, x + content.getMeasuredWidth(), y + content.getMeasuredHeight());
    }
}
