package io.github.z2bguoguo.asb;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class SharedFiles extends BaseProvider {
    static final Uri UPDATE_URI = Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".files/update.apk");

    @Override public String getType(Uri uri) {
        return UPDATE_URI.equals(uri) ? "application/vnd.android.package-archive" : null;
    }

    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
        if (!UPDATE_URI.equals(uri)) throw new IllegalArgumentException();
        File file = Updates.apk(getContext());
        if (columns == null) columns = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(values);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!UPDATE_URI.equals(uri) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(Updates.apk(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
