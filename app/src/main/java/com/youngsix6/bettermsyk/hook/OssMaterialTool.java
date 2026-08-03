package com.youngsix6.bettermsyk.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OSS_Plus Android 图形化实现。（原脚本参github）
 */
final class OssMaterialTool {

    private static final String TAG = "[BetterMSYK]";
    private static final String APP_CLASS =
            "com.zdsoft.newsquirrel.android.NewSquirrelApplication";
    private static final String URL_CONSTANTS_CLASS =
            "com.zdsoft.newsquirrel.android.common.UrlConstants";
    private static final String API_SECRET = "DxlE8wwbZt8Y2ULQfgGywAgZfJl82G9S";
    private static final String FALLBACK_ORIGIN = "https://padapp.msyk.cn";
    private static final String CDN_BASE = "https://msyk.wpstatic.cn";
    private static final String OSS_BUCKET = "msyk";
    private static final String OSS_ENDPOINT =
            "https://oss-cn-shanghai.aliyuncs.com";
    private static final int PICK_FILE_REQUEST = 0x6F53;
    private static final int MAX_LOCAL_FILE_BYTES = 50 * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 16 * 1024 * 1024;
    private static final int MODE_ALL = 0;
    private static final int MODE_STUDENT = 1;
    private static final int MODE_MATERIAL = 2;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Map<Activity, Controller> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OssMaterialTool() {
    }

    static void attach(Activity activity, LinearLayout container, ClassLoader classLoader) {
        Controller controller = new Controller(activity, container, classLoader);
        CONTROLLERS.put(activity, controller);
        controller.build();
    }

