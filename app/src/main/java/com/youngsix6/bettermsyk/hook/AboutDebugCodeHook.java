package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 在调试模式下为“关于软件”页面增加临时编码生成入口。
 */
public final class AboutDebugCodeHook {

    private static final String TAG = "[BetterMSYK]";
    private static final String SP_FILE = "system_properties";
    private static final String KEY_DEBUG_MODE = "isDebugMode";
    private static final String TARGET_ACTIVITY =
            "com.zdsoft.newsquirrel.android.activity.teacher.AboutMsykActivity";
    private static final String BUTTON_TAG = "bettermsyk_generate_debug_codes";
    private static final int VALID_SECONDS = 3600;
    private static final int SEARCH_SECONDS = 300;
    private static final int[] MAPPING = {6, 7, 8, 9, 10, 1, 0, 3, 2, 5, 4};

    private AboutDebugCodeHook() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> activityClass =
                    XposedHelpers.findClass(TARGET_ACTIVITY, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    activityClass,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            if (!isDebugMode(activity)) {
                                return;
                            }
                            injectGenerateButton(activity);
                        }
                    }
            );
            XposedBridge.log(TAG + " AboutDebugCodeHook registered.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AboutDebugCodeHook init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void injectGenerateButton(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor.findViewWithTag(BUTTON_TAG) != null) {
                return;
            }

            int privacyId = activity.getResources().getIdentifier(
                    "ll_privacy", "id", activity.getPackageName());
            View privacyView = activity.findViewById(privacyId);
            if (!(privacyView instanceof LinearLayout)
                    || !(privacyView.getParent() instanceof LinearLayout)) {
                XposedBridge.log(TAG + " about privacy row unavailable");
                return;
            }

            LinearLayout privacyRow = (LinearLayout) privacyView;
            LinearLayout parent = (LinearLayout) privacyRow.getParent();
            LinearLayout codeRow = cloneActionRow(activity, privacyRow);
            if (codeRow == null) {
                XposedBridge.log(TAG + " unable to clone privacy row");
                return;
            }
            codeRow.setTag(BUTTON_TAG);
            codeRow.setOnClickListener(view -> generateAndCopy(activity));

            int privacyIndex = parent.indexOfChild(privacyRow);
            if (privacyIndex < 0) {
                XposedBridge.log(TAG + " privacy row index unavailable");
                return;
            }
            View privacyDivider = privacyIndex >= 0
                    && privacyIndex + 1 < parent.getChildCount()
                    ? parent.getChildAt(privacyIndex + 1)
                    : null;
            boolean hasDivider = isDividerView(privacyDivider);
            int insertIndex = privacyIndex + (hasDivider ? 2 : 1);
            parent.addView(codeRow, insertIndex);
            if (hasDivider) {
                View divider = cloneDivider(activity, privacyDivider);
                if (divider != null) {
                    parent.addView(divider, insertIndex + 1);
                }
            }
            XposedBridge.log(TAG + " debug code button injected");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " inject debug code button failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static LinearLayout cloneActionRow(
            Activity activity,
            LinearLayout source
    ) {
        TextView sourceLabel = null;
        ImageView sourceArrow = null;
        for (int index = 0; index < source.getChildCount(); index++) {
            View child = source.getChildAt(index);
            if (sourceLabel == null && child instanceof TextView) {
                sourceLabel = (TextView) child;
            } else if (sourceArrow == null && child instanceof ImageView) {
                sourceArrow = (ImageView) child;
            }
        }
        if (sourceLabel == null || sourceArrow == null) {
            return null;
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(source.getOrientation());
        row.setGravity(source.getGravity());
        row.setPadding(
                source.getPaddingLeft(),
                source.getPaddingTop(),
                source.getPaddingRight(),
                source.getPaddingBottom()
        );
        row.setBackground(cloneDrawable(activity, source.getBackground()));
        ViewGroup.LayoutParams sourceRowParams = source.getLayoutParams();
        row.setLayoutParams(sourceRowParams instanceof LinearLayout.LayoutParams
                ? new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) sourceRowParams)
                : new LinearLayout.LayoutParams(
                        sourceRowParams.width, sourceRowParams.height));

        TextView label = new TextView(activity);
        label.setText("生成调试编码");
        label.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                sourceLabel.getTextSize()
        );
        label.setTextColor(sourceLabel.getTextColors());
        label.setGravity(sourceLabel.getGravity());
        label.setTypeface(sourceLabel.getTypeface());
        label.setSingleLine(sourceLabel.isSingleLine());
        label.setPadding(
                sourceLabel.getPaddingLeft(),
                sourceLabel.getPaddingTop(),
                sourceLabel.getPaddingRight(),
                sourceLabel.getPaddingBottom()
        );
        ViewGroup.LayoutParams sourceLabelParams = sourceLabel.getLayoutParams();
        label.setLayoutParams(sourceLabelParams instanceof LinearLayout.LayoutParams
                ? new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) sourceLabelParams)
                : new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        ImageView arrow = new ImageView(activity);
        arrow.setBackground(cloneDrawable(activity, sourceArrow.getBackground()));
        arrow.setImageDrawable(cloneDrawable(activity, sourceArrow.getDrawable()));
        arrow.setScaleType(sourceArrow.getScaleType());
        arrow.setPadding(
                sourceArrow.getPaddingLeft(),
                sourceArrow.getPaddingTop(),
                sourceArrow.getPaddingRight(),
                sourceArrow.getPaddingBottom()
        );
        ViewGroup.LayoutParams sourceArrowParams = sourceArrow.getLayoutParams();
        arrow.setLayoutParams(sourceArrowParams instanceof LinearLayout.LayoutParams
                ? new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) sourceArrowParams)
                : new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(label);
        row.addView(arrow);
        return row;
    }

    private static boolean isDividerView(View view) {
        if (view == null || view instanceof ViewGroup) {
            return false;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params != null && params.height > 0;
    }

    private static View cloneDivider(Activity activity, View source) {
        ViewGroup.LayoutParams sourceParams = source.getLayoutParams();
        if (sourceParams == null) {
            return null;
        }
        View divider = new View(activity);
        divider.setBackground(cloneDrawable(activity, source.getBackground()));
        divider.setLayoutParams(sourceParams instanceof LinearLayout.LayoutParams
                ? new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) sourceParams)
                : new LinearLayout.LayoutParams(
                        sourceParams.width, sourceParams.height));
        return divider;
    }

    private static Drawable cloneDrawable(Activity activity, Drawable source) {
        if (source == null || source.getConstantState() == null) {
            return source;
        }
        return source.getConstantState().newDrawable(activity.getResources());
    }

    private static void generateAndCopy(Activity activity) {
        try {
            long now = System.currentTimeMillis() / 1000L;
            GeneratedCode code1 = generate(89, now);
            GeneratedCode code2 = generate(87, now);
            if (!isValid(code1, 89, now) || !isValid(code2, 87, now)) {
                throw new IllegalStateException("self-check failed");
            }

            String result = "code1：" + code1.code + "，code2：" + code2.code;
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                    Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                throw new IllegalStateException("clipboard unavailable");
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "BetterMSYK debug codes", result));
            Toast.makeText(activity, "调试编码已复制", Toast.LENGTH_SHORT).show();
            XposedBridge.log(TAG + " debug codes generated and copied");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " generate debug codes failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            Toast.makeText(activity, "生成调试编码失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isValid(
            GeneratedCode generated,
            int expectedFactor,
            long now
    ) {
        DecodedCode decoded = decode(generated.code);
        return decoded.factor == expectedFactor
                && decoded.timestamp == generated.timestamp
                && Math.abs(now - decoded.timestamp) <= VALID_SECONDS;
    }

    private static GeneratedCode generate(int factor, long now) {
        for (int distance = 0; distance <= SEARCH_SECONDS; distance++) {
            if (distance == 0) {
                String code = buildAt(now, factor);
                if (code != null) {
                    return new GeneratedCode(code, now);
                }
                continue;
            }

            long later = now + distance;
            String laterCode = buildAt(later, factor);
            if (laterCode != null) {
                return new GeneratedCode(laterCode, later);
            }

            long earlier = now - distance;
            String earlierCode = buildAt(earlier, factor);
            if (earlierCode != null) {
                return new GeneratedCode(earlierCode, earlier);
            }
        }
        throw new IllegalStateException("unable to generate factor=" + factor);
    }

    private static String buildAt(long timestamp, int factor) {
        String factorText = String.format(Locale.US, "%03d", factor);
        char c3 = factorText.charAt(0);
        char c2 = factorText.charAt(1);
        char c1 = factorText.charAt(2);

        for (int d3 = 0; d3 < 10; d3++) {
            List<Character> stage2 = toCharacters(Long.toString(timestamp));
            stage2.add(d3, c3);
            if (Character.digit(stage2.get(stage2.size() - 1), 10) != d3) {
                continue;
            }

            for (int d2 = 0; d2 < 10; d2++) {
                List<Character> stage1 = new ArrayList<>(stage2);
                stage1.add(d2, c2);
                if (Character.digit(stage1.get(stage1.size() - 2), 10) != d2) {
                    continue;
                }

                for (int d1 = 0; d1 < 10; d1++) {
                    List<Character> digits = new ArrayList<>(stage1);
                    digits.add(d1, c1);
                    if (Character.digit(digits.get(digits.size() - 3), 10) != d1) {
                        continue;
                    }

                    long decimal = Long.parseLong(join(digits));
                    String transformed = Long.toHexString(decimal)
                            .toUpperCase(Locale.US);
                    if (transformed.length() != MAPPING.length) {
                        continue;
                    }

                    StringBuilder code = new StringBuilder(MAPPING.length);
                    for (int target : MAPPING) {
                        code.append(transformed.charAt(target));
                    }
                    DecodedCode decoded = decode(code.toString());
                    if (decoded.factor == factor
                            && decoded.timestamp == timestamp) {
                        return code.toString();
                    }
                }
            }
        }
        return null;
    }

    private static DecodedCode decode(String code) {
        String source = code.toUpperCase(Locale.US);
        char[] transformed = source.toCharArray();
        for (int index = 0; index < source.length(); index++) {
            int target = index <= source.length() - 7
                    ? index + 6
                    : index - (index % 2 == 0 ? 6 : 4);
            transformed[target] = source.charAt(index);
        }

        long decimal = Long.parseLong(new String(transformed), 16);
        List<Character> digits = toCharacters(Long.toString(decimal));
        String keyText = "";
        for (int offset : new int[]{3, 2, 1}) {
            int keyIndex = Character.digit(
                    digits.get(digits.size() - offset), 10);
            keyText = digits.get(keyIndex) + keyText;
            digits.remove(keyIndex);
        }

        int key = Integer.parseInt(keyText);
        int factor = key % 89 == 0 ? 89 : key % 87 == 0 ? 87 : 0;
        long timestamp = Long.parseLong(join(digits));
        return new DecodedCode(factor, timestamp);
    }

    private static List<Character> toCharacters(String value) {
        List<Character> characters = new ArrayList<>(value.length());
        for (int index = 0; index < value.length(); index++) {
            characters.add(value.charAt(index));
        }
        return characters;
    }

    private static String join(List<Character> characters) {
        StringBuilder value = new StringBuilder(characters.size());
        for (char character : characters) {
            value.append(character);
        }
        return value.toString();
    }

    private static boolean isDebugMode(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE);
        return preferences.getBoolean(KEY_DEBUG_MODE, false);
    }

    private static final class GeneratedCode {
        final String code;
        final long timestamp;

        GeneratedCode(String code, long timestamp) {
            this.code = code;
            this.timestamp = timestamp;
        }
    }

    private static final class DecodedCode {
        final int factor;
        final long timestamp;

        DecodedCode(int factor, long timestamp) {
            this.factor = factor;
            this.timestamp = timestamp;
        }
    }
}
