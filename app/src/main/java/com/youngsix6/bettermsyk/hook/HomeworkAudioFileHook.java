package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 可选地用系统 MP3 文件选择器替代题目内置录音器。
 * 可解析时长和题目音频大小上限校验，再以完全相同的结果格式交回原应用。
 */
public final class HomeworkAudioFileHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String PICK_UPLOAD_CLASS =
            "com.zdsoft.newsquirrel.android.activity.student.homework"
                    + ".analysis.util.HwAnswerManager$PickResAndUpload";
    private static final String ANSWER_MANAGER_CLASS =
            "com.zdsoft.newsquirrel.android.activity.student.homework"
                    + ".analysis.util.HwAnswerManager";
    private static final String HOMEWORK_PACKAGE_MARKER =
            ".activity.student.homework.";
    private static final String INK_HOMEWORK_PACKAGE_MARKER =
            ".android.ink.student.";
    private static final String OBJECTIVE_FRAGMENT_CLASS =
            "com.zdsoft.newsquirrel.android.activity.student.homework"
                    + ".resource.HomeworkObjectiveFragment";
    private static final String DTK_DETAIL_ACTIVITY_CLASS =
            "com.zdsoft.newsquirrel.android.activity.student.homework"
                    + ".detail.dtk.StudentDtkHwDetailActivity";
    private static final String INK_DTK_DETAIL_ACTIVITY_CLASS =
            "com.zdsoft.newsquirrel.android.ink.student"
                    + ".StudentInkDtkHwDetailActivity";
    private static final int AUDIO_REQUEST_CODE = 1004;
    private static final int DEFAULT_MAX_AUDIO_MB = 100;
    private static final String CACHE_DIRECTORY =
            "bettermsyk_homework_audio";
    private static final long STALE_FILE_AGE_MS =
            3L * 24L * 60L * 60L * 1000L;
    private static final int MP3_SCAN_BYTES = 1024 * 1024;

    private static final int[] MPEG1_LAYER3_BITRATES = {
            0, 32, 40, 48, 56, 64, 80, 96,
            112, 128, 160, 192, 224, 256, 320, 0
    };
    private static final int[] MPEG2_LAYER3_BITRATES = {
            0, 8, 16, 24, 32, 40, 48, 56,
            64, 80, 96, 112, 128, 144, 160, 0
    };

    private static final Map<Object, PendingAudio> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private HomeworkAudioFileHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> pickerClass = XposedHelpers.findClass(
                    PICK_UPLOAD_CLASS, lpparam.classLoader);
            Class<?> answerManagerClass = XposedHelpers.findClass(
                    ANSWER_MANAGER_CLASS, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    pickerClass, "addAudio", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            interceptAddAudio(param, answerManagerClass);
                        }
                    });
            XposedBridge.hookAllMethods(
                    pickerClass, "onAddBack", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            adaptAudioResult(param);
                        }
                    });
            hookEmbeddedRecorderEntries(
                    lpparam, answerManagerClass);
            XposedBridge.log(TAG + " HomeworkAudioFileHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " HomeworkAudioFileHook init failed: "
                    + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
    }

    private static void hookEmbeddedRecorderEntries(
            XC_LoadPackage.LoadPackageParam lpparam,
            Class<?> answerManagerClass) {
        hookObjectiveRecorder(lpparam, answerManagerClass);
        hookDtkRecorder(
                lpparam, answerManagerClass,
                DTK_DETAIL_ACTIVITY_CLASS);
        hookDtkRecorder(
                lpparam, answerManagerClass,
                INK_DTK_DETAIL_ACTIVITY_CLASS);
    }

    private static void hookObjectiveRecorder(
            XC_LoadPackage.LoadPackageParam lpparam,
            Class<?> answerManagerClass) {
        try {
            Class<?> fragmentClass = XposedHelpers.findClass(
                    OBJECTIVE_FRAGMENT_CLASS, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    fragmentClass, "showRecordAudio",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            Activity activity =
                                    fragmentActivity(param.thisObject);
                            if (!isAudioReplacementEnabled(activity)) {
                                return;
                            }
                            try {
                                Object resourceModel =
                                        XposedHelpers.getObjectField(
                                                param.thisObject,
                                                "resourceModel");
                                Object resource = XposedHelpers.callMethod(
                                        resourceModel, "getResource");
                                Object value = XposedHelpers.callMethod(
                                        resource, "getResId");
                                if (launchAudioPickerThroughManager(
                                        answerManagerClass,
                                        String.valueOf(value))) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                logFailure(
                                        "redirect objective recorder", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure("objective recorder hook", t);
        }
    }

    private static void hookDtkRecorder(
            XC_LoadPackage.LoadPackageParam lpparam,
            Class<?> answerManagerClass,
            String activityClassName) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(
                    activityClassName, lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    activityClass, "showRecordAudio",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)
                                    || param.args == null
                                    || param.args.length < 1
                                    || !(param.args[0] instanceof Integer)) {
                                return;
                            }
                            Activity activity =
                                    (Activity) param.thisObject;
                            if (!isAudioReplacementEnabled(activity)) {
                                return;
                            }
                            try {
                                Object result = XposedHelpers.callMethod(
                                        activity, "getOptionInfoList");
                                if (!(result instanceof List)) {
                                    return;
                                }
                                int position = (Integer) param.args[0];
                                List<?> options = (List<?>) result;
                                if (position < 0
                                        || position >= options.size()) {
                                    return;
                                }
                                Object questionId =
                                        XposedHelpers.callMethod(
                                                options.get(position),
                                                "getQuestionId");
                                if (launchAudioPickerThroughManager(
                                        answerManagerClass,
                                        String.valueOf(questionId))) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                logFailure(
                                        "redirect DTK recorder "
                                                + activityClassName,
                                        t);
                            }
                        }
                    });
        } catch (Throwable t) {
            logFailure(
                    "DTK recorder hook " + activityClassName, t);
        }
    }

    private static boolean launchAudioPickerThroughManager(
            Class<?> answerManagerClass, String questionId) {
        if (questionId == null
                || questionId.trim().isEmpty()
                || "null".equalsIgnoreCase(questionId)) {
            return false;
        }
        Object companion = resolveAnswerManagerCompanion(
                answerManagerClass);
        Object picker = XposedHelpers.callMethod(
                companion, "getPickResAndUpload");
        if (picker == null) {
            return false;
        }
        XposedHelpers.callMethod(
                companion, "prepareAddAudio", questionId);
        XposedBridge.log(TAG
                + " embedded homework recorder redirected: question="
                + questionId);
        return true;
    }

    private static Object resolveAnswerManagerCompanion(
            Class<?> answerManagerClass) {
        try {
            return XposedHelpers.getStaticObjectField(
                    answerManagerClass, "INSTANCE");
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getStaticObjectField(
                    answerManagerClass, "Companion");
        } catch (Throwable ignored) {
        }
        throw new NoSuchFieldError(
                ANSWER_MANAGER_CLASS + "#INSTANCE/#Companion");
    }

    private static Activity fragmentActivity(Object fragment) {
        try {
            Object activity =
                    XposedHelpers.callMethod(fragment, "getActivity");
            return activity instanceof Activity
                    ? (Activity) activity : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAudioReplacementEnabled(
            Activity activity) {
        return activity != null
                && isHomeworkActivity(activity)
                && ModuleSettingsHook
                .isHomeworkAudioFileEnabled(activity);
    }

    private static void interceptAddAudio(
            XC_MethodHook.MethodHookParam param,
            Class<?> answerManagerClass) {
        if (param.args == null || param.args.length != 1
                || !(param.args[0] instanceof String)) {
            return;
        }
        Object picker = param.thisObject;
        Activity activity = getActivity(picker);
        if (activity == null
                || !isHomeworkActivity(activity)
                || !ModuleSettingsHook
                .isHomeworkAudioFileEnabled(activity)) {
            return;
        }

        String resourceParentId = (String) param.args[0];
        int maxAudioMb = readMaxAudioSize(answerManagerClass);
        PendingAudio pending = new PendingAudio(
                activity, resourceParentId, maxAudioMb);
        Intent intent = createAudioPickerIntent();
        try {
            PENDING.put(picker, pending);
            activity.startActivityForResult(
                    intent, AUDIO_REQUEST_CODE);
            param.setResult(null);
            XposedBridge.log(TAG
                    + " homework MP3 picker launched: max="
                    + maxAudioMb + " MB");
        } catch (Throwable t) {
            PENDING.remove(picker);
            XposedBridge.log(TAG
                    + " homework MP3 picker launch failed: "
                    + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
            showToast(activity,
                    "无法打开音频选择器，已改用内置录音器");
        }
    }

    private static void adaptAudioResult(
            XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length < 3
                || !(param.args[0] instanceof Integer)
                || !(param.args[1] instanceof Integer)
                || (Integer) param.args[0] != AUDIO_REQUEST_CODE) {
            return;
        }
        Object picker = param.thisObject;
        PendingAudio pending = PENDING.get(picker);
        if (pending == null) {
            return;
        }

        int resultCode = (Integer) param.args[1];
        if (resultCode != Activity.RESULT_OK) {
            PENDING.remove(picker);
            XposedBridge.log(TAG
                    + " homework MP3 picker cancelled.");
            return;
        }

        Intent systemResult = param.args[2] instanceof Intent
                ? (Intent) param.args[2] : null;
        Uri uri = systemResult == null
                ? null : systemResult.getData();
        try {
            if (uri == null) {
                throw new IllegalArgumentException(
                        "系统选择器未返回音频文件");
            }
            File mp3File = copyAndValidateMp3(
                    pending.activity, uri, pending.maxAudioMb);
            Intent compatibleResult = new Intent();
            compatibleResult.putExtra(
                    "filePath", mp3File.getAbsolutePath());
            compatibleResult.putExtra(
                    "mResParId", pending.resourceParentId);
            param.args[2] = compatibleResult;
            PENDING.remove(picker);
            XposedBridge.log(TAG
                    + " homework MP3 result adapted: bytes="
                    + mp3File.length());
        } catch (Throwable t) {
            PENDING.remove(picker);
            param.args[2] = new Intent();
            XposedBridge.log(TAG
                    + " homework MP3 validation failed: "
                    + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
            showToast(pending.activity,
                    "音频文件不可用：" + friendly(t));
        }
    }

    private static Intent createAudioPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/mpeg");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/mpeg", "audio/mp3", "audio/x-mpeg"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    private static File copyAndValidateMp3(
            Activity activity, Uri uri, int maxAudioMb)
            throws Exception {
        File directory = new File(
                activity.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException(
                    "无法创建音频缓存目录");
        }
        clearStaleFiles(directory);
        File target = File.createTempFile(
                "homework_audio_", ".mp3", directory);
        try {
            long maxBytes = Math.max(1L, maxAudioMb)
                    * 1024L * 1024L;
            copyUri(
                    activity.getContentResolver(),
                    uri, target, maxBytes);
            if (!hasValidMp3Frames(target)) {
                throw new IllegalArgumentException(
                        "不是有效的 MP3（MPEG Layer III）文件");
            }
            validatePlayableDuration(target);
            return target;
        } catch (Exception t) {
            target.delete();
            throw t;
        }
    }

    private static void copyUri(
            ContentResolver resolver, Uri uri,
            File target, long maxBytes) throws Exception {
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "无法读取所选音频");
            }
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException(
                            "文件超过题目允许的 "
                                    + (maxBytes / 1024L / 1024L)
                                    + " MB");
                }
                output.write(buffer, 0, read);
            }
            output.flush();
            if (total == 0) {
                throw new IllegalArgumentException(
                        "所选音频内容为空");
            }
        }
    }

    private static boolean hasValidMp3Frames(File file)
            throws Exception {
        try (RandomAccessFile input =
                     new RandomAccessFile(file, "r")) {
            long fileLength = input.length();
            if (fileLength < 8) {
                return false;
            }
            long scanStart = readId3End(input, fileLength);
            long scanEnd = Math.min(
                    fileLength - 4,
                    scanStart + MP3_SCAN_BYTES);
            for (long offset = scanStart;
                 offset <= scanEnd; offset++) {
                int frameLength = readMp3FrameLength(
                        input, offset, fileLength);
                if (frameLength <= 0) {
                    continue;
                }
                long nextFrame = offset + frameLength;
                if (readMp3FrameLength(
                        input, nextFrame, fileLength) > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static long readId3End(
            RandomAccessFile input, long fileLength)
            throws Exception {
        if (fileLength < 10) {
            return 0;
        }
        input.seek(0);
        if (input.readUnsignedByte() != 'I'
                || input.readUnsignedByte() != 'D'
                || input.readUnsignedByte() != '3') {
            return 0;
        }
        input.readUnsignedByte();
        input.readUnsignedByte();
        int flags = input.readUnsignedByte();
        int b0 = input.readUnsignedByte();
        int b1 = input.readUnsignedByte();
        int b2 = input.readUnsignedByte();
        int b3 = input.readUnsignedByte();
        if ((b0 | b1 | b2 | b3) > 0x7F) {
            return 0;
        }
        long tagSize = ((long) b0 << 21)
                | ((long) b1 << 14)
                | ((long) b2 << 7)
                | b3;
        long end = 10L + tagSize;
        if ((flags & 0x10) != 0) {
            end += 10L;
        }
        return Math.min(end, fileLength);
    }

    private static int readMp3FrameLength(
            RandomAccessFile input, long offset,
            long fileLength) throws Exception {
        if (offset < 0 || offset + 4 > fileLength) {
            return -1;
        }
        input.seek(offset);
        int header = input.readInt();
        if ((header & 0xFFE00000) != 0xFFE00000) {
            return -1;
        }
        int versionBits = (header >>> 19) & 0x3;
        int layerBits = (header >>> 17) & 0x3;
        int bitrateIndex = (header >>> 12) & 0xF;
        int sampleRateIndex = (header >>> 10) & 0x3;
        int padding = (header >>> 9) & 0x1;
        if (versionBits == 1
                || layerBits != 1
                || bitrateIndex == 0
                || bitrateIndex == 15
                || sampleRateIndex == 3) {
            return -1;
        }

        boolean mpeg1 = versionBits == 3;
        int bitrate = (mpeg1
                ? MPEG1_LAYER3_BITRATES
                : MPEG2_LAYER3_BITRATES)[bitrateIndex];
        int[] sampleRates;
        if (versionBits == 3) {
            sampleRates = new int[]{44100, 48000, 32000};
        } else if (versionBits == 2) {
            sampleRates = new int[]{22050, 24000, 16000};
        } else {
            sampleRates = new int[]{11025, 12000, 8000};
        }
        int sampleRate = sampleRates[sampleRateIndex];
        if (bitrate <= 0 || sampleRate <= 0) {
            return -1;
        }
        int coefficient = mpeg1 ? 144000 : 72000;
        int frameLength =
                coefficient * bitrate / sampleRate + padding;
        return frameLength >= 24 ? frameLength : -1;
    }

    private static void validatePlayableDuration(File file)
            throws Exception {
        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String durationText = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = durationText == null
                    ? 0 : Long.parseLong(durationText);
            if (duration <= 0) {
                throw new IllegalArgumentException(
                        "无法解析 MP3 时长");
            }
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int readMaxAudioSize(
            Class<?> answerManagerClass) {
        try {
            int value = XposedHelpers.getStaticIntField(
                    answerManagerClass, "maxAudioSize");
            return value > 0 ? value : DEFAULT_MAX_AUDIO_MB;
        } catch (Throwable t) {
            XposedBridge.log(TAG
                    + " maxAudioSize fallback: "
                    + t.getClass().getSimpleName());
            return DEFAULT_MAX_AUDIO_MB;
        }
    }

    private static void clearStaleFiles(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis()
                - STALE_FILE_AGE_MS;
        for (File file : files) {
            if (file.isFile()
                    && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private static Activity getActivity(Object picker) {
        try {
            Object context = XposedHelpers.getObjectField(
                    picker, "context");
            return context instanceof Activity
                    ? (Activity) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isHomeworkActivity(
            Activity activity) {
        String className = activity.getClass().getName();
        return className.contains(HOMEWORK_PACKAGE_MARKER)
                || className.contains(INK_HOMEWORK_PACKAGE_MARKER);
    }

    private static void showToast(
            Activity activity, String message) {
        activity.runOnUiThread(() -> Toast.makeText(
                activity, message, Toast.LENGTH_SHORT).show());
    }

    private static String friendly(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private static void logFailure(
            String operation, Throwable t) {
        ModuleLog.log(TAG + " " + operation + " failed: "
                + t.getClass().getSimpleName() + ": "
                + t.getMessage());
    }

    private static final class PendingAudio {
        final Activity activity;
        final String resourceParentId;
        final int maxAudioMb;

        PendingAudio(
                Activity activity, String resourceParentId,
                int maxAudioMb) {
            this.activity = activity;
            this.resourceParentId =
                    resourceParentId == null
                            ? "" : resourceParentId;
            this.maxAudioMb = maxAudioMb;
        }
    }
}
