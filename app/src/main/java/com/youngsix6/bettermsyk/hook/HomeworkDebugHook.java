package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HomeworkDebugHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String TARGET_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.student.homework.detail.dtk.StudentDtkHwDetailActivity";
    private static final String HW_ANSWER_MANAGER =
            "com.zdsoft.newsquirrel.android.activity.student.homework.analysis.util.HwAnswerManager";
    private static final String BUTTON_TAG = "bettermsyk_one_click_correct";
    private static final int VOLUME_TRIGGER_PRESS_COUNT = 3;
    private static final long VOLUME_TRIGGER_WINDOW_MS = 2000L;
    private static final Map<Activity, Boolean> RESUME_LOGGED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Boolean> VISIBLE_LOGGED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, AnswerLookup> ANSWER_CACHE = new ConcurrentHashMap<>();
    private static final Map<Activity, VolumeTriggerState> VOLUME_TRIGGER_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private HomeworkDebugHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass = XposedHelpers.findClass(TARGET_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            try {
                                if (RESUME_LOGGED.put(activity, Boolean.TRUE) == null) {
                                    log(activity, "StudentDtkHwDetailActivity.onResume");
                                }
                                injectOneClickCorrectButton(activity, lpparam.classLoader);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " injectOneClickCorrectButton ERROR: "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                                log(activity, "inject button error: "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                            }
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onKeyDown",
                    int.class,
                    KeyEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int keyCode = (Integer) param.args[0];
                            KeyEvent event = (KeyEvent) param.args[1];
                            if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
                                    || event == null
                                    || event.getRepeatCount() != 0) {
                                return;
                            }

                            Activity activity = (Activity) param.thisObject;
                            if (!isVolumeTriggerEnabled(activity)
                                    || readHomeworkStatus(activity) != 0) {
                                return;
                            }

                            int pressCount = recordVolumeDownPress(activity);
                            log(activity, "volume trigger progress: " + pressCount
                                    + "/" + VOLUME_TRIGGER_PRESS_COUNT);
                            if (pressCount >= VOLUME_TRIGGER_PRESS_COUNT) {
                                resetVolumeTriggerState(activity);
                                log(activity, "one-click fill triggered by three volume downs");
                                toast(activity, "由音量键触发填充");
                                autoFillDtkObjectiveAnswers(
                                        activity, lpparam.classLoader);
                            }
                            // 避免触发回填的同时把系统音量连续降低。
                            param.setResult(true);
                        }
                    }
            );
            XposedBridge.log(TAG + " HomeworkDebugHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " HomeworkDebugHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void injectOneClickCorrectButton(Activity activity, ClassLoader classLoader) {
        Resources res = activity.getResources();
        String pkg = activity.getPackageName();

        int llId = res.getIdentifier("ll", "id", pkg);
        int tvTimeId = res.getIdentifier("tv_time", "id", pkg);
        int ivTimeId = res.getIdentifier("iv_time", "id", pkg);
        int dimX24 = res.getIdentifier("x24", "dimen", pkg);
        int dimX30 = res.getIdentifier("x30", "dimen", pkg);
        int dimX18 = res.getIdentifier("x18", "dimen", pkg);
        int dimY48 = res.getIdentifier("y48", "dimen", pkg);
        int colorWhite = res.getIdentifier("white", "color", pkg);

        View buttonView = activity.getWindow()
                .getDecorView()
                .findViewWithTag(BUTTON_TAG);
        boolean oneClickEnabled =
                ModuleSettingsHook.isOneClickCorrectEnabled(activity);
        if (!oneClickEnabled) {
            if (buttonView != null) {
                buttonView.setVisibility(View.GONE);
            }
            log(activity, "one-click setting off, hide button");
            return;
        }

        int hwStatus = readHomeworkStatus(activity);
        if (hwStatus != 0) {
            if (buttonView != null) {
                buttonView.setVisibility(View.GONE);
            }
            log(activity, "homework is not unsubmitted, hide one-click button: hwStatus="
                    + hwStatus);
            return;
        }

        LinearLayout container = null;
        View llView = activity.findViewById(llId);
        if (llView instanceof LinearLayout) {
            container = (LinearLayout) llView;
        }
        if (container == null) {
            View timeView = activity.findViewById(tvTimeId);
            if (timeView != null && timeView.getParent() instanceof LinearLayout) {
                container = (LinearLayout) timeView.getParent();
            }
        }
        if (container == null) {
            XposedBridge.log(TAG + " one click correct container not found");
            log(activity, "one-click button container not found");
            return;
        }

        if (buttonView == null) {
            TextView button = new TextView(activity);
            button.setTag(BUTTON_TAG);
            button.setText("一键正确");
            button.setSingleLine(true);
            button.setGravity(Gravity.CENTER);
            button.setTextColor(safeColor(res, colorWhite, Color.WHITE));
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    safeDimen(res, dimX24, 24));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#22C55E"));
            bg.setCornerRadius(safeDimen(res, dimY48, 48) / 2f);
            button.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    safeDimen(res, dimY48, 48)
            );
            lp.rightMargin = safeDimen(res, dimX18, 18);
            button.setPadding(
                    safeDimen(res, dimX30, 30),
                    0,
                    safeDimen(res, dimX30, 30),
                    0
            );
            button.setLayoutParams(lp);

            final ClassLoader finalClassLoader = classLoader;
            button.setOnClickListener(v -> {
                int currentStatus = readHomeworkStatus(activity);
                if (currentStatus != 0) {
                    v.setVisibility(View.GONE);
                    log(activity, "one-click blocked after homework state changed: hwStatus="
                            + currentStatus);
                    return;
                }
                autoFillDtkObjectiveAnswers(activity, finalClassLoader);
            });

            int insertIndex = container.getChildCount();
            View ivTime = activity.findViewById(ivTimeId);
            if (ivTime != null) {
                int index = container.indexOfChild(ivTime);
                if (index >= 0) {
                    insertIndex = index;
                }
            }
            container.addView(button, insertIndex);
            buttonView = button;
            log(activity, "one-click button injected");
        }

        buttonView.setVisibility(View.VISIBLE);
        if (VISIBLE_LOGGED.put(buttonView, Boolean.TRUE) == null) {
            log(activity, "one-click button visible");
        }
    }

    private static int readHomeworkStatus(Activity activity) {
        try {
            Object status = XposedHelpers.callMethod(activity, "getHwStatus");
            if (status instanceof Number) {
                return ((Number) status).intValue();
            }
        } catch (Throwable t) {
            log(activity, "read homework status error: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isVolumeTriggerEnabled(Context context) {
        return ModuleSettingsHook.isVolumeTriggerEnabled(context);
    }

    private static int recordVolumeDownPress(Activity activity) {
        long now = SystemClock.elapsedRealtime();
        synchronized (VOLUME_TRIGGER_STATES) {
            VolumeTriggerState state = VOLUME_TRIGGER_STATES.get(activity);
            if (state == null) {
                state = new VolumeTriggerState();
                VOLUME_TRIGGER_STATES.put(activity, state);
            }
            if (now - state.lastPressTime > VOLUME_TRIGGER_WINDOW_MS) {
                state.pressCount = 0;
            }
            state.lastPressTime = now;
            state.pressCount++;
            return state.pressCount;
        }
    }

    private static void resetVolumeTriggerState(Activity activity) {
        synchronized (VOLUME_TRIGGER_STATES) {
            VOLUME_TRIGGER_STATES.remove(activity);
        }
    }

    private static void autoFillDtkObjectiveAnswers(Activity activity, ClassLoader classLoader) {
        autoFillDtkObjectiveAnswers(activity, classLoader, null, true);
    }

    private static void autoFillDtkObjectiveAnswers(
            Activity activity,
            ClassLoader classLoader,
            AnswerLookup remoteAnswers,
            boolean allowRemoteFetch
    ) {
        try {
            log(activity, "one-click fill start");
            Object manager = resolveHwAnswerManager(classLoader);
            Object handlePickAnswer = XposedHelpers.callMethod(manager, "getHandlePickAnswer");
            if (handlePickAnswer == null) {
                log(activity, "handlePickAnswer is null");
                toast(activity, "答案缓存未就绪，请稍后再试");
                return;
            }

            List<?> optionList = (List<?>) XposedHelpers.callMethod(activity, "getOptionInfoList");
            if (optionList == null || optionList.isEmpty()) {
                log(activity, "optionList empty");
                toast(activity, "未找到题目数据");
                return;
            }

            FillStats stats = new FillStats();
            int filledCount = fillOptionList(
                    optionList, manager, handlePickAnswer, activity, stats, remoteAnswers);
            log(activity, "fill stats: objective=" + stats.objectiveCount
                    + ", empty=" + stats.emptyCount
                    + ", remoteHit=" + stats.remoteHitCount
                    + ", createdBucket=" + stats.createdBucketCount
                    + ", failedBucket=" + stats.failedBucketCount
                    + ", filled=" + filledCount);
            if (filledCount <= 0 && allowRemoteFetch && stats.emptyCount > 0) {
                AnswerLookup cached = getCachedAnswerLookup(activity);
                if (cached != null) {
                    log(activity, "use cached remote answers");
                    autoFillDtkObjectiveAnswers(activity, classLoader, cached, false);
                    return;
                }
                fetchRemoteAnswersAndFill(activity, classLoader);
                return;
            }

            if (filledCount <= 0) {
                toast(activity, "未找到可回填的客观题");
                return;
            }

            try {
                Object presenter = XposedHelpers.getObjectField(activity, "MvpPre");
                if (presenter != null) {
                    XposedHelpers.callMethod(presenter, "saveOptionAnswer", false);
                    log(activity, "saveOptionAnswer(false) called");
                }
            } catch (Throwable t) {
                log(activity, "saveOptionAnswer(false) compatibility warning: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            try {
                XposedHelpers.callMethod(activity, "updateTheDoView");
                log(activity, "updateTheDoView called");
            } catch (Throwable t) {
                log(activity, "updateTheDoView compatibility warning: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            refreshAnswerList(activity);
            toast(activity, "已回填 " + filledCount + " 题，不会自动提交");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " autoFillDtkObjectiveAnswers ERROR: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            log(activity, "auto fill error: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            toast(activity, "一键正确失败");
        }
    }

    private static Object resolveHwAnswerManager(ClassLoader classLoader) {
        Class<?> managerClass = XposedHelpers.findClass(HW_ANSWER_MANAGER, classLoader);
        try {
            return XposedHelpers.getStaticObjectField(managerClass, "INSTANCE");
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getStaticObjectField(managerClass, "Companion");
        } catch (Throwable ignored) {
        }
        throw new NoSuchFieldError(HW_ANSWER_MANAGER + "#INSTANCE/#Companion");
    }

    private static int fillOptionList(
            List<?> optionList,
            Object manager,
            Object handlePickAnswer,
            Activity activity,
            FillStats stats,
            AnswerLookup remoteAnswers
    ) {
        int count = 0;
        for (Object option : optionList) {
            if (option == null) {
                continue;
            }

            Integer questionType = (Integer) XposedHelpers.callMethod(option, "getQuestionType");
            if (questionType == null) {
                continue;
            }

            List<?> childList = null;
            try {
                childList = (List<?>) XposedHelpers.callMethod(option, "getOtherQuesList");
            } catch (Throwable ignored) {
            }
            if (childList != null && !childList.isEmpty()) {
                count += fillOptionList(
                        childList, manager, handlePickAnswer, activity, stats, remoteAnswers);
                continue;
            }

            if (!isObjectiveQuestion(questionType)) {
                continue;
            }
            stats.objectiveCount++;

            String answer = (String) XposedHelpers.callMethod(option, "getAnswer");
            String questionId = (String) XposedHelpers.callMethod(option, "getQuestionId");
            if (TextUtils.isEmpty(answer) && remoteAnswers != null) {
                answer = remoteAnswers.find(option);
                if (!TextUtils.isEmpty(answer)) {
                    stats.remoteHitCount++;
                }
            }
            if (TextUtils.isEmpty(answer) || TextUtils.isEmpty(questionId)) {
                stats.emptyCount++;
                continue;
            }

            if (!hasAnswerBucket(handlePickAnswer, questionId)) {
                if (!ensureAnswerBucket(manager, handlePickAnswer, option, questionId, answer)) {
                    stats.failedBucketCount++;
                    log(activity, "failed to create answer bucket: " + questionId);
                    continue;
                }
                stats.createdBucketCount++;
            }

            try {
                if (questionType == 4) {
                    applyBlankList(option, answer);
                }
                XposedHelpers.callMethod(option, "setStudentAnswer", answer);
                XposedHelpers.callMethod(manager, "dtkOptionChange", questionId, answer);
                count++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " fill question failed: " + questionId + " / "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return count;
    }

    private static void fetchRemoteAnswersAndFill(Activity activity, ClassLoader classLoader) {
        toast(activity, "缓存未命中，正在联网获取答案");
        new Thread(() -> {
            try {
                AnswerLookup lookup = fetchRemoteAnswerLookup(activity);
                String cacheKey = getAnswerCacheKey(activity);
                if (!TextUtils.isEmpty(cacheKey)) {
                    ANSWER_CACHE.put(cacheKey, lookup);
                }
                log(activity, "remote answer fetched: serial=" + lookup.bySerial.size()
                        + ", order=" + lookup.byOrder.size()
                        + ", question=" + lookup.byQuestion.size());
                activity.runOnUiThread(() -> autoFillDtkObjectiveAnswers(
                        activity, classLoader, lookup, false));
            } catch (Throwable t) {
                XposedBridge.log(TAG + " remote answer fetch failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                log(activity, "remote answer fetch failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                toast(activity, "获取答案失败");
            }
        }, "BetterMSYK-answer-fetch").start();
    }

    private static AnswerLookup fetchRemoteAnswerLookup(Activity activity) throws Exception {
        Object presenter = XposedHelpers.getObjectField(activity, "MvpPre");
        if (presenter == null) {
            throw new IllegalStateException("presenter is null");
        }
        int homeworkId = ((Number) XposedHelpers.callMethod(presenter, "getHomeworkId")).intValue();
        Object loginUser = XposedHelpers.callMethod(presenter, "getLoginUser");
        String unitId = String.valueOf(XposedHelpers.callMethod(loginUser, "getUnitId"));
        String url = "https://padapp.msyk.cn/ws/teacher/homeworkCard/getHomeworkCardInfo"
                + "?homeworkId=" + homeworkId
                + "&studentId="
                + "&modifyNum=0"
                + "&unitId=" + URLEncoder.encode(unitId, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream()
                : conn.getErrorStream();
        String body = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("http " + code);
        }
        return parseAnswerLookup(body);
    }

    private static AnswerLookup getCachedAnswerLookup(Activity activity) {
        try {
            String cacheKey = getAnswerCacheKey(activity);
            if (TextUtils.isEmpty(cacheKey)) {
                return null;
            }
            return ANSWER_CACHE.get(cacheKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getAnswerCacheKey(Activity activity) throws Exception {
        Object presenter = XposedHelpers.getObjectField(activity, "MvpPre");
        if (presenter == null) {
            return "";
        }
        int homeworkId = ((Number) XposedHelpers.callMethod(presenter, "getHomeworkId")).intValue();
        Object loginUser = XposedHelpers.callMethod(presenter, "getLoginUser");
        String unitId = String.valueOf(XposedHelpers.callMethod(loginUser, "getUnitId"));
        return homeworkId + "@" + unitId;
    }

    private static AnswerLookup parseAnswerLookup(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray list = root.optJSONArray("homeworkCardList");
        if (list == null) {
            throw new IllegalStateException("homeworkCardList missing");
        }
        AnswerLookup lookup = new AnswerLookup();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            int questionType = item.optInt("questionType", 0);
            if (!isObjectiveQuestion(questionType)) {
                continue;
            }
            String answer = item.optString("answer", "");
            if (questionType == 4 && TextUtils.isEmpty(answer)) {
                JSONArray blankList = item.optJSONArray("blankList");
                if (blankList != null && blankList.length() > 0) {
                    answer = blankList.toString();
                }
            }
            if (TextUtils.isEmpty(answer)) {
                continue;
            }
            lookup.put(item.optString("serialNumber", ""), item.optString("orderNum", ""),
                    item.optString("questionId", ""), answer);
        }
        return lookup;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private static void applyBlankList(Object option, String answer) {
        try {
            JSONArray array = new JSONArray(answer);
            ArrayList<String> blankList = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                blankList.add(array.optString(i, ""));
            }

            Object current = XposedHelpers.callMethod(option, "getBlankList");
            if (current instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> currentList = (List<Object>) current;
                currentList.clear();
                currentList.addAll(blankList);
            } else {
                XposedHelpers.callMethod(option, "setBlankList", blankList);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " applyBlankList failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void refreshAnswerList(Activity activity) {
        try {
            int recyclerId = activity.getResources().getIdentifier(
                    "rec_answer", "id", activity.getPackageName());
            View recycler = recyclerId == 0 ? null : activity.findViewById(recyclerId);
            Object adapter = null;
            try {
                adapter = XposedHelpers.callMethod(activity, "getAdapter");
            } catch (Throwable ignored) {
                if (recycler != null) {
                    adapter = XposedHelpers.callMethod(recycler, "getAdapter");
                }
            }
            if (adapter != null) {
                XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
            }
            if (recycler == null) {
                return;
            }
            recycler.requestLayout();
            recycler.invalidate();
            final Object finalAdapter = adapter;
            recycler.post(() -> {
                try {
                    Object currentAdapter = finalAdapter != null
                            ? finalAdapter
                            : XposedHelpers.callMethod(recycler, "getAdapter");
                    if (currentAdapter != null) {
                        XposedHelpers.callMethod(currentAdapter, "notifyDataSetChanged");
                    }
                    recycler.requestLayout();
                    recycler.invalidate();
                } catch (Throwable t) {
                    log(activity, "delayed answer refresh warning: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            });
            log(activity, "answer list refresh scheduled");
        } catch (Throwable t) {
            log(activity, "answer list refresh warning: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean ensureAnswerBucket(
            Object manager,
            Object handlePickAnswer,
            Object option,
            String questionId,
            String answer
    ) {
        try {
            if (hasAnswerBucket(handlePickAnswer, questionId)) {
                return false;
            }

            String resourceId = valueToString(XposedHelpers.callMethod(option, "getResourceId"), "0");
            double score = valueToDouble(XposedHelpers.callMethod(option, "getScore"));
            Object questionModel = XposedHelpers.callMethod(
                    manager,
                    "generateQuestionModel",
                    questionId,
                    resourceId,
                    score,
                    "1",
                    0
            );
            Object answerModel = XposedHelpers.callMethod(
                    manager,
                    "generateDtkServerObjAnswerModel",
                    "-1",
                    answer
            );
            ArrayList<Object> answerModels = new ArrayList<>();
            answerModels.add(answerModel);
            XposedHelpers.callMethod(handlePickAnswer, "restObjectAnswer", questionModel, answerModels);
            return hasAnswerBucket(handlePickAnswer, questionId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ensureAnswerBucket failed: " + questionId + " / "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasAnswerBucket(Object handlePickAnswer, String questionId) {
        try {
            Map<String, ?> answerMap = (Map<String, ?>) XposedHelpers.callMethod(
                    handlePickAnswer, "getQuestionIdMapAnswers");
            return answerMap != null && answerMap.get(questionId) != null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hasAnswerBucket failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean isObjectiveQuestion(int questionType) {
        return questionType == 1 || questionType == 2 || questionType == 4 || questionType == 5;
    }

    private static String valueToString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static double valueToDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Throwable ignored) {
            return 0D;
        }
    }

    private static void toast(Activity activity, String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    private static void log(Context context, String message) {
        ModuleLog.log(TAG + " " + message);
    }

    private static int safeDimen(Resources res, int resId, int fallbackPx) {
        try {
            return res.getDimensionPixelSize(resId);
        } catch (Throwable ignored) {
            return fallbackPx;
        }
    }

    private static int safeColor(Resources res, int resId, int fallback) {
        try {
            return res.getColor(resId, null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static final class FillStats {
        int objectiveCount;
        int emptyCount;
        int remoteHitCount;
        int createdBucketCount;
        int failedBucketCount;
    }

    private static final class VolumeTriggerState {
        int pressCount;
        long lastPressTime;
    }

    private static final class AnswerLookup {
        final Map<String, String> bySerial = new HashMap<>();
        final Map<String, String> byOrder = new HashMap<>();
        final Map<String, String> byQuestion = new HashMap<>();

        void put(String serialNumber, String orderNum, String questionId, String answer) {
            putIfValid(bySerial, serialNumber, answer);
            putIfValid(byOrder, orderNum, answer);
            putIfValid(byQuestion, questionId, answer);
        }

        String find(Object option) {
            String answer = findByMethod(bySerial, option, "getSerialNumber");
            if (!TextUtils.isEmpty(answer)) {
                return answer;
            }
            answer = findByMethod(byOrder, option, "getOrderNum");
            if (!TextUtils.isEmpty(answer)) {
                return answer;
            }
            return findByMethod(byQuestion, option, "getQuestionId");
        }

        private String findByMethod(Map<String, String> map, Object option, String methodName) {
            try {
                Object key = XposedHelpers.callMethod(option, methodName);
                return map.get(valueToString(key, ""));
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static void putIfValid(Map<String, String> map, String key, String answer) {
            if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(answer)) {
                map.put(key, answer);
            }
        }
    }
}
