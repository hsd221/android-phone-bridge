package io.github.hsd221.phonebridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicInteger;

/** Records attempted URI coercion without ever providing file contents. Test APK only. */
public class DataUriProvider extends ContentProvider {
    private static final AtomicInteger reads = new AtomicInteger();
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor result = new MatrixCursor(new String[] { "reads" });
        result.addRow(new Object[] { reads.get() });
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        reads.incrementAndGet();
        throw new FileNotFoundException("Synthetic provider must never be read by clipboard tools");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
