package io.github.hsd221.phonebridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.nio.charset.StandardCharsets;

/** Read-only fixed test results across the fixture/target UID boundary. Never included in the main APK. */
public class DesignStateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[] { "state" });
        String state = "unavailable";
        try (java.io.FileInputStream input = getContext().openFileInput("design-fixture-state.txt")) {
            byte[] data = new byte[128];
            int length = input.read(data);
            if (length >= 0) state = new String(data, 0, length, StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) { }
        cursor.addRow(new Object[] { state });
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.phonebridge.test-state"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
