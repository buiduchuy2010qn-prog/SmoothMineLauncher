package net.kdt.pojavlaunch.smooth;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.smooth.SmoothHardwareProfiler.SmoothMode;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GameFileOptimizer: Hệ thống can thiệp & tối ưu hóa sâu trực tiếp vào các file cấu hình,
 * tài nguyên (Resource Pack), bộ đệm I/O và hệ sinh thái Mod của Minecraft.
 * Đi kèm cơ chế sao lưu tự động (smoothmine_backup/) để khôi phục nguyên trạng 1-chạm.
 */
public final class GameFileOptimizer {
    private static final String TAG = "GameFileOptimizer";

    public static final String PREF_DEEP_OPT_ACTIVE = "smooth_deep_file_opt_active";
    public static final String PREF_OPT_ENGINE = "smooth_opt_engine_io";
    public static final String PREF_OPT_MOD_CONFIGS = "smooth_opt_mod_configs";
    public static final String PREF_OPT_FPS_PACK = "smooth_opt_fps_resourcepack";
    public static final String PREF_OPT_LOG_CLEAN = "smooth_opt_log_clean";
    public static final String PREF_OPT_AUTO_LAUNCH = "smooth_opt_auto_prelaunch";

    public static final String FPS_PACK_FILENAME = "SmoothMine-FPS-Pack.zip";
    private static final String BACKUP_DIR_NAME = "smoothmine_backup";
    private static final Pattern MC_VERSION_PATTERN = Pattern.compile("(?:^|[^0-9])(1\\.\\d+(?:\\.\\d+)?)");

    private GameFileOptimizer() {}

    public interface OnOptimizationDoneListener {
        void onDone();
    }

    public static final class OptimizationOptions {
        public boolean patchEngineAndIo;
        public boolean patchModAndOptiFineConfigs;
        public boolean injectFpsResourcePack;
        public boolean throttleLogsAndCleanJunk;
        public boolean autoApplyOnLaunch;

        public static OptimizationOptions loadFromPrefs() {
            OptimizationOptions opts = new OptimizationOptions();
            SharedPreferences prefs = LauncherPreferences.DEFAULT_PREF;
            if (prefs == null) {
                opts.patchEngineAndIo = true;
                opts.patchModAndOptiFineConfigs = true;
                opts.injectFpsResourcePack = true;
                opts.throttleLogsAndCleanJunk = true;
                opts.autoApplyOnLaunch = true;
                return opts;
            }
            opts.patchEngineAndIo = prefs.getBoolean(PREF_OPT_ENGINE, true);
            opts.patchModAndOptiFineConfigs = prefs.getBoolean(PREF_OPT_MOD_CONFIGS, true);
            opts.injectFpsResourcePack = prefs.getBoolean(PREF_OPT_FPS_PACK, true);
            opts.throttleLogsAndCleanJunk = prefs.getBoolean(PREF_OPT_LOG_CLEAN, true);
            opts.autoApplyOnLaunch = prefs.getBoolean(PREF_OPT_AUTO_LAUNCH, true);
            return opts;
        }

        public void saveToPrefs(boolean markActive) {
            if (LauncherPreferences.DEFAULT_PREF == null) return;
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putBoolean(PREF_DEEP_OPT_ACTIVE, markActive)
                    .putBoolean(PREF_OPT_ENGINE, patchEngineAndIo)
                    .putBoolean(PREF_OPT_MOD_CONFIGS, patchModAndOptiFineConfigs)
                    .putBoolean(PREF_OPT_FPS_PACK, injectFpsResourcePack)
                    .putBoolean(PREF_OPT_LOG_CLEAN, throttleLogsAndCleanJunk)
                    .putBoolean(PREF_OPT_AUTO_LAUNCH, autoApplyOnLaunch)
                    .apply();
        }
    }

    public static final class OptimizationResult {
        public final String targetPathLabel;
        public final SmoothMode appliedMode;
        public final List<String> interventions = new ArrayList<>();
        public int deletedJunkFiles = 0;
        public long freedBytes = 0L;

        public OptimizationResult(String targetPathLabel, SmoothMode appliedMode) {
            this.targetPathLabel = targetPathLabel;
            this.appliedMode = appliedMode;
        }

        public String formatFreedSize() {
            if (freedBytes <= 0) return "0 KB (Đã sạch)";
            if (freedBytes < 1024L * 1024L) {
                return String.format(Locale.US, "%.1f KB", freedBytes / 1024.0);
            }
            return String.format(Locale.US, "%.2f MB", freedBytes / (1024.0 * 1024.0));
        }
    }

