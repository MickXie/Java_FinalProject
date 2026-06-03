package com.nfctransfer.app.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.text.SimpleDateFormat;

public class FileUtils {

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif"
    ));

    // ThreadLocal: SimpleDateFormat is not thread-safe
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()));

    public static String formatFileSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String getFileName(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        String name = cursor.getString(idx);
                        if (name != null) return name;
                    }
                }
            } catch (Exception ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "未知檔案";
    }

    public static long getFileSize(Context context, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx);
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public static boolean isImageFile(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(ext);
    }

    public static String formatTimestamp(long timestamp) {
        return DATE_FORMAT.get().format(new Date(timestamp));
    }
}
