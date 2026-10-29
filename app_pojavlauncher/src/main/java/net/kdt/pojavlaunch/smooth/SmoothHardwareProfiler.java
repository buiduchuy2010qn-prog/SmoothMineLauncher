package net.kdt.pojavlaunch.smooth;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.GLInfoUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SmoothHardwareProfiler: Bộ phân tích phần cứng & tự động tinh chỉnh FPS/RAM cho SmoothMine Launcher.
 */
public final class SmoothHardwareProfiler {
    private static final String TAG = "SmoothProfiler";
    public static final String PREF_SMOOTH_MODE = "smooth_boost_mode";
    public static final String PREF_AUTO_TUNE_ON_LAUNCH = "smooth_auto_tune_launch";
    public static final String PREF_FIRST_AUTO_TUNED = "smooth_first_auto_tuned";

    public enum SmoothMode {
        ULTRA_FPS("ULTRA_FPS", "Siêu Mượt FPS", "Ưu tiên FPS tối đa, giảm tải GPU & chống khựng"),
        BALANCED("BALANCED", "Cân Bằng", "Mượt 60-120 FPS, giữ độ nét và tầm nhìn tốt"),
        QUALITY("QUALITY", "Đồ Họa Đẹp", "Độ phân giải 100%, sẵn sàng cho Shader nhẹ");

        public final String id;
        public final String displayName;
        public final String summary;

        SmoothMode(String id, String displayName, String summary) {
            this.id = id;
            this.displayName = displayName;
            this.summary = summary;
        }

        public static SmoothMode fromId(String id) {
            if (id == null) return BALANCED;
            for (SmoothMode m : values()) {
                if (m.id.equalsIgnoreCase(id)) return m;
            }
            return BALANCED;
        }
    }

    public static final class HardwareReport {
        public final String gpuVendor;
        public final String gpuRenderer;
        public final int glesMajor;
        public final int cpuCores;
        public final boolean hasVulkan;
        public final boolean isAdreno;
        public final boolean isMali;
        public final int totalRamMb;
        public final int freeRamMb;
        public final int allocatedRamMb;
        public final int resolutionScale;
        public final String recommendedRendererId;
        public final String recommendedRendererName;
        public final String deviceTierLabel;
        public final SmoothMode currentMode;

        public HardwareReport(
                String gpuVendor,
                String gpuRenderer,
                int glesMajor,
                int cpuCores,
                boolean hasVulkan,
                boolean isAdreno,
                boolean isMali,
                int totalRamMb,
                int freeRamMb,
                int allocatedRamMb,
                int resolutionScale,
                String recommendedRendererId,
                String recommendedRendererName,
                String deviceTierLabel,
                SmoothMode currentMode
        ) {
            this.gpuVendor = gpuVendor;
            this.gpuRenderer = gpuRenderer;
            this.glesMajor = glesMajor;
            this.cpuCores = cpuCores;
            this.hasVulkan = hasVulkan;
            this.isAdreno = isAdreno;
            this.isMali = isMali;
            this.totalRamMb = totalRamMb;
            this.freeRamMb = freeRamMb;
            this.allocatedRamMb = allocatedRamMb;
            this.resolutionScale = resolutionScale;
            this.recommendedRendererId = recommendedRendererId;
            this.recommendedRendererName = recommendedRendererName;
            this.deviceTierLabel = deviceTierLabel;
            this.currentMode = currentMode;
        }
    }

    private SmoothHardwareProfiler() {}

    public static SmoothMode getSavedMode() {
        if (LauncherPreferences.DEFAULT_PREF == null) return SmoothMode.BALANCED;
        return SmoothMode.fromId(LauncherPreferences.DEFAULT_PREF.getString(PREF_SMOOTH_MODE, SmoothMode.BALANCED.id));
    }