    static void onActivityResult(Activity activity, int requestCode,
                                 int resultCode, Intent data) {
        if (requestCode != PICK_FILE_REQUEST || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Controller controller = CONTROLLERS.get(activity);
        if (controller != null) {
            controller.acceptFile(data);
        }
    }

    static void detach(Activity activity) {
        CONTROLLERS.remove(activity);
    }

    private static final class Controller {
        private final Activity activity;
        private final LinearLayout host;
        private final ClassLoader classLoader;
        private final AtomicInteger previewGeneration = new AtomicInteger();
        private final List<ImageItem> allItems = new ArrayList<>();
        private final List<ImageItem> items = new ArrayList<>();

        private EditText homeworkIdInput;
        private RadioGroup modeGroup;
        private RadioButton allMode;
        private RadioButton studentMode;
        private CheckBox hideDirectoryFiles;
        private Button loadButton;
        private Spinner itemSpinner;
        private TextView itemDetails;
        private ImageView remotePreview;
        private Button openRemoteButton;
        private Button downloadButton;
        private Button chooseButton;
        private ImageView localPreview;
        private TextView localDetails;
        private Button openLocalButton;
        private Button replaceButton;
        private Button recheckButton;
        private ProgressBar progress;
        private TextView status;

        private ImageItem selectedItem;
        private byte[] selectedBytes;
        private String selectedMime;
        private String selectedName;
        private Uri selectedUri;
        private String lastUploadedDigest;
        private String loadedHomeworkName = "";
        private String loadedNote = "";

        Controller(Activity activity, LinearLayout host, ClassLoader classLoader) {
            this.activity = activity;
            this.host = host;
            this.classLoader = classLoader;
        }

        void build() {
            host.removeAllViews();
            host.setBackgroundColor(Color.parseColor("#F7F9F7"));

            LinearLayout landscapeRoot = new LinearLayout(activity);
            landscapeRoot.setOrientation(LinearLayout.HORIZONTAL);
            landscapeRoot.setPadding(dp(16), dp(14), dp(16), dp(16));
            host.addView(landscapeRoot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            ScrollView leftScroll = new ScrollView(activity);
            leftScroll.setFillViewport(true);
            leftScroll.setClipToPadding(false);
            LinearLayout leftContent = new LinearLayout(activity);
            leftContent.setOrientation(LinearLayout.VERTICAL);
            leftContent.setPadding(0, 0, dp(14), dp(24));
            leftScroll.addView(leftContent, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            landscapeRoot.addView(leftScroll, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.37f));

            View columnDivider = new View(activity);
            columnDivider.setBackgroundColor(Color.parseColor("#DCE5DE"));
            LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                    dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
            dividerLp.setMargins(dp(2), 0, dp(16), 0);
            landscapeRoot.addView(columnDivider, dividerLp);

            ScrollView rightScroll = new ScrollView(activity);
            rightScroll.setFillViewport(true);
            rightScroll.setClipToPadding(false);
            LinearLayout rightContent = new LinearLayout(activity);
            rightContent.setOrientation(LinearLayout.VERTICAL);
            rightContent.setPadding(0, 0, 0, dp(24));
            rightScroll.addView(rightContent, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            landscapeRoot.addView(rightScroll, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.63f));

            TextView heading = text("OSS ToolKit", 24,
                    Color.parseColor("#18201B"));
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            leftContent.addView(heading);

            TextView intro = text(
                    "使用当前登录账号读取作业文件，先删除目标对象，"
                            + "再使用新文件替换到原位置。"
                            + "操作不可逆请确认目标无误。",
                    13, Color.parseColor("#536158"));
            LinearLayout.LayoutParams introLp = matchWrapLp();
            introLp.topMargin = dp(6);
            leftContent.addView(intro, introLp);

            TextView userText = text("当前账号：正在读取…", 13,
                    Color.parseColor("#24633F"));
            userText.setBackground(roundedBackground(
                    Color.parseColor("#D8E8DC"), 10, 0, Color.TRANSPARENT));
            userText.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams userLp = wrapLp();
            userLp.topMargin = dp(12);
            leftContent.addView(userText, userLp);
            readCurrentUserLabel(userText);

            LinearLayout queryCard = card();
            LinearLayout.LayoutParams cardLp = matchWrapLp();
            cardLp.topMargin = dp(16);
            leftContent.addView(queryCard, cardLp);
            queryCard.addView(sectionTitle("1. 读取目标"));

            homeworkIdInput = new EditText(activity);
            homeworkIdInput.setHint("作业 ID");
            homeworkIdInput.setHintTextColor(Color.parseColor("#738078"));
            homeworkIdInput.setTextColor(Color.parseColor("#18201B"));
            homeworkIdInput.setSingleLine(true);
            homeworkIdInput.setTextSize(16);
            homeworkIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            homeworkIdInput.setPadding(dp(14), 0, dp(14), 0);
            homeworkIdInput.setBackground(roundedBackground(
                    Color.parseColor("#FFFFFF"), 12, 1,
                    Color.parseColor("#77857B")));
            LinearLayout.LayoutParams inputLp = matchWrapLp();
            inputLp.topMargin = dp(8);
            inputLp.height = dp(54);
            queryCard.addView(homeworkIdInput, inputLp);

            modeGroup = new RadioGroup(activity);
            modeGroup.setOrientation(LinearLayout.HORIZONTAL);
            allMode = new RadioButton(activity);
            allMode.setId(View.generateViewId());
            allMode.setText("全部");
            styleRadio(allMode);
            studentMode = new RadioButton(activity);
            studentMode.setId(View.generateViewId());
            studentMode.setText("学生答案");
            styleRadio(studentMode);
            RadioButton materialMode = new RadioButton(activity);
            materialMode.setId(View.generateViewId());
            materialMode.setText("作业材料 / 解析");
            styleRadio(materialMode);
            modeGroup.addView(allMode, new RadioGroup.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.72f));
            modeGroup.addView(studentMode, new RadioGroup.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            modeGroup.addView(materialMode, new RadioGroup.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f));
            modeGroup.check(allMode.getId());
            LinearLayout.LayoutParams modeLp = matchWrapLp();
            modeLp.topMargin = dp(6);
            queryCard.addView(modeGroup, modeLp);

            hideDirectoryFiles = new CheckBox(activity);
            hideDirectoryFiles.setText("隐藏目录下其他文件");
            hideDirectoryFiles.setTextSize(13);
            hideDirectoryFiles.setTextColor(Color.parseColor("#34483B"));
            hideDirectoryFiles.setChecked(true);
            hideDirectoryFiles.setButtonTintList(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{
                            Color.parseColor("#386A4D"),
                            Color.parseColor("#77857B")
                    }));
            LinearLayout.LayoutParams directoryToggleLp = matchWrapLp();
            directoryToggleLp.topMargin = dp(2);
            queryCard.addView(hideDirectoryFiles, directoryToggleLp);

            loadButton = filledButton("读取文件列表");
            LinearLayout.LayoutParams loadLp = matchWrapLp();
            loadLp.topMargin = dp(10);
            queryCard.addView(loadButton, loadLp);
            loadButton.setOnClickListener(v -> loadItems());

            TextView statusTitle = sectionTitle("运行状态");
            LinearLayout.LayoutParams statusTitleLp = matchWrapLp();
            statusTitleLp.topMargin = dp(18);
            leftContent.addView(statusTitle, statusTitleLp);

            LinearLayout statusSurface = new LinearLayout(activity);
            statusSurface.setOrientation(LinearLayout.HORIZONTAL);
            statusSurface.setGravity(Gravity.CENTER_VERTICAL);
            statusSurface.setPadding(dp(14), dp(12), dp(14), dp(12));
            statusSurface.setBackground(roundedBackground(
                    Color.parseColor("#E9F0EB"), 14, 0, Color.TRANSPARENT));
            LinearLayout.LayoutParams statusSurfaceLp = matchWrapLp();
            statusSurfaceLp.topMargin = dp(8);
            leftContent.addView(statusSurface, statusSurfaceLp);

            progress = new ProgressBar(activity);
            progress.setIndeterminate(true);
            progress.setIndeterminateTintList(ColorStateList.valueOf(
                    Color.parseColor("#386A4D")));
            progress.setVisibility(View.GONE);
            LinearLayout.LayoutParams progressLp =
                    new LinearLayout.LayoutParams(dp(28), dp(28));
            progressLp.rightMargin = dp(10);
            statusSurface.addView(progress, progressLp);

            status = text("就绪", 13, Color.parseColor("#34483B"));
            status.setTextIsSelectable(true);
            statusSurface.addView(status, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView compareHeading = text("文件对照", 20,
                    Color.parseColor("#18201B"));
            compareHeading.setTypeface(Typeface.DEFAULT_BOLD);
            rightContent.addView(compareHeading);

            TextView compareHint = text(
                    "左侧为当前远程对象，右侧为删除原对象后即将上传的新文件。",
                    13, Color.parseColor("#536158"));
            LinearLayout.LayoutParams compareHintLp = matchWrapLp();
            compareHintLp.topMargin = dp(4);
            rightContent.addView(compareHint, compareHintLp);

            LinearLayout previewRow = new LinearLayout(activity);
            previewRow.setOrientation(LinearLayout.HORIZONTAL);
            previewRow.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams previewRowLp = matchWrapLp();
            previewRowLp.topMargin = dp(12);
            rightContent.addView(previewRow, previewRowLp);

            LinearLayout targetCard = card();
            LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            targetLp.rightMargin = dp(8);
            previewRow.addView(targetCard, targetLp);
            targetCard.addView(sectionTitle("2. 当前 OSS 文件"));

            itemSpinner = new Spinner(activity);
            itemSpinner.setVisibility(View.GONE);
            itemSpinner.setPadding(dp(8), 0, dp(8), 0);
            itemSpinner.setBackground(roundedBackground(
                    Color.parseColor("#EEF3EF"), 10, 1,
                    Color.parseColor("#CBD5CD")));
            LinearLayout.LayoutParams spinnerLp = matchWrapLp();
            spinnerLp.topMargin = dp(8);
            spinnerLp.height = dp(50);
            targetCard.addView(itemSpinner, spinnerLp);
            itemSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (position >= 0 && position < items.size()) {
                        selectItem(items.get(position));
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            itemDetails = text("请先在左侧读取文件列表", 12,
                    Color.parseColor("#536158"));
            itemDetails.setTextIsSelectable(true);
            LinearLayout.LayoutParams detailsLp = matchWrapLp();
            detailsLp.topMargin = dp(8);
            targetCard.addView(itemDetails, detailsLp);

            remotePreview = previewView();
            LinearLayout.LayoutParams remotePreviewLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
            remotePreviewLp.topMargin = dp(10);
            targetCard.addView(remotePreview, remotePreviewLp);

            openRemoteButton = outlinedButton("用系统应用打开");
            openRemoteButton.setEnabled(false);
            LinearLayout.LayoutParams openLp = matchWrapLp();
            openLp.topMargin = dp(8);
            targetCard.addView(openRemoteButton, openLp);
            openRemoteButton.setOnClickListener(v -> openRemote());

            downloadButton = outlinedButton("下载文件");
            downloadButton.setEnabled(false);
            LinearLayout.LayoutParams downloadLp = matchWrapLp();
            downloadLp.topMargin = dp(8);
            targetCard.addView(downloadButton, downloadLp);
            downloadButton.setOnClickListener(v -> downloadRemote());

            LinearLayout localCard = card();
            LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            localLp.leftMargin = dp(8);
            previewRow.addView(localCard, localLp);
            localCard.addView(sectionTitle("3. 新的替换文件"));

            chooseButton = tonalButton("从设备选择文件");
            chooseButton.setEnabled(false);
            LinearLayout.LayoutParams chooseLp = matchWrapLp();
            chooseLp.topMargin = dp(8);
            localCard.addView(chooseButton, chooseLp);
            chooseButton.setOnClickListener(v -> launchPicker());

            localPreview = previewView();
            LinearLayout.LayoutParams localPreviewLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
            localPreviewLp.topMargin = dp(10);
            localCard.addView(localPreview, localPreviewLp);

            localDetails = text("尚未选择本地文件", 12,
                    Color.parseColor("#536158"));
            localDetails.setTextIsSelectable(true);
            LinearLayout.LayoutParams localDetailsLp = matchWrapLp();
            localDetailsLp.topMargin = dp(8);
            localCard.addView(localDetails, localDetailsLp);

            openLocalButton = outlinedButton("用系统应用预览所选文件");
            openLocalButton.setEnabled(false);
            LinearLayout.LayoutParams openLocalLp = matchWrapLp();
            openLocalLp.topMargin = dp(8);
            localCard.addView(openLocalButton, openLocalLp);
            openLocalButton.setOnClickListener(v -> openLocal());

            replaceButton = dangerButton("确认替换");
            replaceButton.setEnabled(false);
            LinearLayout.LayoutParams replaceLp = matchWrapLp();
            replaceLp.topMargin = dp(10);
            localCard.addView(replaceButton, replaceLp);
            replaceButton.setOnClickListener(v -> confirmReplace());

            recheckButton = tonalButton("重新检查公开 CDN");
            recheckButton.setVisibility(View.GONE);
            LinearLayout.LayoutParams recheckLp = matchWrapLp();
            recheckLp.topMargin = dp(8);
            localCard.addView(recheckButton, recheckLp);
            recheckButton.setOnClickListener(v -> recheckCdn());

            hideDirectoryFiles.setOnCheckedChangeListener(
                    (buttonView, checked) -> {
                        if (!allItems.isEmpty()) {
                            rebuildVisibleItems();
                        }
                    });
        }

        private void readCurrentUserLabel(TextView label) {
            EXECUTOR.execute(() -> {
                try {
                    Session session = currentSession(activity, classLoader);
                    ui(() -> label.setText("当前账号：" + session.realName
                            + "（" + session.userId + "）"));
                } catch (Throwable t) {
                    ui(() -> label.setText("当前账号：读取失败，请确认已登录"));
                }
            });
        }

        private void loadItems() {
            String homeworkId = homeworkIdInput.getText().toString().trim();
            if (homeworkId.isEmpty()) {
                homeworkIdInput.setError("请输入作业 ID");
                return;
            }
            if (!homeworkId.matches("\\d+")) {
                homeworkIdInput.setError("作业 ID 必须是数字");
                return;
            }
            int mode = selectedMode();
            setBusy(true, mode == MODE_STUDENT
                    ? "正在读取学生答案文件…"
                    : mode == MODE_MATERIAL
                    ? "正在读取并扫描材料目录…"
                    : "正在读取全部作业文件并扫描材料目录…");
            selectedItem = null;
            allItems.clear();
            items.clear();
            itemSpinner.setVisibility(View.GONE);
            itemDetails.setText("正在读取…");
            remotePreview.setImageDrawable(null);
            openRemoteButton.setEnabled(false);
            downloadButton.setEnabled(false);
            chooseButton.setEnabled(false);
            updateReplaceEnabled();
            recheckButton.setVisibility(View.GONE);
            lastUploadedDigest = null;

            EXECUTOR.execute(() -> {
                try {
                    Session session = currentSession(activity, classLoader);
                    FetchResult result = fetchItems(activity, classLoader,
                            session, homeworkId, mode);
                    ui(() -> {
                        allItems.clear();
                        allItems.addAll(result.items);
                        loadedHomeworkName = result.homeworkName;
                        loadedNote = result.note;
                        rebuildVisibleItems();
                    });
                } catch (Throwable t) {
                    logFailure("load OSS items", t);
                    ui(() -> {
                        itemDetails.setText("读取失败");
                        setBusy(false, "读取失败：" + friendly(t));
                    });
                }
            });
        }

        private void rebuildVisibleItems() {
            selectedItem = null;
            items.clear();
            boolean hideDirectory = hideDirectoryFiles.isChecked();
            int hiddenCount = 0;
            for (ImageItem item : allItems) {
                if (hideDirectory
                        && "目录下其他文件".equals(item.category)) {
                    hiddenCount++;
                } else {
                    items.add(item);
                }
            }

            itemSpinner.setAdapter(null);
            itemSpinner.setVisibility(View.GONE);
            remotePreview.setImageDrawable(null);
            remotePreview.clearColorFilter();
            openRemoteButton.setEnabled(false);
            downloadButton.setEnabled(false);
            chooseButton.setEnabled(false);
            lastUploadedDigest = null;
            recheckButton.setVisibility(View.GONE);
            updateReplaceEnabled();

            if (items.isEmpty()) {
                itemDetails.setText(allItems.isEmpty()
                        ? "该作业未找到可操作的 OSS 文件。"
                        : "目录下其他文件已隐藏，取消勾选后可查看。");
                setBusy(false, loadedHomeworkName
                        + (loadedNote.isEmpty() ? "" : "\n" + loadedNote));
                return;
            }

            List<String> labels = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                labels.add((i + 1) + ". " + items.get(i).label);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    activity, android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            itemSpinner.setAdapter(adapter);
            itemSpinner.setVisibility(View.VISIBLE);
            setBusy(false, loadedHomeworkName + "\n显示 "
                    + items.size() + " 个文件"
                    + (hiddenCount == 0
                    ? "" : "，已隐藏目录下其他文件 " + hiddenCount + " 个")
                    + (loadedNote.isEmpty() ? "" : "\n" + loadedNote));
            selectItem(items.get(0));
        }

        private void selectItem(ImageItem item) {
            if (selectedItem == item) {
                return;
            }
            selectedItem = item;
            lastUploadedDigest = null;
            recheckButton.setVisibility(View.GONE);
            itemDetails.setText("类型：" + item.category
                    + "\n格式：" + item.familyLabel()
                    + (item.extension.isEmpty() ? "" : "（." + item.extension + "）")
                    + "\nOSS Key：" + item.ossKey
                    + "\n公开地址：" + item.url);
            openRemoteButton.setEnabled(true);
            downloadButton.setEnabled(true);
            chooseButton.setEnabled(true);
            updateReplaceEnabled();
            loadRemotePreview(item);
        }

        private void loadRemotePreview(ImageItem item) {
            int generation = previewGeneration.incrementAndGet();
            remotePreview.setImageDrawable(null);
            setPreviewBackground(remotePreview, Color.parseColor("#EEF3EF"));
            if (!item.isImage()) {
                remotePreview.setImageResource(android.R.drawable.ic_menu_save);
                remotePreview.setColorFilter(Color.parseColor("#647168"));
                return;
            }
            remotePreview.clearColorFilter();
            EXECUTOR.execute(() -> {
                try {
                    HttpBytes response = httpGet(
                            cacheBustedUrl(item.url, "bettermsyk_preview"),
                            MAX_PREVIEW_BYTES);
                    Bitmap bitmap = decodePreview(response.bytes);
                    if (bitmap == null) {
                        throw new IllegalArgumentException("远程图片无法预览");
                    }
                    ui(() -> {
                        if (generation == previewGeneration.get()
                                && selectedItem == item) {
                            remotePreview.setImageBitmap(bitmap);
                        }
                    });
                } catch (Throwable t) {
                    ui(() -> {
                        if (generation == previewGeneration.get()
                                && selectedItem == item) {
                            remotePreview.setImageDrawable(null);
                            setPreviewBackground(
                                    remotePreview, Color.parseColor("#F3F0F0"));
                        }
                    });
                }
            });
        }

        private void openRemote() {
            ImageItem item = selectedItem;
            if (item == null) {
                return;
            }
            try {
                String separator = item.url.contains("?") ? "&" : "?";
                Uri uri = Uri.parse(item.url + separator
                        + "bettermsyk_refresh=" + System.currentTimeMillis());
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                activity.startActivity(intent);
            } catch (Throwable t) {
                status.setText("没有可打开该文件地址的应用：" + friendly(t));
            }
        }

        private void downloadRemote() {
            ImageItem item = selectedItem;
            if (item == null) {
                return;
            }
            try {
                String separator = item.url.contains("?") ? "&" : "?";
                Uri uri = Uri.parse(item.url + separator
                        + "bettermsyk_download=" + System.currentTimeMillis());
                DownloadManager manager = (DownloadManager) activity
                        .getSystemService(Activity.DOWNLOAD_SERVICE);
                if (manager == null) {
                    throw new IllegalStateException("系统下载服务不可用");
                }
                DownloadManager.Request request = new DownloadManager.Request(uri)
                        .setTitle(item.fileName())
                        .setDescription("BetterMSYK OSS ToolKit")
                        .setNotificationVisibility(
                                DownloadManager.Request
                                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                if (!item.contentType.isEmpty()) {
                    request.setMimeType(item.contentType);
                }
                long id = manager.enqueue(request);
                status.setText("已交给系统下载，任务 ID：" + id);
            } catch (Throwable t) {
                status.setText("下载失败：" + friendly(t));
            }
        }

        private void launchPicker() {
            if (selectedItem == null) {
                status.setText("请先选择要替换的 OSS 文件");
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, supportedMimeTypes());
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                activity.startActivityForResult(intent, PICK_FILE_REQUEST);
            } catch (Throwable t) {
                status.setText("无法打开系统文件选择器：" + friendly(t));
            }
        }

        void acceptFile(Intent data) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
                if ((data.getFlags()
                        & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    activity.getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Throwable ignored) {
                // 部分文件提供者不支持持久授权；当前页面的临时授权仍然有效。
            }
            ImageItem target = selectedItem;
            if (target == null) {
                status.setText("目标文件已失效，请重新选择");
                return;
            }
            setBusy(true, "正在校验本地文件…");
            chooseButton.setEnabled(false);
            EXECUTOR.execute(() -> {
                try {
                    ContentResolver resolver = activity.getContentResolver();
                    String name = displayName(resolver, uri);
                    String mime = resolver.getType(uri);
                    byte[] bytes;
                    try (InputStream in = resolver.openInputStream(uri)) {
                        if (in == null) {
                            throw new IllegalArgumentException("无法读取所选文件");
                        }
                        bytes = readFully(in, MAX_LOCAL_FILE_BYTES);
                    }
                    String finalMime = validateUpload(
                            bytes, name, target.ossKey, mime);
                    String extension = fileExtension(name);
                    Bitmap bitmap = isImageExtension(extension)
                            ? decodePreview(bytes) : null;
                    ui(() -> {
                        selectedBytes = bytes;
                        selectedMime = finalMime;
                        selectedName = name;
                        selectedUri = uri;
                        if (bitmap != null) {
                            localPreview.clearColorFilter();
                            setPreviewBackground(
                                    localPreview, Color.parseColor("#EEF3EF"));
                            localPreview.setImageBitmap(bitmap);
                        } else {
                            localPreview.setImageResource(
                                    android.R.drawable.ic_menu_save);
                            localPreview.setColorFilter(
                                    Color.parseColor("#647168"));
                            setPreviewBackground(
                                    localPreview, Color.parseColor("#EEF3EF"));
                        }
                        localDetails.setText("文件：" + name
                                + "\n格式：" + fileFamilyLabel(extension)
                                + "\n类型：" + finalMime
                                + "\n大小：" + humanSize(bytes.length)
                                + (!isImageExtension(extension)
                                ? "\n非图片文件请使用系统应用预览。"
                                : bitmap == null
                                ? "\n当前 Android 解码器无法预览，但格式校验已通过。"
                                : ""));
                        chooseButton.setEnabled(true);
                        openLocalButton.setEnabled(true);
                        setBusy(false, "本地文件校验通过");
                        updateReplaceEnabled();
                    });
                } catch (Throwable t) {
                    logFailure("read selected file", t);
                    ui(() -> {
                        selectedBytes = null;
                        selectedMime = null;
                        selectedName = null;
                        selectedUri = null;
                        localPreview.setImageDrawable(null);
                        localPreview.clearColorFilter();
                        localDetails.setText("文件读取失败：" + friendly(t));
                        chooseButton.setEnabled(true);
                        openLocalButton.setEnabled(false);
                        setBusy(false, "请选择受支持且不超过 50 MB 的文件");
                        updateReplaceEnabled();
                    });
                }
            });
        }

        private void openLocal() {
            Uri uri = selectedUri;
            if (uri == null) {
                return;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri,
                        selectedMime == null || selectedMime.isEmpty()
                                ? "*/*" : selectedMime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(intent);
            } catch (Throwable t) {
                status.setText("没有可预览所选文件的应用：" + friendly(t));
            }
        }

        private void confirmReplace() {
            ImageItem item = selectedItem;
            byte[] bytes = selectedBytes;
            if (item == null || bytes == null) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("确认删除并替换 OSS 文件")
                    .setMessage("目标：" + item.label
                            + "\nOSS Key：" + item.ossKey
                            + "\n新文件：" + selectedName
                            + "（" + humanSize(bytes.length) + "）"
                            + "\n\n此操作会先删除远程对象，再上传新文件；"
                            + "不会提交或修改作业答案记录。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认替换", (dialog, which) ->
                            replace(item, bytes, selectedName, selectedMime))
                    .show();
        }

        private void replace(
                ImageItem item, byte[] bytes, String fileName, String mime) {
            setBusy(true, "正在获取临时凭证，删除原对象后上传…");
            loadButton.setEnabled(false);
            chooseButton.setEnabled(false);
            replaceButton.setEnabled(false);
            itemSpinner.setEnabled(false);
            setModeEnabled(false);
            homeworkIdInput.setEnabled(false);
            recheckButton.setVisibility(View.GONE);
            EXECUTOR.execute(() -> {
                try {
                    ReplacementResult result = replaceObject(
                            activity, classLoader,
                            homeworkIdInput.getText().toString().trim(),
                            item, bytes, fileName, mime);
                    ui(() -> {
                        lastUploadedDigest = result.digest;
                        loadButton.setEnabled(true);
                        chooseButton.setEnabled(true);
                        itemSpinner.setEnabled(true);
                        setModeEnabled(true);
                        homeworkIdInput.setEnabled(true);
                        setBusy(false,
                                "删除并上传成功，OSS 源站校验一致（"
                                        + humanSize(result.size) + "）。\n"
                                        + cdnMessage(result.publicStatus));
                        recheckButton.setVisibility(View.VISIBLE);
                        updateReplaceEnabled();
                        loadRemotePreview(item);
                    });
                } catch (Throwable t) {
                    logFailure("replace OSS object", t);
                    ui(() -> {
                        loadButton.setEnabled(true);
                        chooseButton.setEnabled(true);
                        itemSpinner.setEnabled(true);
                        setModeEnabled(true);
                        homeworkIdInput.setEnabled(true);
                        setBusy(false, "替换失败：" + friendly(t));
                        updateReplaceEnabled();
                    });
                }
            });
        }

        private void recheckCdn() {
            ImageItem item = selectedItem;
            String digest = lastUploadedDigest;
            if (item == null || digest == null) {
                return;
            }
            setBusy(true, "正在检查公开 CDN…");
            recheckButton.setEnabled(false);
            EXECUTOR.execute(() -> {
                try {
                    PublicStatus result = checkPublic(item, digest);
                    ui(() -> {
                        recheckButton.setEnabled(true);
                        setBusy(false, cdnMessage(result));
                        if (result.matches) {
                            loadRemotePreview(item);
                        }
                    });
                } catch (Throwable t) {
                    ui(() -> {
                        recheckButton.setEnabled(true);
                        setBusy(false, "CDN 检查失败：" + friendly(t));
                    });
                }
            });
        }

        private void updateReplaceEnabled() {
            replaceButton.setEnabled(selectedItem != null
                    && selectedBytes != null
                    && selectedName != null
                    && compatibleFileTypes(
                    fileExtension(selectedItem.ossKey),
                    fileExtension(selectedName))
                    && progress.getVisibility() != View.VISIBLE);
        }

        private void setModeEnabled(boolean enabled) {
            modeGroup.setEnabled(enabled);
            for (int i = 0; i < modeGroup.getChildCount(); i++) {
                modeGroup.getChildAt(i).setEnabled(enabled);
            }
        }

        private int selectedMode() {
            int checked = modeGroup.getCheckedRadioButtonId();
            if (checked == studentMode.getId()) {
                return MODE_STUDENT;
            }
            if (checked == allMode.getId()) {
                return MODE_ALL;
            }
            return MODE_MATERIAL;
        }

        private void setBusy(boolean busy, String message) {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            status.setText(message);
            if (!busy) {
                loadButton.setEnabled(true);
            } else {
                loadButton.setEnabled(false);
            }
            updateReplaceEnabled();
        }

        private void ui(Runnable action) {
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    action.run();
                }
            });
        }

