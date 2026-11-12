package io.github.hsd221.phonebridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

/** Synthetic clipboard source in the foreign test APK; never part of the distributed app. */
public class DataControlsFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Synthetic clipboard fixture");
        setContentView(label);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) applyClipboard(getIntent());
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (hasWindowFocus()) applyClipboard(intent);
    }
    private void applyClipboard(Intent intent) {
        ClipboardManager manager = getSystemService(ClipboardManager.class);
        String action = intent.getStringExtra("clipAction");
        if ("text".equals(action)) manager.setPrimaryClip(ClipData.newPlainText(intent.getStringExtra("clipLabel"), intent.getStringExtra("clipText")));
        else if ("uri".equals(action)) manager.setPrimaryClip(ClipData.newRawUri("synthetic-uri", Uri.parse("content://io.github.hsd221.phonebridge.test.data/never-read")));
        else if ("clear".equals(action)) manager.clearPrimaryClip();
        intent.removeExtra("clipAction");
    }
}
