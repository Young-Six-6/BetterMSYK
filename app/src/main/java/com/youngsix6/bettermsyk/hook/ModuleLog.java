package com.youngsix6.bettermsyk.hook;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块主动维护的进程内环形日志，不依赖 LSPosed/LSPatch 日志读取权限。
 */
final class ModuleLog {

    private static final int MAX_LINES = 600;
    private static final int MAX_CHARACTERS = 180_000;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);
    private static int characterCount;

    private ModuleLog() {
    }

    static void log(String message) {
        record(message);
        try {
            XposedBridge.log(message);
        } catch (Throwable ignored) {
            // 内置日志仍可用，框架日志不可用时不影响目标功能。
        }
    }

    static synchronized void record(String message) {
        String safe = message == null ? "null" : message;
        if (safe.length() > 6000) {
            safe = safe.substring(0, 6000) + "…";
        }
        String line = TIME_FORMAT.format(new Date())
                + " [" + Thread.currentThread().getName() + "] "
                + safe;
        LINES.addLast(line);
        characterCount += line.length() + 1;
        while (LINES.size() > MAX_LINES
                || characterCount > MAX_CHARACTERS) {
            String removed = LINES.removeFirst();
            characterCount -= removed.length() + 1;
        }
    }

    static synchronized String snapshot() {
        if (LINES.isEmpty()) {
            return "暂无模块主动日志。\n"
                    + "日志仅记录当前目标应用进程，重启应用后会重新开始。";
        }
        StringBuilder result = new StringBuilder(characterCount + 128);
        result.append("BetterMSYK 模块日志\n")
                .append("仅包含模块主动记录，当前进程内最多保留 ")
                .append(MAX_LINES)
                .append(" 条。\n\n");
        for (String line : LINES) {
            result.append(line).append('\n');
        }
        return result.toString();
    }

    static synchronized void clear() {
        LINES.clear();
        characterCount = 0;
    }
}