    public static HardwareReport getHardwareReport(Context ctx) {
        GLInfoUtils.GLInfo glInfo = GLInfoUtils.getGlInfo();
        String vendor = glInfo.vendor != null ? glInfo.vendor : "Unknown";
        String renderer = glInfo.renderer != null ? glInfo.renderer : "Mobile GPU";
        int glesMajor = glInfo.glesMajorVersion;
        int cpuCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        boolean hasVulkan = Tools.checkVulkanSupport(ctx.getPackageManager());
        String lowerRenderer = renderer.toLowerCase(Locale.ROOT);
        boolean isAdreno = glInfo.isAdreno() || lowerRenderer.contains("adreno");
        boolean isMali = lowerRenderer.contains("mali") || lowerRenderer.contains("immortalis");

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.getMemoryInfo(memInfo);
        }
        int totalRamMb = memInfo.totalMem > 0 ? (int) (memInfo.totalMem / (1024L * 1024L)) : Tools.getTotalDeviceMemory(ctx);
        int freeRamMb = memInfo.availMem > 0 ? (int) (memInfo.availMem / (1024L * 1024L)) : Tools.getFreeDeviceMemory(ctx);

        MinecraftProfile currentProfile = getSafeCurrentProfile();
        String versionId = currentProfile != null ? currentProfile.lastVersionId : "latest-release";
        boolean hasSodium = safeHasSodium();

        String bestRenderer = pickOptimalRenderer(ctx, glesMajor, isAdreno, versionId, hasSodium);
        String activeRenderer = (currentProfile != null && Tools.isValidString(currentProfile.pojavRendererName))
                ? currentProfile.pojavRendererName
                : bestRenderer;

        SmoothMode mode = getSavedMode();
        int allocatedRam = LauncherPreferences.DEFAULT_PREF != null
                ? LauncherPreferences.DEFAULT_PREF.getInt("allocation", LauncherPreferences.PREF_RAM_ALLOCATION)
                : computeOptimalRamMb(totalRamMb, freeRamMb, mode, hasSodium);
        int resScale = LauncherPreferences.DEFAULT_PREF != null
                ? LauncherPreferences.DEFAULT_PREF.getInt("resolutionRatio", (int) (LauncherPreferences.PREF_SCALE_FACTOR * 100f))
                : computeResolutionScale(ctx, mode, totalRamMb);

