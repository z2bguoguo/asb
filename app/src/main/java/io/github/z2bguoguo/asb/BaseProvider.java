package io.github.z2bguoguo.asb;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

abstract class BaseProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { return null; }
}
