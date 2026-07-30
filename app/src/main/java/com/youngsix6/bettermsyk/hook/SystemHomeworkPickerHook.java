package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 可选地将作业图片上传的内置图片选择器替换为系统选择器。
 */
public final class SystemHomeworkPickerHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String PICK_UPLOAD_CLASS =
            "com.zdsoft.newsquirrel.android.activity.student.homework"
                    + ".analysis.util.HwAnswerManager$PickResAndUpload";
    private static final String HOMEWORK_PACKAGE_MARKER =
            ".activity.student.homework.";
    private static final String INK_HOMEWORK_PACKAGE_MARKER =
            ".android.ink.student.";
    private static final String EXTRA_PICKER_RESULT = "picker_result";
    private static final String EXTRA_RESOURCE_PARENT_ID = "mResParId";
    private static final String EXTRA_SHOW_VIDEO = "is_show_video";
    private static final String CACHE_DIRECTORY =
            "bettermsyk_system_homework_picker";
    private static final long MAX_IMAGE_BYTES = 100L * 1024L * 1024L;
    private static final long STALE_FILE_AGE_MS =
            3L * 24L * 60L * 60L * 1000L;

    private static final Map<Object, PendingSelection> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SystemHomeworkPickerHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> pickerClass =
                    XposedHelpers.findClass(PICK_UPLOAD_CLASS, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    pickerClass, "addPic", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            interceptAddPic(param);
                        }
                    });
            XposedBridge.hookAllMethods(
                    pickerClass, "addPicAndVideo", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            interceptAddPicAndVideo(param);
                        }
                    });
            XposedBridge.hookAllMethods(
                    pickerClass, "onAddBack", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            adaptSystemPickerResult(param);
                        }
                    });
            XposedBridge.log(TAG + " SystemHomeworkPickerHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " SystemHomeworkPickerHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void interceptAddPic(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length != 3
                || !(param.args[0] instanceof Integer)) {
            return;
        }
        Object picker = param.thisObject;
        Activity activity = getActivity(picker);
        if (activity == null
                || !isHomeworkActivity(activity)
                || !ModuleSettingsHook
                .isSystemHomeworkPickerEnabled(activity)) {
            return;
        }

        int maxCount = (Integer) param.args[0];
        if (maxCount <= 0) {
            return;
        }
        boolean directStart = param.args[1] instanceof String
                && param.args[2] instanceof Boolean;
        boolean callbackStart = param.args[2] != null
                && !(param.args[2] instanceof Boolean)
                && (param.args[1] instanceof String
                || param.args[1] instanceof Boolean);
        if (!directStart && !callbackStart) {
            return;
        }

        String resourceParentId = param.args[1] instanceof String
                ? (String) param.args[1] : "";
        boolean onlyAdd = directStart && (Boolean) param.args[2];
        int requestCode = onlyAdd ? 1005 : 1003;
        Intent systemPicker = createSystemPickerIntent(maxCount);
        PendingSelection pending = new PendingSelection(
                activity, requestCode, maxCount, resourceParentId, onlyAdd);

        try {
            XposedHelpers.setIntField(picker, "picCount", maxCount);
            if (callbackStart) {
                Object startFunction = param.args[2];
                XposedHelpers.setBooleanField(picker, "isStartInFun", true);
                XposedHelpers.setObjectField(
                        picker, "startFun", startFunction);
                PENDING.put(picker, pending);
                XposedHelpers.callMethod(
                        startFunction, "invoke", systemPicker, requestCode);
            } else {
                PENDING.put(picker, pending);
                activity.startActivityForResult(systemPicker, requestCode);
            }
            param.setResult(null);
            XposedBridge.log(TAG
                    + " system homework picker launched: max=" + maxCount
                    + ", request=" + requestCode);
        } catch (Throwable t) {
            PENDING.remove(picker);
            XposedBridge.log(TAG + " system homework picker launch failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            showToast(activity,
                    "无法打开系统选择器，已改用内置选择器");
        }
    }

    private static void adaptSystemPickerResult(
            XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 3
                || !(param.args[0] instanceof Integer)
                || !(param.args[1] instanceof Integer)) {
            return;
        }
        Object picker = param.thisObject;
        PendingSelection pending = PENDING.get(picker);
        if (pending == null
                || pending.requestCode != (Integer) param.args[0]) {
            return;
        }

        int resultCode = (Integer) param.args[1];
        if (resultCode != Activity.RESULT_OK) {
            PENDING.remove(picker);
            XposedBridge.log(TAG + " system homework picker cancelled.");
            return;
        }

        Intent systemResult = param.args[2] instanceof Intent
                ? (Intent) param.args[2] : null;
        try {
            ArrayList<Uri> selectedUris = collectUris(systemResult);
            if (selectedUris.isEmpty()) {
                throw new IllegalArgumentException("系统选择器未返回图片");
            }
            boolean truncated = selectedUris.size() > pending.maxCount;
            if (truncated) {
                selectedUris = new ArrayList<>(
                        selectedUris.subList(0, pending.maxCount));
            }
            ArrayList<String> localPaths = copyToHostCache(
                    pending.activity, selectedUris);
            Intent compatibleResult = new Intent();
            compatibleResult.putStringArrayListExtra(
                    EXTRA_PICKER_RESULT, localPaths);
            compatibleResult.putExtra("isCameraReturn", false);
            compatibleResult.putExtra(
                    EXTRA_RESOURCE_PARENT_ID, pending.resourceParentId);
            compatibleResult.putExtra(EXTRA_SHOW_VIDEO, false);
            compatibleResult.putExtra("type", 1);
            compatibleResult.putExtra("isOnlyAdd", pending.onlyAdd);
            param.args[2] = compatibleResult;
            PENDING.remove(picker);
            if (truncated) {
                showToast(pending.activity,
                        "最多选择 " + pending.maxCount + " 张图片，已忽略多余项目");
            }
            XposedBridge.log(TAG
                    + " system homework picker adapted: count="
                    + localPaths.size());
        } catch (Throwable t) {
            PENDING.remove(picker);
            param.args[2] = new Intent();
            XposedBridge.log(TAG + " system homework picker result failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            showToast(pending.activity,
                    "读取所选图片失败：" + friendly(t));
        }
    }

    private static void interceptAddPicAndVideo(
            XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length != 4
                || !(param.args[0] instanceof Integer)
                || !(param.args[3] instanceof String)) {
            return;
        }
        Object picker = param.thisObject;
        Activity activity = getActivity(picker);
        if (activity == null
                || !isHomeworkActivity(activity)
                || !ModuleSettingsHook
                .isSystemHomeworkPickerEnabled(activity)) {
            return;
        }
        int maxCount = (Integer) param.args[0];
        if (maxCount <= 0) {
            return;
        }
        String resourceParentId = (String) param.args[3];
        Intent systemPicker = createSystemPickerIntent(maxCount);
        PendingSelection pending = new PendingSelection(
                activity, 1003, maxCount, resourceParentId, false);
        try {
            XposedHelpers.setIntField(picker, "picCount", maxCount);
            PENDING.put(picker, pending);
            activity.startActivityForResult(systemPicker, 1003);
            param.setResult(null);
            XposedBridge.log(TAG
                    + " combined homework media entry redirected "
                    + "to system image picker: max=" + maxCount);
        } catch (Throwable t) {
            PENDING.remove(picker);
            XposedBridge.log(TAG
                    + " combined system image picker launch failed: "
                    + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
            showToast(activity,
                    "无法打开系统选择器，已改用内置选择器");
        }
    }

    private static Intent createSystemPickerIntent(int maxCount) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, maxCount > 1);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private static ArrayList<Uri> collectUris(Intent data) {
        LinkedHashMap<String, Uri> unique = new LinkedHashMap<>();
        if (data == null) {
            return new ArrayList<>();
        }
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    unique.put(uri.toString(), uri);
                }
            }
        }
        Uri single = data.getData();
        if (single != null) {
            unique.put(single.toString(), single);
        }
        return new ArrayList<>(unique.values());
    }

    private static ArrayList<String> copyToHostCache(
            Activity activity, ArrayList<Uri> uris) throws Exception {
        File directory = new File(activity.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建图片缓存目录");
        }
        clearStaleFiles(directory);

        ArrayList<File> created = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                String suffix = chooseSuffix(
                        activity.getContentResolver(), uri);
                File target = File.createTempFile(
                        "homework_", suffix, directory);
                created.add(target);
                copyUri(activity.getContentResolver(), uri, target);
                paths.add(target.getAbsolutePath());
            }
            return paths;
        } catch (Exception t) {
            for (File file : created) {
                if (file.isFile()) {
                    file.delete();
                }
            }
            throw t;
        }
    }

    private static void copyUri(
            ContentResolver resolver, Uri uri, File target)
            throws Exception {
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IllegalArgumentException("无法读取图片");
            }
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMAGE_BYTES) {
                    throw new IllegalArgumentException(
                            "单张图片不能超过 100 MB");
                }
                output.write(buffer, 0, read);
            }
            output.flush();
            if (total == 0) {
                throw new IllegalArgumentException("所选图片内容为空");
            }
        }
    }

    private static String chooseSuffix(
            ContentResolver resolver, Uri uri) {
        String displayName = queryDisplayName(resolver, uri);
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0 && dot < displayName.length() - 1) {
            String extension = displayName.substring(dot + 1)
                    .toLowerCase(java.util.Locale.ROOT);
            if (extension.matches("[a-z0-9]{1,8}")) {
                return "." + extension;
            }
        }
        String extension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(resolver.getType(uri));
        return extension == null || extension.trim().isEmpty()
                ? ".jpg" : "." + extension;
    }

    private static String queryDisplayName(
            ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void clearStaleFiles(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - STALE_FILE_AGE_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private static Activity getActivity(Object picker) {
        try {
            Object context = XposedHelpers.getObjectField(picker, "context");
            return context instanceof Activity ? (Activity) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isHomeworkActivity(Activity activity) {
        String className = activity.getClass().getName();
        return className.contains(HOMEWORK_PACKAGE_MARKER)
                || className.contains(INK_HOMEWORK_PACKAGE_MARKER);
    }

    private static void showToast(Activity activity, String message) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    private static String friendly(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private static final class PendingSelection {
        final Activity activity;
        final int requestCode;
        final int maxCount;
        final String resourceParentId;
        final boolean onlyAdd;

        PendingSelection(
                Activity activity, int requestCode, int maxCount,
                String resourceParentId, boolean onlyAdd) {
            this.activity = activity;
            this.requestCode = requestCode;
            this.maxCount = maxCount;
            this.resourceParentId = resourceParentId == null
                    ? "" : resourceParentId;
            this.onlyAdd = onlyAdd;
        }
    }
}