        return new HardwareReport(
                vendor,
                cleanGpuName(renderer),
                glesMajor,
                cpuCores,
                hasVulkan,
                isAdreno,
                isMali,
                totalRamMb,
                freeRamMb,
                allocatedRam,
                resScale,
                activeRenderer,
                getFriendlyRendererName(activeRenderer),
                classifyDeviceTier(totalRamMb, cpuCores),
                mode
        );
    }

    public static String cleanGpuName(String rawRenderer) {
        if (rawRenderer == null || rawRenderer.trim().isEmpty() || rawRenderer.contains("<Unknown>")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != null && !Build.SOC_MODEL.isEmpty()) {
                return Build.SOC_MODEL;
            }
            return Build.HARDWARE != null ? Build.HARDWARE : "Android GPU";
        }
        return rawRenderer.replace("(TM)", "").replace("(R)", "").replaceAll("\\s+", " ").trim();
    }

    public static String getFriendlyRendererName(String rendererId) {
        if (rendererId == null) return "MobileGlues (GL 4.x)";
        switch (rendererId) {
            case "opengles_mobileglues":
                return "MobileGlues (Tăng FPS + Sodium)";
            case "opengles3_ltw":
                return "Light-The-Way (GLES 3 Mượt)";
            case "opengles3_desktopgl_zink_kopper":
                return "Vulkan Zink / Turnip";
            case "vulkan_zink":
                return "OSMesa Zink";
            case "opengles2":
                return "NG-GL4ES (Nhẹ cho bản cũ)";
            default:
                return rendererId;
        }
    }

    private static String classifyDeviceTier(int totalRamMb, int cores) {
        if (totalRamMb >= 7500 && cores >= 8) return "Máy Mạnh";
        if (totalRamMb >= 5200) return "Tầm Trung";
        if (totalRamMb >= 3500) return "Phổ Thông";
        return "Máy Yếu / Tiết Kiệm RAM";
    }

    /**
     * Tự động chọn Renderer mượt nhất dựa trên GPU, phiên bản Minecraft và sự hiện diện của Sodium.
     */
    public static String pickOptimalRenderer(Context ctx, int glesMajor, boolean isAdreno, String versionId, boolean hasSodium) {
        List<String> compatible;
        try {
            compatible = Tools.getCompatibleRenderers(ctx).rendererIds;
        } catch (Throwable t) {
            Log.w(TAG, "Fallback renderer list", t);
            return glesMajor >= 3 ? "opengles_mobileglues" : "opengles2";
        }

        boolean isLegacyVersion = isLegacyOpenGl2Version(versionId);

        // Nếu dùng Sodium hoặc bản mới 1.17+, MobileGlues & LTW cho FPS cao vượt trội và tương thích Core Profile
        if (hasSodium || !isLegacyVersion) {
            if (glesMajor >= 3 && compatible.contains("opengles_mobileglues")) {
                return "opengles_mobileglues";
            }
            if (glesMajor >= 3 && compatible.contains("opengles3_ltw")) {
                return "opengles3_ltw";
            }
            if (isAdreno && compatible.contains("opengles3_desktopgl_zink_kopper")) {
                return "opengles3_desktopgl_zink_kopper";
            }
        } else {
            // Các bản cũ (1.7.10 - 1.12.2 - 1.16.5 không Sodium): NG-GL4ES hoặc LTW cực nhẹ RAM
            if (compatible.contains("opengles2")) {
                return "opengles2";
            }
            if (glesMajor >= 3 && compatible.contains("opengles3_ltw")) {
                return "opengles3_ltw";
            }
        }

        if (compatible.contains("opengles_mobileglues")) return "opengles_mobileglues";
        if (!compatible.isEmpty()) return compatible.get(0);
        return "opengles2";
    }

    private static boolean isLegacyOpenGl2Version(String versionId) {
        if (versionId == null) return false;
        String v = versionId.toLowerCase(Locale.ROOT);
        if (v.contains("latest")) return false;
        // Check 1.7 .. 1.16
        for (int minor = 7; minor <= 16; minor++) {
            if (v.startsWith("1." + minor + ".") || v.equals("1." + minor)
                    || v.contains("-1." + minor + ".") || v.endsWith("-1." + minor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tính mức cấp phát RAM chuẩn vàng: đủ lớn để GC không khựng, nhưng chừa khoảng thở cho Android & driver GPU để không văng game.
     */
    public static int computeOptimalRamMb(int totalRamMb, int freeRamMb, SmoothMode mode, boolean modded) {
        if (Architecture.is32BitsDevice()) {
            return 696;
        }
        int target;
        if (totalRamMb < 3000) {
            target = 896;
        } else if (totalRamMb < 4300) {
            target = modded ? 1344 : 1184;
        } else if (totalRamMb < 6400) {
            target = modded ? 2048 : 1792;
        } else if (totalRamMb < 8600) {
            target = modded ? 2944 : 2496;
        } else if (totalRamMb < 12500) {
            target = modded ? 3840 : 3200;
        } else {
            target = modded ? 4608 : 3840;
        }

        if (mode == SmoothMode.ULTRA_FPS && target > 1536 && !modded) {
            // Heap gọn hơn giúp chu kỳ G1GC ngắn hơn, giảm micro-stutter
            target -= 128;
        }

        // Chừa tối thiểu ~320MB trên RAM trống hiện tại để tránh hộp thoại cảnh báo và tránh lmkd của Android kill tiến trình
        if (freeRamMb > 900) {
            int safeCeiling = freeRamMb - 320;
            if (target > safeCeiling) {
                target = Math.max(768, safeCeiling);
            }
        }

        // Làm tròn theo bước 8MB của thanh trượt bộ nhớ
        target = (target / 8) * 8;
        return Math.max(512, target);
    }

    public static int computeResolutionScale(Context ctx, SmoothMode mode, int totalRamMb) {
        DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int targetScale;
        switch (mode) {
            case ULTRA_FPS:
                if (shortSide >= 1200) targetScale = 65;
                else if (shortSide >= 1000) targetScale = 75;
                else targetScale = 85;
                if (totalRamMb < 4200) targetScale = Math.min(targetScale, 70);
                break;
            case QUALITY:
                targetScale = shortSide > 1440 ? 90 : 100;
                break;
            case BALANCED:
            default:
                if (shortSide >= 1200) targetScale = 80;
                else if (shortSide >= 1000) targetScale = 85;
                else targetScale = 95;
                break;
        }
        return (targetScale / 5) * 5;
    }

    public static String buildOptimalJvmArgs(SmoothMode mode) {
        int maxPauseMs = (mode == SmoothMode.ULTRA_FPS) ? 35 : 45;
        return "-XX:+UnlockExperimentalVMOptions "
                + "-XX:+UseG1GC "
                + "-XX:+ParallelRefProcEnabled "
                + "-XX:MaxGCPauseMillis=" + maxPauseMs + " "
                + "-XX:+DisableExplicitGC "
                + "-XX:G1NewSizePercent=20 "
                + "-XX:G1ReservePercent=15 "
                + "-XX:G1HeapRegionSize=16M "
                + "-XX:G1MixedGCCountTarget=4 "
                + "-XX:InitiatingHeapOccupancyPercent=20 "
                + "-Dfml.readTimeout=90";
    }

    /**
     * Áp dụng toàn diện cấu hình SmoothBoost cho cả Launcher, Renderer, JVM và options.txt của Minecraft.
     */
    public static HardwareReport applyPreset(Context ctx, SmoothMode mode) {
        return applyPresetForProfile(ctx, mode, null, null, null);
    }

    public static HardwareReport applyPresetForProfile(
            Context ctx,
            SmoothMode mode,
            MinecraftProfile explicitProfile,
            String explicitMcVersion,
            Boolean forceModdedFlag
    ) {
        Tools.initStorageConstants(ctx);
        GLInfoUtils.GLInfo glInfo = GLInfoUtils.getGlInfo();
        String rendererRaw = glInfo.renderer != null ? glInfo.renderer.toLowerCase(Locale.ROOT) : "";
        boolean isAdreno = glInfo.isAdreno() || rendererRaw.contains("adreno");

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) am.getMemoryInfo(memInfo);
        int totalRamMb = memInfo.totalMem > 0 ? (int) (memInfo.totalMem / (1024L * 1024L)) : Tools.getTotalDeviceMemory(ctx);
        int freeRamMb = memInfo.availMem > 0 ? (int) (memInfo.availMem / (1024L * 1024L)) : Tools.getFreeDeviceMemory(ctx);

        LauncherProfiles.load();
        MinecraftProfile profile = explicitProfile != null ? explicitProfile : getSafeCurrentProfile();
        String versionId = explicitMcVersion != null
                ? explicitMcVersion
                : (profile != null && profile.lastVersionId != null ? profile.lastVersionId : "latest-release");

        boolean hasSodium = (forceModdedFlag != null && forceModdedFlag) || safeHasSodium();
        boolean isModded = hasSodium
                || versionId.toLowerCase(Locale.ROOT).contains("fabric")
                || versionId.toLowerCase(Locale.ROOT).contains("forge")
                || versionId.toLowerCase(Locale.ROOT).contains("quilt");

        int optimalRam = computeOptimalRamMb(totalRamMb, freeRamMb, mode, isModded);
        int resScale = computeResolutionScale(ctx, mode, totalRamMb);
        String optimalRenderer = pickOptimalRenderer(ctx, glInfo.glesMajorVersion, isAdreno, versionId, hasSodium);
        String jvmArgs = buildOptimalJvmArgs(mode);

        boolean supportsSodiumRenderer = !"opengles2".equals(optimalRenderer);

        SharedPreferences.Editor editor = LauncherPreferences.DEFAULT_PREF.edit();
        editor.putString(PREF_SMOOTH_MODE, mode.id);
        editor.putBoolean(PREF_FIRST_AUTO_TUNED, true);
        editor.putInt("allocation", optimalRam);
        editor.putInt("resolutionRatio", resScale);
        editor.putBoolean("bigCoreAffinity", true);
        editor.putBoolean("alternate_surface", true);
        editor.putBoolean("force_vsync", false);
        editor.putBoolean("sustainedPerformance", false);
        editor.putBoolean("sodium_override", supportsSodiumRenderer);
        editor.putString("javaArgs", jvmArgs);

        // Tinh chỉnh sâu cho MobileGlues để giảm nghẽn cổ chai driver GLES
        editor.putString("mg_renderer_setting_glsl_cache_size", "256");
        editor.putString("mg_renderer_setting_errorSetting", mode == SmoothMode.QUALITY ? "0" : "2");
        editor.putBoolean("mg_renderer_dsaExt", true);
        editor.putBoolean("mg_renderer_computeShaderext", mode == SmoothMode.QUALITY);
        editor.apply();

        LauncherPreferences.loadPreferences(ctx);

        if (profile != null) {
            profile.pojavRendererName = optimalRenderer;
            profile.javaArgs = jvmArgs;
            LauncherProfiles.write();
            File gameDir = Tools.getGameDirPath(profile);
            optimizeMinecraftOptions(ctx, gameDir, mode);
            writeOptimizedSodiumConfigs(gameDir, mode);
        } else {
            optimizeMinecraftOptions(ctx, new File(Tools.DIR_GAME_NEW), mode);
        }

        // Thu gom rác bộ nhớ của Launcher trước khi vào game
        System.gc();
        return getHardwareReport(ctx);
    }

    /**
     * Tự động chạy tối ưu lần đầu tiên mở app để người mới cài vào là mượt ngay lập tức.
     */
    public static void ensureFirstLaunchAutoTune(Context ctx) {
        if (LauncherPreferences.DEFAULT_PREF == null) return;
        if (!LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_FIRST_AUTO_TUNED, false)) {
            try {
                applyPreset(ctx, SmoothMode.BALANCED);
            } catch (Throwable t) {
                Log.w(TAG, "First launch auto-tune skipped", t);
            }
        }
    }

    /**
     * Tối ưu file options.txt của Minecraft nhưng giữ nguyên phím bấm & âm lượng của người chơi.
     */
    public static void optimizeMinecraftOptions(Context ctx, File gameDir, SmoothMode mode) {
        if (gameDir == null) return;
        FileUtils.ensureDirectory(gameDir);
        File optionsFile = new File(gameDir, "options.txt");

        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (!optionsFile.exists() && ctx != null) {
            try (InputStream is = ctx.getAssets().open("options.txt")) {
                String defaultContent = Tools.read(is);
                parseOptionsIntoMap(defaultContent, map);
            } catch (IOException ignored) {}
        } else if (optionsFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(optionsFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        map.put(line.substring(0, idx), line.substring(idx + 1));
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Could not read existing options.txt", e);
            }
        }

        switch (mode) {
            case ULTRA_FPS:
                map.put("renderDistance", "6");
                map.put("simulationDistance", "5");
                map.put("maxFps", "260");
                map.put("enableVsync", "false");
                map.put("fancyGraphics", "false");
                map.put("graphicsMode", "0");
                map.put("ao", "0");
                map.put("clouds", "false");
                map.put("cloudRenderMode", "\"off\"");
                map.put("particles", "2");
                map.put("mipmapLevels", "0");
                map.put("biomeBlendRadius", "0");
                map.put("entityShadows", "false");
                map.put("entityDistanceScaling", "0.6");
                map.put("prioritizeChunkUpdates", "0");
                map.put("bobView", "false");
                break;
            case QUALITY:
                map.put("renderDistance", "10");
                map.put("simulationDistance", "8");
                map.put("maxFps", "120");
                map.put("enableVsync", "false");
                map.put("fancyGraphics", "true");
                map.put("graphicsMode", "1");
                map.put("ao", "2");
                map.put("clouds", "true");
                map.put("cloudRenderMode", "\"fast\"");
                map.put("particles", "1");
                map.put("mipmapLevels", "2");
                map.put("biomeBlendRadius", "2");
                map.put("entityShadows", "true");
                map.put("entityDistanceScaling", "1.0");
                break;
            case BALANCED:
            default:
                map.put("renderDistance", "8");
                map.put("simulationDistance", "6");
                map.put("maxFps", "120");
                map.put("enableVsync", "false");
                map.put("fancyGraphics", "false");
                map.put("graphicsMode", "0");
                map.put("ao", "1");
                map.put("clouds", "false");
                map.put("cloudRenderMode", "\"off\"");
                map.put("particles", "1");
                map.put("mipmapLevels", "0");
                map.put("biomeBlendRadius", "1");
                map.put("entityShadows", "false");
                map.put("entityDistanceScaling", "0.75");
                map.put("prioritizeChunkUpdates", "0");
                break;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            sb.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        try {
            Tools.write(optionsFile.getAbsolutePath(), sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "Failed writing optimized options.txt", e);
        }
    }

    /**
     * Ghi sẵn cấu hình Sodium tối ưu cho GPU điện thoại (chỉ animate khối đang nhìn thấy, bật face/fog/entity culling).
     */
    public static void writeOptimizedSodiumConfigs(File gameDir, SmoothMode mode) {
        if (gameDir == null) return;
        File configDir = new File(gameDir, "config");
        FileUtils.ensureDirectory(configDir);

        File mixinProps = new File(configDir, "sodium-mixins.properties");
        String mixinContent = "mixin.features.buffer_builder.intrinsics=false\n"
                + "mixin.features.chunk_rendering=false\n";
        try {
            Tools.write(mixinProps.getAbsolutePath(), mixinContent);
        } catch (IOException ignored) {}

        File sodiumOpts = new File(configDir, "sodium-options.json");
        if (!sodiumOpts.exists()) {
            String json = "{\n"
                    + "  \"quality\": {\n"
                    + "    \"weather_quality\": \"" + (mode == SmoothMode.QUALITY ? "DEFAULT" : "FAST") + "\",\n"
                    + "    \"leaves_quality\": \"" + (mode == SmoothMode.QUALITY ? "DEFAULT" : "FAST") + "\",\n"
                    + "    \"enable_vignette\": false\n"
                    + "  },\n"
                    + "  \"performance\": {\n"
                    + "    \"chunk_builder_threads\": 0,\n"
                    + "    \"always_defer_chunk_updates\": true,\n"
                    + "    \"animate_only_visible_textures\": true,\n"
                    + "    \"use_entity_culling\": true,\n"
                    + "    \"use_fog_occlusion\": true,\n"
                    + "    \"use_block_face_culling\": true,\n"
                    + "    \"use_no_error_gl_context\": true\n"
                    + "  },\n"
                    + "  \"advanced\": {\n"
                    + "    \"cpu_render_ahead_limit\": 2\n"
                    + "  }\n"
                    + "}\n";
            try {
                org.apache.commons.io.FileUtils.writeStringToFile(sodiumOpts, json, StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
    }

    private static void parseOptionsIntoMap(String content, Map<String, String> map) {
        if (content == null) return;
        String[] lines = content.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            int idx = line.indexOf(':');
            if (idx > 0) {
                map.put(line.substring(0, idx), line.substring(idx + 1));
            }
        }
    }

    private static MinecraftProfile getSafeCurrentProfile() {
        try {
            LauncherProfiles.load();
            return LauncherProfiles.getCurrentProfile();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean safeHasSodium() {
        try {
            return Tools.hasMods("sodium", "embeddium", "rubidium", "xenon");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