    public static boolean isDeepOptimizationActive() {
        return LauncherPreferences.DEFAULT_PREF != null
                && LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_DEEP_OPT_ACTIVE, false);
    }

    public static boolean isLogThrottleEnabled() {
        return LauncherPreferences.DEFAULT_PREF != null
                && LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_DEEP_OPT_ACTIVE, false)
                && LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_OPT_LOG_CLEAN, true);
    }

    /**
     * Hiển thị hộp thoại tương tác cho phép người chơi chọn các hạng mục can thiệp sâu vào file game,
     * hoặc khôi phục lại toàn bộ file gốc từ bản sao lưu an toàn.
     */
    public static void showOptimizerDialog(Activity activity, OnOptimizationDoneListener listener) {
        final OptimizationOptions opts = OptimizationOptions.loadFromPrefs();
        final CharSequence[] items = new CharSequence[]{
                activity.getString(R.string.smooth_opt_item_engine),
                activity.getString(R.string.smooth_opt_item_mods),
                activity.getString(R.string.smooth_opt_item_pack),
                activity.getString(R.string.smooth_opt_item_logs),
                activity.getString(R.string.smooth_opt_item_auto)
        };
        final boolean[] checked = new boolean[]{
                opts.patchEngineAndIo,
                opts.patchModAndOptiFineConfigs,
                opts.injectFpsResourcePack,
                opts.throttleLogsAndCleanJunk,
                opts.autoApplyOnLaunch
        };

        new AlertDialog.Builder(activity)
                .setTitle(R.string.smooth_file_opt_dialog_title)
                .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.smooth_file_opt_apply_btn, (dialog, which) -> {
                    opts.patchEngineAndIo = checked[0];
                    opts.patchModAndOptiFineConfigs = checked[1];
                    opts.injectFpsResourcePack = checked[2];
                    opts.throttleLogsAndCleanJunk = checked[3];
                    opts.autoApplyOnLaunch = checked[4];

                    boolean anySelected = opts.patchEngineAndIo
                            || opts.patchModAndOptiFineConfigs
                            || opts.injectFpsResourcePack
                            || opts.throttleLogsAndCleanJunk;
                    opts.saveToPrefs(anySelected || opts.autoApplyOnLaunch);

                    Toast.makeText(activity, R.string.smooth_file_opt_running, Toast.LENGTH_SHORT).show();
                    PojavApplication.sExecutorService.execute(() -> {
                        MinecraftProfile profile = getSafeCurrentProfile();
                        File gameDir = profile != null ? Tools.getGameDirPath(profile) : new File(Tools.DIR_GAME_NEW);
                        String versionId = profile != null && profile.lastVersionId != null
                                ? profile.lastVersionId : "latest-release";
                        SmoothMode mode = SmoothHardwareProfiler.getSavedMode();

                        OptimizationResult result = executeInterventions(activity, gameDir, versionId, mode, opts);

                        // Đồng bộ cả thư mục .minecraft mặc định nếu profile hiện tại dùng thư mục riêng
                        File defaultDir = new File(Tools.DIR_GAME_NEW);
                        if (!defaultDir.getAbsolutePath().equals(gameDir.getAbsolutePath())) {
                            executeInterventions(activity, defaultDir, versionId, mode, opts);
                        }

                        Tools.runOnUiThread(() -> {
                            if (listener != null) listener.onDone();
                            showResultSummaryDialog(activity, result);
                        });
                    });
                })
                .setNeutralButton(R.string.smooth_file_opt_restore_btn, (dialog, which) -> {
                    PojavApplication.sExecutorService.execute(() -> {
                        MinecraftProfile profile = getSafeCurrentProfile();
                        File gameDir = profile != null ? Tools.getGameDirPath(profile) : new File(Tools.DIR_GAME_NEW);
                        int restored = restoreOriginalFiles(gameDir);
                        File defaultDir = new File(Tools.DIR_GAME_NEW);
                        if (!defaultDir.getAbsolutePath().equals(gameDir.getAbsolutePath())) {
                            restored += restoreOriginalFiles(defaultDir);
                        }
                        if (LauncherPreferences.DEFAULT_PREF != null) {
                            LauncherPreferences.DEFAULT_PREF.edit()
                                    .putBoolean(PREF_DEEP_OPT_ACTIVE, false)
                                    .putBoolean(PREF_OPT_AUTO_LAUNCH, false)
                                    .apply();
                        }
                        final int totalRestored = restored;
                        Tools.runOnUiThread(() -> {
                            if (listener != null) listener.onDone();
                            Toast.makeText(
                                    activity,
                                    activity.getString(R.string.smooth_file_opt_restored_toast, totalRestored),
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showResultSummaryDialog(Activity activity, OptimizationResult result) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        StringBuilder details = new StringBuilder();
        for (String line : result.interventions) {
            details.append("• ").append(line).append('\n');
        }
        if (details.length() == 0) {
            details.append("• Đã lưu thiết lập tự động.\n");
        }
        String message = activity.getString(
                R.string.smooth_file_opt_result_msg,
                result.targetPathLabel,
                result.appliedMode.displayName,
                details.toString().trim(),
                result.deletedJunkFiles,
                result.formatFreedSize()
        );
        new AlertDialog.Builder(activity)
                .setTitle(R.string.smooth_file_opt_result_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Tối ưu toàn bộ cho một thư mục instance mới (được gọi tự động khi cài gói Modpack X3 FPS).
     */
    public static void optimizeAllForDirectory(Context ctx, File gameDir, String mcVersion, SmoothMode mode) {
        OptimizationOptions all = new OptimizationOptions();
        all.patchEngineAndIo = true;
        all.patchModAndOptiFineConfigs = true;
        all.injectFpsResourcePack = true;
        all.throttleLogsAndCleanJunk = true;
        all.autoApplyOnLaunch = true;
        all.saveToPrefs(true);
        executeInterventions(ctx, gameDir, mcVersion, mode, all);
    }

    /**
     * Hook chạy ngầm cực nhanh ngay trước khi khởi chạy JVM trong Tools.launchMinecraft().
     */
    public static void onPreLaunch(Context ctx, MinecraftProfile profile, File gameDir, String versionId) {
        try {
            if (LauncherPreferences.DEFAULT_PREF == null) return;
            boolean active = LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_DEEP_OPT_ACTIVE, false);
            boolean autoLaunch = LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_OPT_AUTO_LAUNCH, false);
            if (!active || !autoLaunch) return;

            OptimizationOptions opts = OptimizationOptions.loadFromPrefs();
            SmoothMode mode = SmoothHardwareProfiler.getSavedMode();
            String ver = versionId != null ? versionId
                    : (profile != null && profile.lastVersionId != null ? profile.lastVersionId : "latest-release");
            executeInterventions(ctx, gameDir, ver, mode, opts);
        } catch (Throwable t) {
            Log.w(TAG, "Pre-launch file optimization skipped safely", t);
        }
    }

    /**
     * Chuyển đổi file cấu hình Log4j2 sang chế độ ghi đệm bất đồng bộ (immediateFlush=false)
     * và lọc bớt INFO spam để triệt tiêu hiện tượng giật khựng I/O đĩa trên điện thoại,
     * đồng thời giữ nguyên 100% bộ lọc chống lỗ hổng RCE Log4Shell.
     */
    public static String resolveOptimalLog4jConfig(File gameDir, String baseConfigPath) {
        if (!isLogThrottleEnabled() || baseConfigPath == null) {
            return baseConfigPath;
        }
        try {
            File baseFile = new File(baseConfigPath);
            if (!baseFile.exists() || !baseFile.isFile()) {
                return baseConfigPath;
            }
            String xml = org.apache.commons.io.FileUtils.readFileToString(baseFile, StandardCharsets.UTF_8);
            if (!xml.contains("immediateFlush=")) {
                xml = xml.replace(
                        "<RollingRandomAccessFile name=\"File\" ",
                        "<RollingRandomAccessFile name=\"File\" immediateFlush=\"false\" "
                );
            }
            xml = xml.replace("<Root level=\"info\">", "<Root level=\"warn\">")
                     .replace("<Root level=\"INFO\">", "<Root level=\"WARN\">");

            // Lưu vào vùng security nội bộ để không bao giờ gặp lỗi khoảng trắng đường dẫn URI
            File securityDir = new File(Tools.DIR_DATA, "security");
            FileUtils.ensureDirectorySilently(securityDir);
            File optimizedSecurityXml = new File(securityDir, "smoothmine-" + baseFile.getName());
            org.apache.commons.io.FileUtils.writeStringToFile(optimizedSecurityXml, xml, StandardCharsets.UTF_8);

            // Lưu bản tham chiếu trong thư mục config của game để người dùng dễ kiểm tra
            if (gameDir != null) {
                File gameConfigDir = new File(gameDir, "config");
                if (FileUtils.ensureDirectorySilently(gameConfigDir)) {
                    File mirrorXml = new File(gameConfigDir, "smoothmine-log4j2.xml");
                    org.apache.commons.io.FileUtils.writeStringToFile(mirrorXml, xml, StandardCharsets.UTF_8);
                }
            }
            return optimizedSecurityXml.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "Fallback to default log4j config", t);
            return baseConfigPath;
        }
    }

    public static OptimizationResult executeInterventions(
            Context ctx,
            File gameDir,
            String versionId,
            SmoothMode mode,
            OptimizationOptions opts
    ) {
        if (gameDir == null) {
            gameDir = new File(Tools.DIR_GAME_NEW);
        }
        FileUtils.ensureDirectorySilently(gameDir);
        OptimizationResult result = new OptimizationResult(gameDir.getName(), mode);

        // 1. Can thiệp sâu options.txt & splash.properties (Engine & Async Chunk I/O)
        if (opts.patchEngineAndIo) {
            backupIfNeeded(gameDir, "options.txt");
            patchDeepVanillaOptions(ctx, gameDir, mode, opts.injectFpsResourcePack, versionId);
            patchLegacySplashAndForgeConfigs(gameDir);
            result.interventions.add("options.txt (Tắt SyncChunkWrites I/O, giảm tải OpenAL & GL)");
        }

        // 2. Can thiệp sâu hệ thống Mod & OptiFine (optionsof.txt + Sodium/Embeddium/Lithium/FerriteCore/ImmediatelyFast/EntityCulling)
        if (opts.patchModAndOptiFineConfigs) {
            backupIfNeeded(gameDir, "optionsof.txt");
            backupIfNeeded(gameDir, "config/sodium-options.json");
            backupIfNeeded(gameDir, "config/embeddium-options.json");
            backupIfNeeded(gameDir, "config/immediatelyfast.json");
            backupIfNeeded(gameDir, "config/entityculling.json");

            patchOptiFineOptions(gameDir, mode);
            patchSodiumAndEmbeddiumConfigs(gameDir, mode);
            patchLithiumAndFerriteConfigs(gameDir);
            patchImmediatelyFastConfig(gameDir);
            patchEntityCullingConfig(gameDir, mode);
            patchExistingForgeTomls(gameDir);

            result.interventions.add("optionsof.txt (OptiFine SmartAnimations, RenderRegions, tắt glFinish)");
            result.interventions.add("config/* (Sodium/Embeddium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling)");
        }

        // 3. Đóng gói & kích hoạt gói tài nguyên siêu nhẹ SmoothMine-FPS-Pack.zip
        if (opts.injectFpsResourcePack) {
            boolean packOk = generateSmoothMineResourcePack(ctx, gameDir, versionId, mode);
            if (packOk) {
                if (!opts.patchEngineAndIo) {
                    backupIfNeeded(gameDir, "options.txt");
                    enableResourcePackInOptionsOnly(ctx, gameDir, versionId, true);
                }
                result.interventions.add("resourcepacks/" + FPS_PACK_FILENAME + " (Khử Overdraw & hạt môi trường)");
            }
        } else {
            // Nếu người chơi bỏ chọn gói FPS Pack thì gỡ khỏi danh sách kích hoạt gọn gàng
            removeGeneratedResourcePack(gameDir);
        }

        // 4. Dọn dẹp rác Cache / Logs nén / Crash cũ / Mixin dump & kích hoạt Log4j2 Async
        if (opts.throttleLogsAndCleanJunk) {
            cleanDirectoryJunk(gameDir, result);
            if (Tools.DIR_CACHE != null) {
                cleanStaleTempFiles(Tools.DIR_CACHE, result);
            }
            result.interventions.add("Log4j2 Async Buffer (Chặn spam INFO ghi đĩa gây khựng)");
        }

        return result;
    }

    /**
     * Sao lưu bản gốc duy nhất lần đầu tiên trước khi SmoothMine can thiệp.
     */
    private static void backupIfNeeded(File gameDir, String relativePath) {
        try {
            File original = new File(gameDir, relativePath);
            if (!original.exists() || !original.isFile()) return;

            File backupDir = new File(gameDir, BACKUP_DIR_NAME);
            FileUtils.ensureDirectorySilently(backupDir);
            String safeName = relativePath.replace('/', '_').replace('\\', '_') + ".bak";
            File backupFile = new File(backupDir, safeName);
            if (!backupFile.exists()) {
                org.apache.commons.io.FileUtils.copyFile(original, backupFile);
            }
        } catch (IOException e) {
            Log.w(TAG, "Backup skipped for " + relativePath, e);
        }
    }

    /**
     * Khôi phục toàn bộ các file gốc từ smoothmine_backup/ và gỡ gói tài nguyên FPS tự tạo.
     */
    public static int restoreOriginalFiles(File gameDir) {
        if (gameDir == null || !gameDir.exists()) return 0;
        int restoredCount = 0;

        removeGeneratedResourcePack(gameDir);
        File mirrorLogXml = new File(gameDir, "config/smoothmine-log4j2.xml");
        if (mirrorLogXml.exists() && mirrorLogXml.delete()) {
            restoredCount++;
        }

        String[][] mapping = new String[][]{
                {"options.txt.bak", "options.txt"},
                {"optionsof.txt.bak", "optionsof.txt"},
                {"config_sodium-options.json.bak", "config/sodium-options.json"},
                {"config_embeddium-options.json.bak", "config/embeddium-options.json"},
                {"config_immediatelyfast.json.bak", "config/immediatelyfast.json"},
                {"config_entityculling.json.bak", "config/entityculling.json"}
        };

        File backupDir = new File(gameDir, BACKUP_DIR_NAME);
        if (backupDir.exists() && backupDir.isDirectory()) {
            for (String[] pair : mapping) {
                File bak = new File(backupDir, pair[0]);
                if (bak.exists() && bak.isFile()) {
                    File dest = new File(gameDir, pair[1]);
                    FileUtils.ensureParentDirectorySilently(dest);
                    try {
                        org.apache.commons.io.FileUtils.copyFile(bak, dest);
                        // Đảm bảo nếu khôi phục options.txt thì cũng gỡ tham chiếu FPS Pack nếu có
                        if ("options.txt".equals(pair[1])) {
                            enableResourcePackInOptionsOnly(null, gameDir, null, false);
                        }
                        bak.delete();
                        restoredCount++;
                    } catch (IOException e) {
                        Log.w(TAG, "Could not restore " + pair[1], e);
                    }
                }
            }
        }
        return restoredCount;
    }

    /**
     * Can thiệp sâu options.txt của Vanilla Minecraft (giữ nguyên toàn bộ phím điều khiển & âm lượng).
     */
    private static void patchDeepVanillaOptions(
            Context ctx,
            File gameDir,
            SmoothMode mode,
            boolean enableFpsPack,
            String versionId
    ) {
        File optionsFile = new File(gameDir, "options.txt");
        LinkedHashMap<String, String> map = readColonKeyValueFile(ctx, optionsFile, "options.txt");

        // Các thông số sâu chống khựng I/O flash và giảm tải CPU/GPU trên điện thoại
        map.put("syncChunkWrites", "false");
        map.put("glDebugVerbosity", "0");
        map.put("directionalAudio", "false");
        map.put("prioritizeChunkUpdates", "0");
        map.put("enableVsync", "false");
        map.put("fboEnable", "true");
        map.put("snooperEnabled", "false");
        map.put("telemetryOptInExtra", "false");
        map.put("onboardAccessibility", "false");
        map.put("pauseOnLostFocus", "false");

        switch (mode) {
            case ULTRA_FPS:
                map.put("renderDistance", "5");
                map.put("simulationDistance", "5");
                map.put("maxFps", "260");
                map.put("fancyGraphics", "false");
                map.put("graphicsMode", "0");
                map.put("ao", "0");
                map.put("clouds", "false");
                map.put("cloudRenderMode", "\"off\"");
                map.put("particles", "2");
                map.put("mipmapLevels", "0");
                map.put("biomeBlendRadius", "0");
                map.put("entityShadows", "false");
                map.put("entityDistanceScaling", "0.55");
                map.put("bobView", "false");
                map.put("GuiScale", map.containsKey("guiScale") ? map.get("guiScale") : "3");
                map.remove("GuiScale");
                break;
            case QUALITY:
                map.put("renderDistance", "10");
                map.put("simulationDistance", "8");
                map.put("maxFps", "120");
                map.put("fancyGraphics", "true");
                map.put("graphicsMode", "1");
                map.put("ao", "2");
                map.put("clouds", "true");
                map.put("cloudRenderMode", "\"fast\"");
                map.put("particles", "1");
                map.put("mipmapLevels", "2");
                map.put("biomeBlendRadius", "2");
                map.put("entityShadows", "false");
                map.put("entityDistanceScaling", "0.9");
                break;
            case BALANCED:
            default:
                map.put("renderDistance", "7");
                map.put("simulationDistance", "6");
                map.put("maxFps", "160");
                map.put("fancyGraphics", "false");
                map.put("graphicsMode", "0");
                map.put("ao", "1");
                map.put("clouds", "false");
                map.put("cloudRenderMode", "\"off\"");
                map.put("particles", "1");
                map.put("mipmapLevels", "0");
                map.put("biomeBlendRadius", "1");
                map.put("entityShadows", "false");
                map.put("entityDistanceScaling", "0.7");
                break;
        }

        if (enableFpsPack) {
            updateResourcePackArrayInMap(map, versionId, true);
        }

        writeColonKeyValueFile(optionsFile, map);
    }

    private static void enableResourcePackInOptionsOnly(Context ctx, File gameDir, String versionId, boolean enable) {
        File optionsFile = new File(gameDir, "options.txt");
        if (!optionsFile.exists() && !enable) return;
        LinkedHashMap<String, String> map = readColonKeyValueFile(ctx, optionsFile, "options.txt");
        updateResourcePackArrayInMap(map, versionId, enable);
        writeColonKeyValueFile(optionsFile, map);
    }

    private static void updateResourcePackArrayInMap(Map<String, String> map, String versionId, boolean enable) {
        boolean isLegacy = isLegacyPre113(versionId);
        String packEntry = isLegacy ? FPS_PACK_FILENAME : ("file/" + FPS_PACK_FILENAME);

        String rawPacks = map.get("resourcePacks");
        JSONArray arr = parseJsonArraySafe(rawPacks);

        JSONArray filtered = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            String item = arr.optString(i, "");
            if (!item.isEmpty()
                    && !FPS_PACK_FILENAME.equals(item)
                    && !("file/" + FPS_PACK_FILENAME).equals(item)) {
                filtered.put(item);
            }
        }

        if (enable) {
            if (!isLegacy && filtered.length() == 0) {
                filtered.put("vanilla");
            }
            filtered.put(packEntry);
        }

        map.put("resourcePacks", filtered.toString().replace("\\/", "/"));

        // Đánh dấu tương thích để không hiện hộp thoại xác nhận trên bản mới
        JSONArray incompat = parseJsonArraySafe(map.get("incompatibleResourcePacks"));
        JSONArray filteredIncompat = new JSONArray();
        for (int i = 0; i < incompat.length(); i++) {
            String item = incompat.optString(i, "");
            if (!item.isEmpty()
                    && !FPS_PACK_FILENAME.equals(item)
                    && !("file/" + FPS_PACK_FILENAME).equals(item)) {
                filteredIncompat.put(item);
            }
        }
        if (enable && !isLegacy) {
            filteredIncompat.put(packEntry);
        }
        if (filteredIncompat.length() > 0 || map.containsKey("incompatibleResourcePacks")) {
            map.put("incompatibleResourcePacks", filteredIncompat.toString().replace("\\/", "/"));
        }
    }

    /**
     * Can thiệp sâu file optionsof.txt của OptiFine (hỗ trợ cả người chơi Forge + OptiFine và OptiFine lẻ).
     * Đặc biệt tắt ofSmoothFps (vốn gọi glFinish() làm tụt 40% FPS trên GPU Adreno/Mali) và bật RenderRegions + SmartAnimations.
     */
    private static void patchOptiFineOptions(File gameDir, SmoothMode mode) {
        File optifineFile = new File(gameDir, "optionsof.txt");
        LinkedHashMap<String, String> map = readColonKeyValueFile(null, optifineFile, null);

        boolean ultra = (mode == SmoothMode.ULTRA_FPS);
        boolean quality = (mode == SmoothMode.QUALITY);

        map.put("ofSmoothFps", "false");
        map.put("ofSmoothWorld", "true");
        map.put("ofFastRender", "false");
        map.put("ofFastMath", "true");
        map.put("ofRenderRegions", "true");
        map.put("ofSmartAnimations", "true");
        map.put("ofLazyChunkLoading", "true");
        map.put("ofDynamicFov", quality ? "true" : "false");
        map.put("ofDynamicLights", quality ? "2" : "3");
        map.put("ofAaLevel", "0");
        map.put("ofAfLevel", "1");
        map.put("ofMipmapType", "0");
        map.put("ofClouds", quality ? "1" : "3");
        map.put("ofTrees", quality ? "2" : "1");
        map.put("ofDroppedItems", quality ? "2" : "1");
        map.put("ofVignette", "1");
        map.put("ofFogType", ultra ? "3" : "1");
        map.put("ofLagometer", "false");
        map.put("ofProfiler", "false");
        map.put("ofWeather", "true");
        map.put("ofSky", ultra ? "false" : "true");
        map.put("ofStars", ultra ? "false" : "true");
        map.put("ofSunMoon", ultra ? "false" : "true");

        // Cắt giảm tính toán hoạt ảnh chất lỏng, hạt khói & lửa không cần thiết
        map.put("ofAnimatedWater", ultra ? "2" : "0");
        map.put("ofAnimatedLava", ultra ? "2" : "0");
        map.put("ofAnimatedFire", ultra ? "false" : "true");
        map.put("ofAnimatedPortal", ultra ? "false" : "true");
        map.put("ofAnimatedRedstone", ultra ? "false" : "true");
        map.put("ofAnimatedExplosion", ultra ? "false" : "true");
        map.put("ofAnimatedFlame", ultra ? "false" : "true");
        map.put("ofAnimatedSmoke", "false");
        map.put("ofVoidParticles", "false");
        map.put("ofWaterParticles", ultra ? "false" : "true");
        map.put("ofPortalParticles", "false");
        map.put("ofPotionParticles", ultra ? "false" : "true");
        map.put("ofFireworkParticles", "true");
        map.put("ofDrippingWaterLava", "false");
        map.put("ofRainSplash", "false");
        map.put("ofAnimatedTerrain", ultra ? "false" : "true");
        map.put("ofAnimatedTextures", ultra ? "false" : "true");

        writeColonKeyValueFile(optifineFile, map);
    }

    /**
     * Hợp nhất & tối ưu sâu cấu hình JSON của cả Sodium (Fabric/NeoForge) và Embeddium (Forge).
     */
    private static void patchSodiumAndEmbeddiumConfigs(File gameDir, SmoothMode mode) {
        File configDir = new File(gameDir, "config");
        FileUtils.ensureDirectorySilently(configDir);

        String mixinContent = "mixin.features.buffer_builder.intrinsics=false\n"
                + "mixin.features.chunk_rendering=false\n";
        writeUtf8Safe(new File(configDir, "sodium-mixins.properties"), mixinContent);
        writeUtf8Safe(new File(configDir, "embeddium-mixins.properties"), mixinContent);

        patchSingleSodiumLikeJson(new File(configDir, "sodium-options.json"), mode);
        patchSingleSodiumLikeJson(new File(configDir, "embeddium-options.json"), mode);
    }

    private static void patchSingleSodiumLikeJson(File jsonFile, SmoothMode mode) {
        JSONObject root = readJsonObjectSafe(jsonFile);
        try {
            JSONObject quality = getOrCreateObject(root, "quality");
            String fastOrDefault = (mode == SmoothMode.QUALITY) ? "DEFAULT" : "FAST";
            quality.put("weather_quality", fastOrDefault);
            quality.put("leaves_quality", fastOrDefault);
            quality.put("enable_vignette", false);
            quality.put("enable_clouds", mode == SmoothMode.QUALITY);

            JSONObject performance = getOrCreateObject(root, "performance");
            performance.put("chunk_builder_threads", 0);
            performance.put("always_defer_chunk_updates", true);
            performance.put("animate_only_visible_textures", true);
            performance.put("use_entity_culling", true);
            performance.put("use_fog_occlusion", true);
            performance.put("use_block_face_culling", true);
            performance.put("use_no_error_gl_context", true);

            JSONObject advanced = getOrCreateObject(root, "advanced");
            advanced.put("cpu_render_ahead_limit", mode == SmoothMode.ULTRA_FPS ? 1 : 2);
            advanced.put("allow_direct_memory_access", true);

            writeUtf8Safe(jsonFile, root.toString(2) + "\n");
        } catch (JSONException e) {
            Log.w(TAG, "Failed patching " + jsonFile.getName(), e);
        }
    }

    /**
     * Cấu hình Lithium (tối ưu AI/Tick/Hopper) và FerriteCore (nén BlockState/Model giảm 40-45% RAM).
     */
    private static void patchLithiumAndFerriteConfigs(File gameDir) {
        File configDir = new File(gameDir, "config");
        FileUtils.ensureDirectorySilently(configDir);

        File lithiumFile = new File(configDir, "lithium.properties");
        if (!lithiumFile.exists()) {
            String lithiumContent = "# SmoothMine Optimized Lithium Config\n"
                    + "mixin.ai=true\n"
                    + "mixin.alloc=true\n"
                    + "mixin.block=true\n"
                    + "mixin.cached_hashcode=true\n"
                    + "mixin.chunk=true\n"
                    + "mixin.entity=true\n"
                    + "mixin.gen=true\n"
                    + "mixin.math=true\n"
                    + "mixin.shapes=true\n"
                    + "mixin.world=true\n";
            writeUtf8Safe(lithiumFile, lithiumContent);
        }

        File ferriteFile = new File(configDir, "ferritecore.mixin.properties");
        String ferriteContent = "# SmoothMine Optimized FerriteCore Memory Deduplication\n"
                + "replaceNeighborLookup=true\n"
                + "populateNeighborTable=true\n"
                + "cacheMultipartModels=true\n"
                + "reducedModelData=true\n"
                + "threaddetec=false\n";
        writeUtf8Safe(ferriteFile, ferriteContent);
    }

    /**
     * Cấu hình ImmediatelyFast gom nhóm lệnh vẽ GUI/HUD/Chữ vào một batch duy nhất.
     */
    private static void patchImmediatelyFastConfig(File gameDir) {
        File configDir = new File(gameDir, "config");
        FileUtils.ensureDirectorySilently(configDir);
        File file = new File(configDir, "immediatelyfast.json");

        JSONObject root = readJsonObjectSafe(file);
        try {
            root.put("font_atlas_resizing", true);
            root.put("map_atlas_generation", true);
            root.put("hud_batching", true);
            root.put("fast_text_lookup", true);
            root.put("fast_buffer_upload", true);
            root.put("dont_add_info_into_debug_hud", true);
            writeUtf8Safe(file, root.toString(2) + "\n");
        } catch (JSONException ignored) {}
    }

    /**
     * Cấu hình EntityCulling cắt bỏ cả Render lẫn Tick của thực thể khuất sau tường/hang động.
     */
    private static void patchEntityCullingConfig(File gameDir, SmoothMode mode) {
        File configDir = new File(gameDir, "config");
        FileUtils.ensureDirectorySilently(configDir);
        File file = new File(configDir, "entityculling.json");

        JSONObject root = readJsonObjectSafe(file);
        try {
            root.put("configVersion", root.optInt("configVersion", 6));
            root.put("renderNametagsThroughWalls", true);
            root.put("traceDistance", mode == SmoothMode.ULTRA_FPS ? 36 : 52);
            root.put("debugMode", false);
            root.put("sleepDelay", 10);
            root.put("hitboxLimit", 45);
            root.put("skipMarkerArmorStands", true);
            root.put("tickCulling", true);
            root.put("skipHiddenEntityRendering", true);
            root.put("skipHiddenBlockEntityRendering", true);
            writeUtf8Safe(file, root.toString(2) + "\n");
        } catch (JSONException ignored) {}
    }

    private static void patchLegacySplashAndForgeConfigs(File gameDir) {
        File configDir = new File(gameDir, "config");
        FileUtils.ensureDirectorySilently(configDir);

        File splashFile = new File(configDir, "splash.properties");
        writeUtf8Safe(splashFile, "enabled=false\n");
    }

    /**
     * Can thiệp an toàn các file cấu hình Forge/NeoForge sẵn có để bật đa luồng dựng địa hình.
     */
    private static void patchExistingForgeTomls(File gameDir) {
        File configDir = new File(gameDir, "config");
        if (!configDir.exists()) return;

        String[] clientTomls = new String[]{"forge-client.toml", "neoforge-client.toml"};
        for (String name : clientTomls) {
            File toml = new File(configDir, name);
            if (toml.exists() && toml.isFile()) {
                try {
                    String content = org.apache.commons.io.FileUtils.readFileToString(toml, StandardCharsets.UTF_8);
                    String updated = content.replace(
                            "alwaysSetupTerrainOffThread = false",
                            "alwaysSetupTerrainOffThread = true"
                    );
                    if (!updated.equals(content)) {
                        org.apache.commons.io.FileUtils.writeStringToFile(toml, updated, StandardCharsets.UTF_8);
                    }
                } catch (IOException ignored) {}
            }
        }

        File fmlToml = new File(configDir, "fml.toml");
        if (fmlToml.exists() && fmlToml.isFile()) {
            try {
                String content = org.apache.commons.io.FileUtils.readFileToString(fmlToml, StandardCharsets.UTF_8);
                String updated = content
                        .replace("versionCheck = true", "versionCheck = false")
                        .replace("splashScreen = true", "splashScreen = false");
                if (!updated.equals(content)) {
                    org.apache.commons.io.FileUtils.writeStringToFile(fmlToml, updated, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {}
        }

        File legacyForgeCfg = new File(configDir, "forge.cfg");
        if (legacyForgeCfg.exists() && legacyForgeCfg.isFile()) {
            try {
                String content = org.apache.commons.io.FileUtils.readFileToString(legacyForgeCfg, StandardCharsets.UTF_8);
                String updated = content.replace(
                        "B:alwaysSetupTerrainOffThread=false",
                        "B:alwaysSetupTerrainOffThread=true"
                );
                if (!updated.equals(content)) {
                    org.apache.commons.io.FileUtils.writeStringToFile(legacyForgeCfg, updated, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {}
        }
    }

    /**
     * Tự động đóng gói Resource Pack "SmoothMine-FPS-Pack.zip" chuẩn cấu trúc Minecraft.
     * - Loại bỏ lớp phủ toàn màn hình (pumpkinblur, powder_snow_outline, clouds) gây nghẽn fillrate trên GPU di động.
     * - Làm mảnh hạt mưa/tuyết giảm 95% alpha overdraw nhưng vẫn nhận biết được thời tiết.
     * - Chuyển hướng các hạt khói trại, bụi nước, bào tử Nether sang sprite rỗng 1x1 siêu nhẹ.
     */
    private static boolean generateSmoothMineResourcePack(Context ctx, File gameDir, String versionId, SmoothMode mode) {
        File rpDir = new File(gameDir, "resourcepacks");
        if (!FileUtils.ensureDirectorySilently(rpDir)) return false;

        File zipFile = new File(rpDir, FPS_PACK_FILENAME);
        int packFormat = resolvePackFormat(versionId);

        byte[] transparentPng = createSolidPngBytes(4, 4, Color.argb(0, 0, 0, 0));
        byte[] subtleRainPng = createSubtleWeatherPngBytes(Color.argb(75, 160, 200, 255));
        byte[] subtleSnowPng = createSubtleWeatherPngBytes(Color.argb(85, 240, 248, 255));
        byte[] packIconPng = createPackIconPngBytes(ctx);

        String mcmeta = "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + packFormat + ",\n"
                + "    \"supported_formats\": [1, 99],\n"
                + "    \"description\": \"\\u00a7a\\u00a7lSmoothMine FPS Pack\\n\\u00a77Giam hat, khong mu & tang FPS GPU\"\n"
                + "  }\n"
                + "}\n";

        String emptyParticleJson = "{\n  \"textures\": [\n    \"smoothmine:empty\"\n  ]\n}\n";
        byte[] emptyParticleJsonBytes = emptyParticleJson.getBytes(StandardCharsets.UTF_8);

        List<String> culledParticles = new ArrayList<>(Arrays.asList(
                "campfire_cosy_smoke",
                "campfire_signal_smoke",
                "underwater",
                "ash",
                "white_ash",
                "crimson_spore",
                "warped_spore",
                "spore_blossom_air"
        ));
        if (mode == SmoothMode.ULTRA_FPS) {
            culledParticles.add("mycelium");
            culledParticles.add("rain");
        }

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            putZipEntry(zos, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
            if (packIconPng != null) {
                putZipEntry(zos, "pack.png", packIconPng);
            }

            // Sprite rỗng chuẩn cho hệ thống Particle Atlas 1.14+
            putZipEntry(zos, "assets/smoothmine/textures/particle/empty.png", transparentPng);

            // Loại bỏ Overdraw phủ toàn màn hình và đám mây thừa
            putZipEntry(zos, "assets/minecraft/textures/misc/pumpkinblur.png", transparentPng);
            putZipEntry(zos, "assets/minecraft/textures/misc/powder_snow_outline.png", transparentPng);
            putZipEntry(zos, "assets/minecraft/textures/environment/clouds.png", transparentPng);

            // Làm mảnh hiệu ứng mưa/tuyết để không tụt nửa FPS khi trời mưa
            putZipEntry(zos, "assets/minecraft/textures/environment/rain.png", subtleRainPng);
            putZipEntry(zos, "assets/minecraft/textures/environment/snow.png", subtleSnowPng);

            for (String particleName : culledParticles) {
                putZipEntry(zos, "assets/minecraft/particles/" + particleName + ".json", emptyParticleJsonBytes);
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed generating SmoothMine FPS Resource Pack", e);
            return false;
        }
    }

    private static void removeGeneratedResourcePack(File gameDir) {
        if (gameDir == null) return;
        File zipFile = new File(gameDir, "resourcepacks/" + FPS_PACK_FILENAME);
        if (zipFile.exists()) {
            zipFile.delete();
        }
        enableResourcePackInOptionsOnly(null, gameDir, null, false);
    }

    private static void putZipEntry(ZipOutputStream zos, String entryPath, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(entryPath);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] createSolidPngBytes(int width, int height, int argbColor) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (argbColor != 0) {
            bmp.eraseColor(argbColor);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
        bmp.recycle();
        return baos.toByteArray();
    }

    private static byte[] createSubtleWeatherPngBytes(int dropletColor) {
        Bitmap bmp = Bitmap.createBitmap(8, 32, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.argb(0, 0, 0, 0));
        bmp.setPixel(3, 10, dropletColor);
        bmp.setPixel(3, 11, dropletColor);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
        bmp.recycle();
        return baos.toByteArray();
    }

    private static byte[] createPackIconPngBytes(Context ctx) {
        if (ctx != null) {
            try (InputStream is = ctx.getAssets().open("smoothmine.png")) {
                Bitmap raw = BitmapFactory.decodeStream(is);
                if (raw != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(raw, 64, 64, true);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    if (scaled != raw) scaled.recycle();
                    raw.recycle();
                    return baos.toByteArray();
                }
            } catch (IOException ignored) {}
        }
        Bitmap fallback = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fallback);
        canvas.drawColor(Color.rgb(11, 18, 32));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(16, 185, 129));
        canvas.drawRoundRect(new RectF(8f, 8f, 56f, 56f), 12f, 12f, paint);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        fallback.compress(Bitmap.CompressFormat.PNG, 100, baos);
        fallback.recycle();
        return baos.toByteArray();
    }

    /**
     * Dọn dẹp các file rác chiếm I/O và bộ nhớ trong thư mục game (logs nén cũ, debug.log,
     * bytecode .mixin.out, telemetry, webcache và crash-reports tồn đọng).
     */
    private static void cleanDirectoryJunk(File gameDir, OptimizationResult result) {
        if (gameDir == null || !gameDir.exists()) return;

        // 1. Dọn logs/*.log.gz và debug*.log*
        File logsDir = new File(gameDir, "logs");
        if (logsDir.exists() && logsDir.isDirectory()) {
            File[] logs = logsDir.listFiles();
            if (logs != null) {
                for (File f : logs) {
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    if (f.isFile() && (name.endsWith(".log.gz") || name.startsWith("debug"))) {
                        deleteAndTrack(f, result);
                    }
                }
            }
        }

        // 2. Chỉ giữ lại tối đa 3 crash-reports mới nhất để chẩn đoán lỗi, xóa phần cũ dư thừa
        File crashDir = new File(gameDir, "crash-reports");
        if (crashDir.exists() && crashDir.isDirectory()) {
            File[] crashes = crashDir.listFiles(File::isFile);
            if (crashes != null && crashes.length > 3) {
                Arrays.sort(crashes, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                for (int i = 3; i < crashes.length; i++) {
                    deleteAndTrack(crashes[i], result);
                }
            }
        }

        // 3. Xóa các thư mục dump bytecode & telemetry rác
        String[] junkDirs = new String[]{".mixin.out", "telemetry", "webcache", "webcache2", "logs/telemetry"};
        for (String dirName : junkDirs) {
            File d = new File(gameDir, dirName);
            if (d.exists()) {
                deleteRecursivelyAndTrack(d, result);
            }
        }

        // 4. Dọn các file tải dở .tmp / .part trong mods và shaderpacks
        cleanStaleTempFiles(new File(gameDir, "mods"), result);
        cleanStaleTempFiles(new File(gameDir, "resourcepacks"), result);
        cleanStaleTempFiles(new File(gameDir, "shaderpacks"), result);
    }

    private static void cleanStaleTempFiles(File dir, OptimizationResult result) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".tmp") || lower.endsWith(".part") || lower.endsWith(".download")) {
                    deleteAndTrack(f, result);
                }
            }
        }
    }

    private static void deleteRecursivelyAndTrack(File target, OptimizationResult result) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursivelyAndTrack(child, result);
                }
            }
            target.delete();
        } else {
            deleteAndTrack(target, result);
        }
    }

    private static void deleteAndTrack(File file, OptimizationResult result) {
        long len = file.length();
        if (file.delete()) {
            result.deletedJunkFiles++;
            result.freedBytes += Math.max(0L, len);
        }
    }

    private static LinkedHashMap<String, String> readColonKeyValueFile(Context ctx, File file, String assetSeedName) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (!file.exists() && ctx != null && assetSeedName != null) {
            try (InputStream is = ctx.getAssets().open(assetSeedName)) {
                String content = Tools.read(is);
                if (content != null) {
                    for (String raw : content.split("\n")) {
                        String line = raw.trim();
                        int idx = line.indexOf(':');
                        if (idx > 0) {
                            map.put(line.substring(0, idx), line.substring(idx + 1));
                        }
                    }
                }
            } catch (IOException ignored) {}
        } else if (file.exists() && file.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        map.put(line.substring(0, idx), line.substring(idx + 1));
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed reading " + file.getName(), e);
            }
        }
        return map;
    }

    private static void writeColonKeyValueFile(File file, Map<String, String> map) {
        FileUtils.ensureParentDirectorySilently(file);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            sb.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        writeUtf8Safe(file, sb.toString());
    }

    private static JSONObject readJsonObjectSafe(File file) {
        if (file.exists() && file.isFile()) {
            try {
                String content = org.apache.commons.io.FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                if (content != null && !content.trim().isEmpty()) {
                    return new JSONObject(content);
                }
            } catch (Exception ignored) {}
        }
        return new JSONObject();
    }

    private static JSONArray parseJsonArraySafe(String raw) {
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                return new JSONArray(raw.trim());
            } catch (JSONException ignored) {}
        }
        return new JSONArray();
    }

    private static JSONObject getOrCreateObject(JSONObject parent, String key) throws JSONException {
        JSONObject child = parent.optJSONObject(key);
        if (child == null) {
            child = new JSONObject();
            parent.put(key, child);
        }
        return child;
    }

    private static void writeUtf8Safe(File file, String content) {
        try {
            FileUtils.ensureParentDirectorySilently(file);
            org.apache.commons.io.FileUtils.writeStringToFile(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Failed writing " + file.getAbsolutePath(), e);
        }
    }

    private static boolean isLegacyPre113(String versionId) {
        int[] ver = parseMinorPatch(versionId);
        return ver != null && ver[0] <= 12;
    }

    private static int resolvePackFormat(String versionId) {
        int[] ver = parseMinorPatch(versionId);
        if (ver == null) return 34; // Mặc định hiện đại kèm supported_formats [1, 99]
        int minor = ver[0];
        int patch = ver[1];
        if (minor <= 8) return 1;
        if (minor <= 10) return 2;
        if (minor <= 12) return 3;
        if (minor <= 14) return 4;
        if (minor == 15) return 5;
        if (minor == 16) return patch <= 1 ? 5 : 6;
        if (minor == 17) return 7;
        if (minor == 18) return 8;
        if (minor == 19) {
            if (patch <= 2) return 9;
            if (patch == 3) return 12;
            return 13;
        }
        if (minor == 20) {
            if (patch <= 1) return 15;
            if (patch == 2) return 18;
            if (patch <= 4) return 22;
            return 32;
        }
        if (minor == 21) {
            if (patch <= 1) return 34;
            if (patch <= 3) return 42;
            return 46;
        }
        return 46;
    }

    private static int[] parseMinorPatch(String versionId) {
        if (versionId == null || versionId.contains("latest")) return null;
        Matcher m = MC_VERSION_PATTERN.matcher(versionId);
        while (m.find()) {
            String token = m.group(1);
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                try {
                    int minor = Integer.parseInt(parts[1]);
                    int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                    if (minor >= 6 && minor <= 30) {
                        return new int[]{minor, patch};
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private static MinecraftProfile getSafeCurrentProfile() {
        try {
            LauncherProfiles.load();
            return LauncherProfiles.getCurrentProfile();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