        private LinearLayout card() {
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(16);
            layout.setPadding(padding, padding, padding, padding);
            layout.setBackground(roundedBackground(
                    Color.WHITE, 16, 1, Color.parseColor("#DCE5DE")));
            layout.setElevation(dp(1));
            return layout;
        }

        private TextView sectionTitle(String value) {
            TextView view = text(value, 16, Color.parseColor("#2B3A31"));
            view.setTypeface(Typeface.DEFAULT_BOLD);
            return view;
        }

        private TextView text(String value, int sp, int color) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(color);
            view.setLineSpacing(0, 1.12f);
            return view;
        }

        private Button filledButton(String value) {
            return styledButton(value,
                    Color.parseColor("#386A4D"), Color.WHITE,
                    Color.TRANSPARENT);
        }

        private Button tonalButton(String value) {
            return styledButton(value,
                    Color.parseColor("#D8E8DC"),
                    Color.parseColor("#1D5136"), Color.TRANSPARENT);
        }

        private Button outlinedButton(String value) {
            return styledButton(value, Color.WHITE,
                    Color.parseColor("#2A6241"),
                    Color.parseColor("#718078"));
        }

        private Button dangerButton(String value) {
            return styledButton(value,
                    Color.parseColor("#BA1A1A"), Color.WHITE,
                    Color.TRANSPARENT);
        }

        private Button styledButton(String value, int fillColor,
                                    int textColor, int strokeColor) {
            Button button = new Button(activity);
            button.setText(value);
            button.setAllCaps(false);
            button.setTextSize(14);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setMinHeight(dp(48));
            button.setPadding(dp(18), 0, dp(18), 0);

            int[][] textStates = new int[][]{
                    new int[]{-android.R.attr.state_enabled},
                    new int[]{}
            };
            button.setTextColor(new ColorStateList(textStates, new int[]{
                    Color.parseColor("#8A918C"), textColor
            }));

            StateListDrawable surfaces = new StateListDrawable();
            surfaces.addState(new int[]{-android.R.attr.state_enabled},
                    roundedBackground(Color.parseColor("#E1E4E1"),
                            24, 0, Color.TRANSPARENT));
            surfaces.addState(new int[]{},
                    roundedBackground(fillColor, 24,
                            strokeColor == Color.TRANSPARENT ? 0 : 1, strokeColor));
            GradientDrawable mask = roundedBackground(
                    Color.WHITE, 24, 0, Color.TRANSPARENT);
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#334E413C")),
                    surfaces, mask));
            return button;
        }

        private void styleRadio(RadioButton radioButton) {
            radioButton.setTextSize(13);
            radioButton.setTextColor(Color.parseColor("#34483B"));
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            };
            radioButton.setButtonTintList(new ColorStateList(states, new int[]{
                    Color.parseColor("#386A4D"),
                    Color.parseColor("#77857B")
            }));
        }

        private ImageView previewView() {
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            image.setAdjustViewBounds(true);
            image.setPadding(dp(6), dp(6), dp(6), dp(6));
            image.setBackground(roundedBackground(
                    Color.parseColor("#EEF3EF"), 14, 1,
                    Color.parseColor("#DCE5DE")));
            image.setClipToOutline(true);
            return image;
        }

        private void setPreviewBackground(ImageView image, int color) {
            image.setBackground(roundedBackground(
                    color, 14, 1, Color.parseColor("#DCE5DE")));
        }

        private GradientDrawable roundedBackground(
                int color, int radiusDp, int strokeDp, int strokeColor) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(color);
            background.setCornerRadius(dp(radiusDp));
            if (strokeDp > 0) {
                background.setStroke(dp(strokeDp), strokeColor);
            }
            return background;
        }

        private LinearLayout.LayoutParams wrapLp() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private LinearLayout.LayoutParams matchWrapLp() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources()
                    .getDisplayMetrics().density);
        }
    }

    private static Session currentSession(
            Context context, ClassLoader classLoader) {
        Class<?> appClass = CompatReflect.findClass(APP_CLASS, classLoader);
        Object loginUser;
        try {
            loginUser = CompatReflect.callStaticMethodExact(
                    appClass, "getLoginUser",
                    new Class<?>[]{Context.class}, context);
        } catch (Throwable ignored) {
            loginUser = CompatReflect.callStaticMethod(
                    appClass, "getLoginUser");
        }
        if (loginUser == null) {
            throw new IllegalStateException("当前没有登录用户");
        }
        String userId = readLoginUserText(
                loginUser, "getLoginUserId",
                "loginUserId", "userId", "id");
        String unitId = readLoginUserText(
                loginUser, "getUnitId", "unitId");
        String realName = readLoginUserText(
                loginUser, "getRealName", "realName", "name");
        String sign = valueOf(
                CompatReflect.getStaticField(appClass, "sign")).trim();
        List<String> missing = new ArrayList<>();
        if (userId.isEmpty()) {
            missing.add("userId");
        }
        if (unitId.isEmpty()) {
            missing.add("unitId");
        }
        if (!missing.isEmpty()) {
            String fields = String.join("、", missing);
            ModuleLog.record(TAG + " OSS session missing " + fields);
            throw new IllegalStateException("当前登录态缺少 " + fields);
        }
        if (sign.isEmpty()) {
            ModuleLog.record(TAG
                    + " OSS session uses empty sign for ID login compatibility");
        }
        return new Session(userId, unitId,
                realName.isEmpty() ? userId : realName, sign);
    }

    private static String readLoginUserText(
            Object loginUser, String methodName, String... fieldNames) {
        try {
            Object value = CompatReflect.callMethod(loginUser, methodName);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (Throwable ignored) {
        }
        for (String fieldName : fieldNames) {
            try {
                Object value = CompatReflect.getField(loginUser, fieldName);
                if (value != null) {
                    String text = String.valueOf(value).trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static FetchResult fetchItems(Activity activity, ClassLoader classLoader,
                                          Session session, String homeworkId,
                                          int mode) throws Exception {
        Map<String, String> homeworkFields = new LinkedHashMap<>();
        homeworkFields.put("homeworkId", homeworkId);
        homeworkFields.put("modifyNum", "0");
        homeworkFields.put("userId", session.userId);
        homeworkFields.put("unitId", session.unitId);
        JSONObject homework = postSigned(classLoader,
                "/ws/common/homework/homeworkStatus",
                homeworkFields, session.sign);

        JSONObject card = new JSONObject();
        String note = "";
        try {
            Map<String, String> cardFields = new LinkedHashMap<>();
            cardFields.put("homeworkId", homeworkId);
            cardFields.put("studentId", session.userId);
            cardFields.put("modifyNum", "0");
            cardFields.put("unitId", session.unitId);
            card = postSigned(classLoader,
                    "/ws/teacher/homeworkCard/getHomeworkCardInfo",
                    cardFields, session.sign);
        } catch (Throwable t) {
            note = "答题卡详情接口不可用，已使用作业状态接口继续枚举。";
        }

        List<ImageItem> found = new ArrayList<>();
        if (mode != MODE_MATERIAL) {
            List<ImageItem> studentItems = collectStudentImages(homework, card);
            found.addAll(studentItems);
            if (studentItems.isEmpty()) {
                found.addAll(fetchStudentFallback(classLoader, session,
                        homeworkId, homework));
            }
        }
        if (mode != MODE_STUDENT) {
            found.addAll(collectMaterialImages(homework, card));
            List<String> prefixes = materialPrefixes(homework, card);
            if (!prefixes.isEmpty()) {
                try {
                    found.addAll(listMaterialDirectoryImages(
                            activity, classLoader, prefixes));
                } catch (Throwable t) {
                    note = appendNote(note,
                            "材料目录扫描失败，仍可操作接口直接返回的文件：" + friendly(t));
                }
            }
        }
        String homeworkName = findString(homework, "homeworkName", "");
        if (homeworkName.isEmpty()) {
            homeworkName = findString(homework, "title", "");
        }
        if (homeworkName.isEmpty()) {
            homeworkName = findString(card, "homeworkName", "");
        }
        if (homeworkName.isEmpty()) {
            homeworkName = "作业 " + homeworkId;
        }
        return new FetchResult(homeworkName, deduplicate(found), note);
    }

    private static List<ImageItem> collectStudentImages(
            JSONObject homework, JSONObject card) {
        List<ImageItem> items = new ArrayList<>();
        JSONArray resources = findArray(homework, "resourceList");
        Map<String, QuestionInfo> questions = questionMap(resources);

        JSONArray direct = findArray(homework, "studentHomeworkAnswers");
        for (int i = 0; i < direct.length(); i++) {
            JSONObject entry = direct.optJSONObject(i);
            if (entry != null) {
                addItem(items, "学生答案",
                        questionLabel("学生答案", entry, questions),
                        first(entry, "pictureUrl", "url", "resourceUrl", "picUrl",
                                "audioUrl", "voiceUrl", "videoUrl", "fileUrl"));
            }
        }

        JSONArray answerList = findArray(homework, "studentAnswerList");
        for (int i = 0; i < answerList.length(); i++) {
            JSONObject entry = answerList.optJSONObject(i);
            if (entry != null) {
                String bitId = first(entry, "bitmapId", "bitId");
                addItem(items, "学生答案",
                        questionLabel("学生文件", entry, questions)
                                + " · bit=" + (bitId.isEmpty() ? "-1" : bitId),
                        first(entry, "pictureUrl", "url", "resourceUrl", "picUrl",
                                "audioUrl", "voiceUrl", "videoUrl", "fileUrl"));
            }
        }

        for (String listKey : new String[]{
                "studentAudioList", "studentVoiceList",
                "studentVideoList", "studentFileList"}) {
            JSONArray fileList = findArray(homework, listKey);
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject entry = fileList.optJSONObject(i);
                if (entry != null) {
                    addItem(items, "学生答案",
                            questionLabel("学生文件", entry, questions),
                            first(entry, "url", "resourceUrl", "audioUrl",
                                    "voiceUrl", "videoUrl", "fileUrl"));
                }
            }
        }

        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (resource == null) {
                continue;
            }
            for (String uploadKey : new String[]{
                    "upLoadPicList", "upLoadAudioList", "upLoadVoiceList",
                    "upLoadVideoList", "upLoadFileList"}) {
                JSONArray uploads = resource.optJSONArray(uploadKey);
                if (uploads == null) {
                    continue;
                }
                for (int j = 0; j < uploads.length(); j++) {
                    JSONObject upload = uploads.optJSONObject(j);
                    if (upload != null) {
                        addItem(items, "学生答案",
                                "学生上传 · 题"
                                        + resource.optString("orderNum", "?"),
                                first(upload, "url", "pictureUrl",
                                        "resourceUrl", "picUrl", "audioUrl",
                                        "voiceUrl", "videoUrl", "fileUrl"));
                    }
                }
            }
        }

        JSONArray cardList = findArray(card, "homeworkCardList");
        for (int i = 0; i < cardList.length(); i++) {
            JSONObject entry = cardList.optJSONObject(i);
            if (entry != null) {
                String order = first(entry, "orderNum", "serialNumber");
                addItem(items, "学生答案",
                        "答题卡文件 · 题" + (order.isEmpty() ? "?" : order),
                        first(entry, "pictureUrl", "url", "audioUrl",
                                "voiceUrl", "videoUrl", "fileUrl"));
            }
        }
        return deduplicate(items);
    }

    private static List<ImageItem> fetchStudentFallback(
            ClassLoader classLoader, Session session, String homeworkId,
            JSONObject homework) {
        List<ImageItem> items = new ArrayList<>();
        JSONArray resources = findArray(homework, "resourceList");
        String modifyNum = findString(homework, "modifyNum", "0");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (resource == null) {
                continue;
            }
            String resourceId = first(resource, "resId", "resourceId");
            if (resourceId.isEmpty()) {
                continue;
            }
            try {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("homeworkId", homeworkId);
                fields.put("resourceId", resourceId);
                fields.put("studentId", session.userId);
                fields.put("unitId", session.unitId);
                fields.put("modifyNum", modifyNum);
                JSONObject answer = postSigned(classLoader,
                        "/ws/student/homework/studentHomework/getHomeworkAnswer",
                        fields, session.sign);
                List<JSONObject> candidates = new ArrayList<>();
                for (String key : new String[]{
                        "studentHomeworkAnswers", "studentAnswerList", "answerList"}) {
                    JSONArray array = findArray(answer, key);
                    for (int j = 0; j < array.length(); j++) {
                        JSONObject candidate = array.optJSONObject(j);
                        if (candidate != null) {
                            candidates.add(candidate);
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    candidates.add(answer);
                }
                for (JSONObject candidate : candidates) {
                    String raw = first(candidate, "pictureUrl", "url",
                            "resourceUrl", "picUrl", "audioUrl", "voiceUrl",
                            "videoUrl", "fileUrl");
                    boolean cameFromGenericAnswer = raw.isEmpty();
                    if (cameFromGenericAnswer) {
                        raw = candidate.optString("answer", "");
                    }
                    for (String one : raw.split(",")) {
                        if (cameFromGenericAnswer && !looksLikeStoredPath(one)) {
                            continue;
                        }
                        addItem(items, "学生答案",
                                "逐题答案 · 题" + resource.optString("orderNum", "?"),
                                one);
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：单题兜底失败不阻塞其他题目。
            }
        }
        return deduplicate(items);
    }

    private static List<ImageItem> collectMaterialImages(
            JSONObject homework, JSONObject card) {
        List<ImageItem> items = new ArrayList<>();
        JSONArray materials = findArray(card, "materialRelas");
        for (int i = 0; i < materials.length(); i++) {
            JSONObject entry = materials.optJSONObject(i);
            if (entry != null) {
                addItem(items, "作业材料",
                        "材料 · " + findString(entry, "title", "未命名"),
                        first(entry, "resourceUrl", "sourceFileUrl",
                                "fileUrl", "url"));
            }
        }
        JSONArray analyses = findArray(card, "analysistList");
        for (int i = 0; i < analyses.length(); i++) {
            JSONObject entry = analyses.optJSONObject(i);
            if (entry != null) {
                addItem(items, "作业答案",
                        "答案/解析 · " + findString(entry, "title", "未命名"),
                        first(entry, "resourceUrl", "sourceFileUrl",
                                "fileUrl", "url"));
            }
        }
        JSONArray resources = findArray(homework, "resourceList");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject entry = resources.optJSONObject(i);
            if (entry != null) {
                String title = first(entry, "resTitle", "title");
                addItem(items, "作业材料",
                        "题目资源 · 题" + entry.optString("orderNum", "?")
                                + " · " + (title.isEmpty() ? "未命名" : title),
                        first(entry, "resourceUrl", "url", "pictureUrl",
                                "sourceFileUrl", "fileUrl", "audioUrl",
                                "videoUrl"));
            }
        }
        return deduplicate(items);
    }

    private static List<String> materialPrefixes(
            JSONObject homework, JSONObject card) {
        List<String> rawUrls = new ArrayList<>();
        collectFirstField(rawUrls, findArray(card, "materialRelas"),
                "resourceUrl", "sourceFileUrl", "fileUrl", "url");
        collectFirstField(rawUrls, findArray(card, "analysistList"),
                "resourceUrl", "sourceFileUrl", "fileUrl", "url");
        JSONArray resources = findArray(homework, "resourceList");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject item = resources.optJSONObject(i);
            if (item != null) {
                rawUrls.add(first(item, "resourceUrl", "url", "pictureUrl",
                        "sourceFileUrl", "fileUrl", "audioUrl", "videoUrl"));
            }
        }
        Map<String, Boolean> unique = new TreeMap<>();
        for (String raw : rawUrls) {
            String url = normalizeUrl(raw);
            String key = ossKey(url);
            int slash = key.lastIndexOf('/');
            if (slash < 0) {
                continue;
            }
            String directory = key.substring(0, slash + 1);
            unique.put(directory, true);
            int cut = directory.indexOf("/cut/");
            if (cut >= 0) {
                unique.put(directory.substring(0, cut + 1), true);
            } else if (key.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                unique.put(directory + "cut/", true);
            }
        }
        return new ArrayList<>(unique.keySet());
    }

    private static List<ImageItem> listMaterialDirectoryImages(
            Activity activity, ClassLoader classLoader,
            List<String> prefixes) throws Exception {
        JSONObject sts = fetchSts(classLoader, false);
        Object client = makeOssClient(activity, classLoader, sts);
        Class<?> requestClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.model.ListObjectsRequest", classLoader);
        List<ImageItem> result = new ArrayList<>();
        for (String prefix : prefixes) {
            Object request = CompatReflect.newInstance(requestClass);
            CompatReflect.callMethod(request, "setBucketName", OSS_BUCKET);
            CompatReflect.callMethod(request, "setPrefix", prefix);
            CompatReflect.callMethod(request, "setMaxKeys", Integer.valueOf(1000));
            Object response = CompatReflect.callMethod(client, "listObjects", request);
            int status = ((Number) CompatReflect.callMethod(
                    response, "getStatusCode")).intValue();
            if (status != 200) {
                continue;
            }
            Object summaries = CompatReflect.callMethod(response, "getObjectSummaries");
            if (!(summaries instanceof List<?>)) {
                continue;
            }
            for (Object summary : (List<?>) summaries) {
                String key = valueOf(CompatReflect.callMethod(summary, "getKey"));
                if (!isSupportedFile(key)) {
                    continue;
                }
                String name = key.substring(key.lastIndexOf('/') + 1);
                result.add(new ImageItem("目录下其他文件",
                        "目录扫描 · " + name, CDN_BASE + "/" + key, key));
            }
        }
        return deduplicate(result);
    }

    private static ReplacementResult replaceObject(
            Activity activity, ClassLoader classLoader, String homeworkId,
            ImageItem requestedItem, byte[] bytes,
            String fileName, String declaredMime) throws Exception {
        Session session = currentSession(activity, classLoader);
        String requestedKey = safeObjectKey(requestedItem.ossKey);
        int mode = "学生答案".equals(requestedItem.category)
                ? MODE_STUDENT : MODE_MATERIAL;
        FetchResult current = fetchItems(
                activity, classLoader, session, homeworkId, mode);
        ImageItem item = null;
        for (ImageItem candidate : current.items) {
            if (candidate.ossKey.equals(requestedKey)) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            throw new IllegalStateException(
                    "目标文件已不在当前作业中，请刷新后重试");
        }
        String mime = validateUpload(
                bytes, fileName, item.ossKey, declaredMime);

        JSONObject sts = fetchSts(classLoader, mode == MODE_STUDENT);
        Object client = makeOssClient(activity, classLoader, sts);

        Class<?> deleteClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.model.DeleteObjectRequest",
                classLoader);
        Object deleteRequest = CompatReflect.newInstance(
                deleteClass, OSS_BUCKET, item.ossKey);
        Object deleteResult;
        try {
            deleteResult = CompatReflect.callMethod(
                    client, "deleteObject", deleteRequest);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "删除原对象失败，未执行上传：" + friendly(t));
        }
        int deleteStatus = ((Number) CompatReflect.callMethod(
                deleteResult, "getStatusCode")).intValue();
        if (deleteStatus != 200 && deleteStatus != 204) {
            throw new IllegalStateException(
                    "OSS 删除返回 HTTP " + deleteStatus
                            + requestIdSuffix(deleteResult));
        }

        Class<?> metadataClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.model.ObjectMetadata", classLoader);
        Object metadata = CompatReflect.newInstance(metadataClass);
        CompatReflect.callMethod(metadata, "setContentType",
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        CompatReflect.callMethod(metadata, "setCacheControl",
                "no-cache, no-store, must-revalidate");
        CompatReflect.callMethod(metadata, "setExpirationTime", new Date(0));

        Class<?> putClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.model.PutObjectRequest", classLoader);
        Object putRequest = CompatReflect.newInstance(
                putClass, OSS_BUCKET, item.ossKey, bytes, metadata);

        Object putResult;
        try {
            putResult = CompatReflect.callMethod(client, "putObject", putRequest);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "原对象已删除，但上传失败：" + friendly(t));
        }
        int putStatus = ((Number) CompatReflect.callMethod(
                putResult, "getStatusCode")).intValue();
        if (putStatus != 200) {
            throw new IllegalStateException(
                    "原对象已删除，但上传返回 HTTP " + putStatus
                            + requestIdSuffix(putResult));
        }

        String localDigest = sha256(bytes);
        Class<?> getClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.model.GetObjectRequest", classLoader);
        Object getRequest = CompatReflect.newInstance(getClass, OSS_BUCKET, item.ossKey);
        Object getResult = CompatReflect.callMethod(client, "getObject", getRequest);
        int getStatus = ((Number) CompatReflect.callMethod(
                getResult, "getStatusCode")).intValue();
        if (getStatus != 200) {
            throw new IllegalStateException("上传后源站读取返回 HTTP " + getStatus);
        }
        byte[] remoteBytes;
        try (InputStream in = (InputStream) CompatReflect.callMethod(
                getResult, "getObjectContent")) {
            remoteBytes = readFully(in,
                    Math.max(MAX_LOCAL_FILE_BYTES, bytes.length + 1024 * 1024));
        }
        if (!localDigest.equals(sha256(remoteBytes))) {
            throw new IllegalStateException("上传后 SHA-256 校验不一致");
        }

        PublicStatus publicStatus;
        try {
            publicStatus = checkPublic(item, localDigest);
        } catch (Throwable t) {
            publicStatus = PublicStatus.unavailable(friendly(t));
        }
        return new ReplacementResult(remoteBytes.length, localDigest, publicStatus);
    }

    private static String requestIdSuffix(Object result) {
        try {
            String requestId = valueOf(
                    CompatReflect.callMethod(result, "getRequestId"));
            return requestId.isEmpty() ? "" : "，requestId=" + requestId;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static JSONObject fetchSts(
            ClassLoader classLoader, boolean signedMode) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        if (signedMode) {
            String salt = String.valueOf(System.currentTimeMillis());
            fields.put("salt", salt);
            fields.put("key", md5(salt + API_SECRET));
        } else {
            fields.put("retry", "0");
        }
        JSONObject response = postForm(serverOrigin(classLoader)
                + "/ws/common/uploadController/getParams", fields);
        JSONObject credentials = findObjectWithFields(response,
                "AccessKeyId", "AccessKeySecret", "SecurityToken");
        if (credentials == null) {
            throw new IllegalStateException("STS 响应缺少临时凭证字段");
        }
        return credentials;
    }

    private static Object makeOssClient(
            Activity activity, ClassLoader classLoader, JSONObject sts) {
        Class<?> providerClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider",
                classLoader);
        Object provider = CompatReflect.newInstance(providerClass,
                sts.optString("AccessKeyId"),
                sts.optString("AccessKeySecret"),
                sts.optString("SecurityToken"));

        Class<?> configClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.ClientConfiguration", classLoader);
        Object config = CompatReflect.newInstance(configClass);
        CompatReflect.callMethod(config, "setConnectionTimeout", 30000);
        CompatReflect.callMethod(config, "setSocketTimeout", 60000);
        CompatReflect.callMethod(config, "setMaxErrorRetry", 2);
        CompatReflect.callMethod(config, "setHttpDnsEnable", false);

        Class<?> clientClass = CompatReflect.findClass(
                "com.alibaba.sdk.android.oss.OSSClient", classLoader);
        return CompatReflect.newInstance(clientClass,
                activity.getApplicationContext(), OSS_ENDPOINT, provider, config);
    }

    private static JSONObject postSigned(
            ClassLoader classLoader, String path,
            Map<String, String> input, String sign) throws Exception {
        TreeMap<String, String> sorted = new TreeMap<>(input);
        StringBuilder values = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String key = entry.getKey();
            if (!"sign".equals(key) && !"key".equals(key) && !"salt".equals(key)) {
                values.append(entry.getValue() == null ? "" : entry.getValue());
            }
        }
        String salt = String.valueOf(System.currentTimeMillis());
        Map<String, String> payload = new LinkedHashMap<>(input);
        payload.put("salt", salt);
        payload.put("sign", sign);
        payload.put("key", md5(values + salt + sign + API_SECRET));
        JSONObject result = postForm(serverOrigin(classLoader) + path, payload);
        String code = String.valueOf(result.opt("code"));
        if (!"10000".equals(code)) {
            String message = first(result, "message", "msg");
            throw new IllegalStateException(path + "：code=" + code + "，"
                    + (message.isEmpty() ? "接口返回失败" : message));
        }
        return result;
    }

    private static String serverOrigin(ClassLoader classLoader) {
        try {
            Class<?> constants = CompatReflect.findClass(URL_CONSTANTS_CLASS, classLoader);
            String value = valueOf(CompatReflect.getStaticField(
                    constants, "SERVER_HOMEWORK_URL"));
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.endsWith("/ws")) {
                value = value.substring(0, value.length() - 3);
            }
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_ORIGIN;
    }

    private static JSONObject postForm(String url, Map<String, String> fields)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", "okhttp/3.12.1");
        connection.setRequestProperty("Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] body = formBody(fields).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] response;
            if (stream == null) {
                response = new byte[0];
            } else {
                try (InputStream input = stream) {
                    response = readFully(input, 4 * 1024 * 1024);
                }
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("网络请求失败：HTTP " + status);
            }
            return new JSONObject(new String(response, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static String formBody(Map<String, String> fields) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            result.append('=');
            result.append(URLEncoder.encode(
                    entry.getValue() == null ? "" : entry.getValue(), "UTF-8"));
        }
        return result.toString();
    }

    private static HttpBytes httpGet(String url, int limit) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "okhttp/3.12.1");
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = readFully(in, limit);
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Age", connection.getHeaderField("Age"));
            headers.put("X-Swift-CacheTime",
                    connection.getHeaderField("X-Swift-CacheTime"));
            headers.put("X-Cache", connection.getHeaderField("X-Cache"));
            return new HttpBytes(bytes, headers);
        } finally {
            connection.disconnect();
        }
    }

    private static PublicStatus checkPublic(
            ImageItem item, String expectedDigest) throws Exception {
        HttpBytes response = httpGet(
                cacheBustedUrl(item.url, "oss_toolkit"),
                MAX_LOCAL_FILE_BYTES);
        return new PublicStatus(
                expectedDigest.equals(sha256(response.bytes)),
                response.bytes.length,
                parseInteger(response.headers.get("Age")),
                parseInteger(response.headers.get("X-Swift-CacheTime")),
                valueOf(response.headers.get("X-Cache")),
                "");
    }

    private static String cdnMessage(PublicStatus status) {
        if (!status.error.isEmpty()) {
            return "公开 CDN 暂时无法检查：" + status.error;
        }
        String details = "大小 " + humanSize(status.size);
        if (status.age != null) {
            details += "，Age=" + status.age;
        }
        if (status.cacheTime != null) {
            details += "，CacheTime=" + status.cacheTime;
        }
        if (!status.matches && status.age != null && status.cacheTime != null) {
            details += "，预计剩余 "
                    + Math.max(status.cacheTime - status.age, 0) + " 秒";
        }
        if (!status.cacheResult.isEmpty()) {
            details += "，" + status.cacheResult;
        }
        return status.matches
                ? "公开 CDN 已返回新文件（" + details + "）"
                : "OSS 新对象已上传，但公开 CDN 仍返回旧缓存（" + details
                + "）。可稍后点击“重新检查公开 CDN”。";
    }

    private static Map<String, QuestionInfo> questionMap(JSONArray resources) {
        Map<String, QuestionInfo> result = new HashMap<>();
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (resource == null) {
                continue;
            }
            String id = first(resource, "resId", "resourceId");
            if (!id.isEmpty()) {
                result.put(id, new QuestionInfo(
                        resource.optString("orderNum", "?"),
                        resource.optInt("questionType", 0)));
            }
        }
        return result;
    }

    private static String questionLabel(
            String prefix, JSONObject item, Map<String, QuestionInfo> questions) {
        String id = first(item, "questionId", "resId");
        QuestionInfo info = questions.get(id);
        String order = info == null
                ? item.optString("orderNum", "?") : info.order;
        int type = info == null ? item.optInt("questionType", 0) : info.type;
        String typeName;
        switch (type) {
            case 1:
                typeName = "单选";
                break;
            case 2:
                typeName = "多选";
                break;
            case 3:
                typeName = "主观";
                break;
            case 4:
                typeName = "填空";
                break;
            case 5:
                typeName = "判断";
                break;
            case 6:
                typeName = "改错";
                break;
            default:
                typeName = "";
        }
        return prefix + " · 题" + order
                + (typeName.isEmpty() ? "" : " · " + typeName);
    }

    private static void addItem(
            List<ImageItem> target, String category, String label, String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url.isEmpty() || !isSupportedFile(url)) {
            return;
        }
        String key = ossKey(url);
        if (!key.isEmpty()) {
            target.add(new ImageItem(category, label, url, key));
        }
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "--".equals(lower) || "-".equals(lower)
                || "null".equals(lower) || "none".equals(lower)
                || "undefined".equals(lower) || "无".equals(value)) {
            return "";
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return lower.startsWith("http://")
                    ? "https://" + value.substring(7) : value;
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return value.startsWith("/") ? CDN_BASE + value : CDN_BASE + "/" + value;
    }

    private static String cacheBustedUrl(String url, String parameter) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + parameter + "=" + System.currentTimeMillis();
    }

    private static String ossKey(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            String path = Uri.parse(url).getPath();
            return path == null ? "" : trimLeadingSlash(path);
        } catch (Throwable t) {
            int query = url.indexOf('?');
            return trimLeadingSlash(query >= 0 ? url.substring(0, query) : url);
        }
    }

    private static String trimLeadingSlash(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '/') {
            index++;
        }
        return value.substring(index);
    }

    private static String safeObjectKey(String value) {
        String key = trimLeadingSlash(value == null ? "" : value);
        if (key.isEmpty() || key.contains("..")
                || key.contains("\\") || key.startsWith(".")) {
            throw new IllegalArgumentException("OSS 对象路径无效");
        }
        return key;
    }

    private static String fileExtension(String value) {
        String clean = value == null ? "" : value;
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        int dot = clean.lastIndexOf('.');
        return dot <= slash ? ""
                : clean.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isImageExtension(String extension) {
        return extension != null && extension.matches(
                "(?i)jpg|jpeg|png|gif|bmp|webp|tif|tiff");
    }

    private static boolean isSupportedExtension(String extension) {
        return extension != null && extension.matches(
                "(?i)jpg|jpeg|png|gif|bmp|webp|tif|tiff|pdf|"
                        + "doc|docx|rtf|xls|xlsx|csv|ppt|pptx|"
                        + "mp3|m4a|aac|wav|ogg|flac|"
                        + "mp4|m4v|mov|webm|mkv|"
                        + "txt|md|json|xml|html|htm|zip|rar|7z");
    }

    private static boolean isSupportedFile(String value) {
        String extension = fileExtension(value);
        return extension.isEmpty() || isSupportedExtension(extension);
    }

    private static String fileFamily(String extension) {
        if (isImageExtension(extension)) {
            return "image";
        }
        if ("pdf".equals(extension)) {
            return "pdf";
        }
        if (extension.matches("doc|docx|rtf")) {
            return "document";
        }
        if (extension.matches("xls|xlsx|csv")) {
            return "spreadsheet";
        }
        if (extension.matches("ppt|pptx")) {
            return "presentation";
        }
        if (extension.matches("mp3|m4a|aac|wav|ogg|flac")) {
            return "audio";
        }
        if (extension.matches("mp4|m4v|mov|webm|mkv")) {
            return "video";
        }
        if (extension.matches("txt|md|json|xml|html|htm")) {
            return "text";
        }
        if (extension.matches("zip|rar|7z")) {
            return "archive";
        }
        return "file";
    }

    private static String fileFamilyLabel(String extension) {
        switch (fileFamily(extension)) {
            case "image":
                return "图片";
            case "pdf":
                return "PDF 文档";
            case "document":
                return "文档";
            case "spreadsheet":
                return "表格";
            case "presentation":
                return "演示文稿";
            case "audio":
                return "音频";
            case "video":
                return "视频";
            case "text":
                return "文本";
            case "archive":
                return "压缩包";
            default:
                return "文件";
        }
    }

    private static boolean compatibleFileTypes(
            String targetExtension, String uploadExtension) {
        if (targetExtension == null || targetExtension.isEmpty()) {
            return true;
        }
        if (targetExtension.equals(uploadExtension)) {
            return true;
        }
        String targetFamily = fileFamily(targetExtension);
        return targetFamily.equals(fileFamily(uploadExtension))
                && targetFamily.matches("image|audio|video");
    }

    private static String contentTypeForExtension(String extension) {
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "tif":
            case "tiff":
                return "image/tiff";
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "rtf":
                return "application/rtf";
            case "xls":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "csv":
                return "text/csv";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "mp3":
                return "audio/mpeg";
            case "m4a":
                return "audio/mp4";
            case "aac":
                return "audio/aac";
            case "wav":
                return "audio/wav";
            case "ogg":
                return "audio/ogg";
            case "flac":
                return "audio/flac";
            case "mp4":
                return "video/mp4";
            case "m4v":
                return "video/x-m4v";
            case "mov":
                return "video/quicktime";
            case "webm":
                return "video/webm";
            case "mkv":
                return "video/x-matroska";
            case "txt":
                return "text/plain";
            case "md":
                return "text/markdown";
            case "json":
                return "application/json";
            case "xml":
                return "application/xml";
            case "html":
            case "htm":
                return "text/html";
            case "zip":
                return "application/zip";
            case "rar":
                return "application/vnd.rar";
            case "7z":
                return "application/x-7z-compressed";
            default:
                return "application/octet-stream";
        }
    }

    private static String validateUpload(
            byte[] bytes, String fileName, String targetKey,
            String declaredMime) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("替换文件为空");
        }
        if (bytes.length > MAX_LOCAL_FILE_BYTES) {
            throw new IllegalArgumentException("替换文件不能超过 50 MB");
        }
        String extension = fileExtension(fileName);
        if (extension.isEmpty() || !isSupportedExtension(extension)) {
            throw new IllegalArgumentException("不支持该文件格式");
        }
        String targetExtension = fileExtension(targetKey);
        if (!compatibleFileTypes(targetExtension, extension)) {
            throw new IllegalArgumentException(
                    "替换文件格式不匹配：目标为 ."
                            + targetExtension + "，所选文件为 ." + extension);
        }
        if (isImageExtension(extension)) {
            return sniffImageMime(bytes);
        }
        if ("pdf".equals(extension)) {
            String header = bytes.length >= 5
                    ? new String(bytes, 0, 5, StandardCharsets.US_ASCII) : "";
            if (!"%PDF-".equals(header)) {
                throw new IllegalArgumentException("所选文件不是有效的 PDF");
            }
        }
        String supplied = declaredMime == null ? "" : declaredMime.trim();
        return supplied.isEmpty()
                || "application/octet-stream".equalsIgnoreCase(supplied)
                ? contentTypeForExtension(extension) : supplied;
    }

    private static String[] supportedMimeTypes() {
        return new String[]{
                "image/*", "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/rtf", "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv", "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "audio/*", "video/*", "text/*", "application/json",
                "application/xml", "application/zip", "application/vnd.rar",
                "application/x-7z-compressed"
        };
    }

    private static boolean looksLikeStoredPath(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.contains("/") || trimmed.startsWith("//")
                || trimmed.startsWith("http://") || trimmed.startsWith("https://")
                || isSupportedExtension(fileExtension(trimmed));
    }

    private static List<ImageItem> deduplicate(List<ImageItem> items) {
        Map<String, ImageItem> unique = new LinkedHashMap<>();
        for (ImageItem item : items) {
            if (!unique.containsKey(item.ossKey)) {
                unique.put(item.ossKey, item);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static JSONArray findArray(JSONObject source, String key) {
        JSONArray result = new JSONArray();
        for (JSONObject candidate : objectSources(source)) {
            JSONArray array = candidate.optJSONArray(key);
            if (array == null) {
                continue;
            }
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value != null) {
                    result.put(value);
                }
            }
        }
        return result;
    }

    private static List<JSONObject> objectSources(JSONObject source) {
        List<JSONObject> result = new ArrayList<>();
        List<JSONObject> queue = new ArrayList<>();
        if (source != null) {
            queue.add(source);
        }
        for (int index = 0; index < queue.size(); index++) {
            JSONObject current = queue.get(index);
            if (result.contains(current)) {
                continue;
            }
            result.add(current);
            for (String wrapper : new String[]{
                    "data", "result", "object", "InfoMap", "infoMap"}) {
                JSONObject nested = current.optJSONObject(wrapper);
                if (nested != null && !result.contains(nested)) {
                    queue.add(nested);
                }
            }
        }
        return result;
    }

    private static JSONObject findObjectWithFields(
            JSONObject source, String... fields) {
        for (JSONObject candidate : objectSources(source)) {
            if (hasFields(candidate, fields)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasFields(JSONObject value, String... fields) {
        for (String field : fields) {
            if (value.optString(field, "").isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String first(JSONObject source, String... keys) {
        for (String key : keys) {
            Object value = source.opt(key);
            if (value != null && value != JSONObject.NULL) {
                String text = String.valueOf(value);
                if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String findString(
            JSONObject source, String key, String fallback) {
        for (JSONObject candidate : objectSources(source)) {
            String value = candidate.optString(key, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return fallback;
    }

    private static void collectFirstField(
            List<String> target, JSONArray array, String... keys) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                target.add(first(item, keys));
            }
        }
    }

    private static byte[] readFully(InputStream input, int maxBytes)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalArgumentException(
                        "文件超过限制（" + humanSize(maxBytes) + "）");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Bitmap decodePreview(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / sample > 1600
                || bounds.outHeight / sample > 1600) {
            sample *= 2;
        }
        BitmapFactory.Options actual = new BitmapFactory.Options();
        actual.inSampleSize = sample;
        actual.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, actual);
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "selected_image" : segment;
    }

    private static String sniffImageMime(byte[] bytes) {
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I'
                && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return "image/bmp";
        }
        if (bytes.length >= 4
                && ((bytes[0] == 'I' && bytes[1] == 'I'
                && bytes[2] == 42 && bytes[3] == 0)
                || (bytes[0] == 'M' && bytes[1] == 'M'
                && bytes[2] == 0 && bytes[3] == 42))) {
            return "image/tiff";
        }
        throw new IllegalArgumentException("所选文件不是受支持的图片");
    }

    private static String md5(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return result.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024d);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024d));
    }

    private static Integer parseInteger(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String appendNote(String original, String addition) {
        return original.isEmpty() ? addition : original + "\n" + addition;
    }

    private static String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String friendly(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return current.getClass().getSimpleName();
        }
        return current.getClass().getSimpleName() + "：" + message;
    }

    private static void logFailure(String action, Throwable throwable) {
        ModuleLog.log(TAG + " OSS ToolKit " + action + " failed: "
                + friendly(throwable));
    }

    private static final class Session {
        final String userId;
        final String unitId;
        final String realName;
        final String sign;

        Session(String userId, String unitId, String realName, String sign) {
            this.userId = userId;
            this.unitId = unitId;
            this.realName = realName;
            this.sign = sign;
        }
    }

    private static final class QuestionInfo {
        final String order;
        final int type;

        QuestionInfo(String order, int type) {
            this.order = order;
            this.type = type;
        }
    }

    private static final class ImageItem {
        final String category;
        final String label;
        final String url;
        final String ossKey;
        final String extension;
        final String family;
        final String contentType;

        ImageItem(String category, String label, String url, String ossKey) {
            this.category = category;
            this.label = label;
            this.url = url;
            this.ossKey = ossKey;
            this.extension = fileExtension(ossKey);
            this.family = fileFamily(extension);
            this.contentType = contentTypeForExtension(extension);
        }

        boolean isImage() {
            return "image".equals(family);
        }

        String familyLabel() {
            return fileFamilyLabel(extension);
        }

        String fileName() {
            int slash = ossKey.lastIndexOf('/');
            String name = slash >= 0 ? ossKey.substring(slash + 1) : ossKey;
            return name.isEmpty() ? "OSS 文件" : name;
        }
    }

    private static final class FetchResult {
        final String homeworkName;
        final List<ImageItem> items;
        final String note;

        FetchResult(
                String homeworkName, List<ImageItem> items, String note) {
            this.homeworkName = homeworkName;
            this.items = items;
            this.note = note;
        }
    }

    private static final class HttpBytes {
        final byte[] bytes;
        final Map<String, String> headers;

        HttpBytes(byte[] bytes, Map<String, String> headers) {
            this.bytes = bytes;
            this.headers = headers;
        }
    }

    private static final class PublicStatus {
        final boolean matches;
        final long size;
        final Integer age;
        final Integer cacheTime;
        final String cacheResult;
        final String error;

        PublicStatus(boolean matches, long size, Integer age, Integer cacheTime,
                     String cacheResult, String error) {
            this.matches = matches;
            this.size = size;
            this.age = age;
            this.cacheTime = cacheTime;
            this.cacheResult = cacheResult;
            this.error = error;
        }

        static PublicStatus unavailable(String error) {
            return new PublicStatus(false, 0, null, null, "", error);
        }
    }

    private static final class ReplacementResult {
        final int size;
        final String digest;
        final PublicStatus publicStatus;

        ReplacementResult(int size, String digest, PublicStatus publicStatus) {
            this.size = size;
            this.digest = digest;
            this.publicStatus = publicStatus;
        }
    }
}
