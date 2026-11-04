package io.github.hsd221.phonebridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A foreign package in the test APK. Java keeps its standalone process independent of target-APK Kotlin libraries. */
@SuppressWarnings("deprecation")
public class DesignControlsFixture extends Activity {
    private void updateStatus(TextView status, String value) {
        status.setText(value);
        try (java.io.FileOutputStream output = openFileOutput("design-fixture-state.txt", MODE_PRIVATE)) {
            output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("close", false)) { finish(); return; }
        TextView status = new TextView(this);
        updateStatus(status, "fixture ready");
        status.setContentDescription("fixture status");
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(32, 80, 32, 32);
        body.addView(status);
        TextView touch = new TextView(this);
        touch.setText("touch pending");
        touch.setContentDescription("fixture touch status");
        body.addView(touch);
        body.addView(new HiddenParentView(this), new LinearLayout.LayoutParams(-1, 160));
        Button button = new Button(this) {
            private long down;
            private long downWall;
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    down = event.getEventTime();
                    downWall = android.os.SystemClock.uptimeMillis();
                    touch.setText("touch down at " + (int) event.getRawX() + "," + (int) event.getRawY());
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    touch.setText("touch event=" + (event.getEventTime() - down) + "ms wall=" + (android.os.SystemClock.uptimeMillis() - downWall) + "ms");
                } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) touch.setText("touch cancelled");
                return super.onTouchEvent(event);
            }
        };
        button.setText("Coordinate control");
        button.setContentDescription("fixture coordinate control");
        button.setOnClickListener(view -> updateStatus(status, "coordinate clicked"));
        button.setOnLongClickListener(view -> { updateStatus(status, "coordinate long pressed"); return true; });
        body.addView(button);
        EditText first = new EditText(this);
        first.setId(0x00f00001);
        first.setContentDescription("fixture first input");
        first.setSingleLine();
        first.setImeOptions(EditorInfo.IME_ACTION_DONE);
        first.setOnEditorActionListener((view, action, event) -> { updateStatus(status, "editor submitted"); return true; });
        body.addView(first);
        EditText second = new EditText(this);
        second.setId(0x00f00002);
        second.setContentDescription("fixture second input");
        second.setSingleLine();
        first.setNextFocusForwardId(second.getId());
        second.setNextFocusForwardId(first.getId());
        body.addView(second);
        setContentView(body);
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("close", false)) finish();
    }

    /** Reproduces frameworks whose invisible grouping node has visible, actionable children. */
    private static class HiddenParentView extends View {
        private String label = "Visible virtual descendant";
        private final Paint paint = new Paint();
        HiddenParentView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            paint.setColor(0xff111111);
            paint.setTextSize(32);
        }
        @Override public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawText(label, 10, 70, paint);
        }
        private void changed(String value) {
            label = value;
            invalidate();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return new AccessibilityNodeProvider() {
                @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                    if (id == HOST_VIEW_ID) {
                        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(HiddenParentView.this);
                        HiddenParentView.this.onInitializeAccessibilityNodeInfo(info);
                        info.addChild(HiddenParentView.this, 1);
                        return info;
                    }
                    if (id != 1 && id != 2) return null;
                    int[] location = new int[2];
                    getLocationOnScreen(location);
                    AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
                    info.setPackageName(getContext().getPackageName());
                    info.setClassName(id == 1 ? "android.view.ViewGroup" : "android.widget.Button");
                    info.setSource(HiddenParentView.this, id);
                    info.setParent(HiddenParentView.this, id == 1 ? HOST_VIEW_ID : 1);
                    info.setBoundsInParent(new Rect(0, 0, getWidth(), getHeight()));
                    info.setBoundsInScreen(new Rect(location[0], location[1], location[0] + getWidth(), location[1] + getHeight()));
                    info.setEnabled(true);
                    info.setVisibleToUser(id == 2);
                    if (id == 1) {
                        info.setText("Hidden virtual container");
                        info.addChild(HiddenParentView.this, 2);
                    } else {
                        info.setText(label);
                        info.setClickable(true);
                        info.setLongClickable(true);
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                    }
                    return info;
                }
                @Override public boolean performAction(int id, int action, Bundle arguments) {
                    if (id != 2) return false;
                    if (action == AccessibilityNodeInfo.ACTION_CLICK) { changed("Virtual descendant clicked"); return true; }
                    if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) { changed("Virtual descendant long pressed"); return true; }
                    return false;
                }
            };
        }
    }
}
