package net.kdt.pojavlaunch.smooth;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FpsModpackInstaller: Trình cài đặt 1-Chạm gói Mod tăng FPS tối đa (Fabric + Sodium + Lithium + FerriteCore + ImmediatelyFast + EntityCulling).
 */
public final class FpsModpackInstaller {
    private static final String TAG = "FpsModpackInstaller";

    private static final String[][] SUPPORTED_VERSIONS = new String[][]{
            {"1.21.4", "1.21.4 — Mới nhất, hiệu năng cao (Java 21)"},
            {"1.21.1", "1.21.1 — Ổn định, nhiều Server hỗ trợ (Java 21)"},
            {"1.20.4", "1.20.4 — Nhẹ & rất mượt (Java 17)"},
            {"1.20.1", "1.20.1 — Phổ biến nhất cho Sinh tồn / Server (Java 17)"},
            {"1.19.4", "1.19.4 — Mượt mà, tiết kiệm RAM (Java 17)"},
            {"1.18.2", "1.18.2 — Cân bằng cho máy tầm trung (Java 17)"},
            {"1.16.5", "1.16.5 — Siêu nhẹ cho máy RAM 3GB-4GB (Java 8/17)"}
    };

    private static final class ModSpec {
        final String slug;
        final String displayName;
        final boolean required;

        ModSpec(String slug, String displayName, boolean required) {
            this.slug = slug;
            this.displayName = displayName;
            this.required = required;
        }
    }

    private static final ModSpec[] FPS_MODS = new ModSpec[]{
            new ModSpec("fabric-api", "Fabric API", true),
            new ModSpec("sodium", "Sodium (Tăng x3 FPS)", true),
            new ModSpec("lithium", "Lithium (Tối ưu CPU/Tick)", false),
            new ModSpec("ferrite-core", "FerriteCore (Giảm 40% RAM)", false),
            new ModSpec("immediatelyfast", "ImmediatelyFast (Tăng tốc GPU UI)", false),
            new ModSpec("entityculling", "EntityCulling (Ẩn vật thể khuất)", false)
    };

    private FpsModpackInstaller() {}

    public interface OnCompleteCallback {
        void onCompleted(String profileName);
    }

    public static void showInstallDialog(Activity activity, OnCompleteCallback callback) {
        if (ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(activity, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        if (!Tools.isOnline(activity)) {
            Toast.makeText(activity, R.string.multirt_no_internet, Toast.LENGTH_LONG).show();
            return;
        }

        CharSequence[] items = new CharSequence[SUPPORTED_VERSIONS.length];
        for (int i = 0; i < SUPPORTED_VERSIONS.length; i++) {
            items[i] = SUPPORTED_VERSIONS[i][1];
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.smooth_fps_pack_dialog_title)
                .setItems(items, (dialog, which) -> {
                    String mcVersion = SUPPORTED_VERSIONS[which][0];
                    installFpsPackAsync(activity, mcVersion, callback);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void installFpsPackAsync(Activity activity, String mcVersion, OnCompleteCallback callback) {
        Toast.makeText(activity, activity.getString(R.string.smooth_fps_pack_starting, mcVersion), Toast.LENGTH_SHORT).show();

        PojavApplication.sExecutorService.execute(() -> {
            ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 5, R.string.smooth_fps_pack_progress_fabric, mcVersion);
            try {
                // 1. Tải danh sách Fabric Loader và chọn bản stable mới nhất
                FabricVersion[] loaderVersions = FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(mcVersion);
                if (loaderVersions == null || loaderVersions.length == 0) {
                    throw new IOException("Không tìm thấy Fabric Loader cho phiên bản " + mcVersion);
                }
                String chosenLoader = loaderVersions[0].version;
                for (FabricVersion fv : loaderVersions) {
                    if (fv.stable) {
                        chosenLoader = fv.version;
                        break;
                    }
                }

                String fabricJsonUrl = FabriclikeUtils.FABRIC_UTILS.createJsonDownloadUrl(mcVersion, chosenLoader);
                String fabricJson = DownloadUtils.downloadString(fabricJsonUrl);
                String fabricVersionId;
                try {
                    JSONObject obj = new JSONObject(fabricJson);
                    fabricVersionId = obj.getString("id");
                } catch (JSONException e) {
                    throw new IOException("Dữ liệu Fabric JSON không hợp lệ", e);
                }

                File versionJsonDir = new File(Tools.DIR_HOME_VERSION, fabricVersionId);
                FileUtils.ensureDirectory(versionJsonDir);
                File versionJsonFile = new File(versionJsonDir, fabricVersionId + ".json");
                Tools.write(versionJsonFile.getAbsolutePath(), fabricJson);

                // 2. Tạo thư mục instance riêng biệt để tránh xung đột mod giữa các phiên bản
                String instanceFolderName = "smoothboost_" + mcVersion.replace('.', '_');
                File instanceDir = new File(Tools.DIR_GAME_HOME, "custom_instances/" + instanceFolderName);
                File modsDir = new File(instanceDir, "mods");
                FileUtils.ensureDirectory(modsDir);

                // Dọn các bản mod cũ của bộ tối ưu nếu cài đè để tránh trùng 2 phiên bản mod
                cleanExistingBoostMods(modsDir);

                // 3. Tải lần lượt các mod tối ưu từ Modrinth API v2
                List<String> installedModNames = new ArrayList<>();
                for (int i = 0; i < FPS_MODS.length; i++) {
                    ModSpec spec = FPS_MODS[i];
                    int progress = 15 + ((i * 75) / FPS_MODS.length);
                    ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, progress, R.string.smooth_fps_pack_progress_mod, spec.displayName);

                    boolean ok = downloadModFromModrinth(spec.slug, mcVersion, modsDir);
                    if (ok) {
                        installedModNames.add(spec.displayName);
                    } else if (spec.required) {
                        throw new IOException("Không thể tải mod bắt buộc: " + spec.displayName + " (" + mcVersion + ")");
                    }
                }

                // 4. Tạo & cấu hình Profile SmoothBoost
                ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 95, R.string.smooth_fps_pack_progress_finalize);
                String profileName = "⚡ SmoothBoost " + mcVersion;

                LauncherProfiles.load();
                String profileKey = null;
                MinecraftProfile existingProfile = null;
                for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
                    MinecraftProfile p = entry.getValue();
                    if (p != null && profileName.equals(p.name)) {
                        profileKey = entry.getKey();
                        existingProfile = p;
                        break;
                    }
                }
                if (profileKey == null) {
                    profileKey = LauncherProfiles.getFreeProfileKey();
                }

                MinecraftProfile boostProfile = existingProfile != null ? existingProfile : new MinecraftProfile();
                boostProfile.name = profileName;
                boostProfile.lastVersionId = fabricVersionId;
                boostProfile.gameDir = "./custom_instances/" + instanceFolderName;
                boostProfile.icon = "fabric";

                LauncherProfiles.mainProfileJson.profiles.put(profileKey, boostProfile);
                LauncherProfiles.write();

                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                        .apply();

                // 5. Áp dụng trọn bộ tối ưu phần cứng, MobileGlues, JVM Flags, options.txt & sodium-options.json
                SmoothHardwareProfiler.SmoothMode targetMode = SmoothHardwareProfiler.getSavedMode();
                SmoothHardwareProfiler.applyPresetForProfile(activity, targetMode, boostProfile, mcVersion, true);

                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);

                String summaryMods = joinStrings(installedModNames);
                Tools.runOnUiThread(() -> {
                    if (callback != null) {
                        callback.onCompleted(profileName);
                    }
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        new AlertDialog.Builder(activity)
                                .setTitle(R.string.smooth_fps_pack_done_title)
                                .setMessage(activity.getString(R.string.smooth_fps_pack_done_msg, profileName, summaryMods))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                });

            } catch (Throwable e) {
                Log.e(TAG, "Failed to install SmoothBoost FPS Pack", e);
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                Tools.showErrorRemote(activity, R.string.modpack_install_download_failed, e instanceof Exception ? (Exception) e : new RuntimeException(e));
            }
        });
    }

    private static boolean downloadModFromModrinth(String projectSlug, String mcVersion, File modsDir) {
        try {
            String loadersParam = URLEncoder.encode("[\"fabric\"]", "UTF-8");
            String versionsParam = URLEncoder.encode("[\"" + mcVersion + "\"]", "UTF-8");
            String apiUrl = "https://api.modrinth.com/v2/project/" + projectSlug
                    + "/version?loaders=" + loadersParam
                    + "&game_versions=" + versionsParam;

            String jsonResponse = DownloadUtils.downloadString(apiUrl);
            JSONArray versionsArray = new JSONArray(jsonResponse);
            if (versionsArray.length() == 0) {
                Log.w(TAG, "No Modrinth version found for " + projectSlug + " on " + mcVersion);
                return false;
            }

            // Ưu tiên bản release ổn định đầu tiên, nếu không có thì lấy phần tử đầu tiên
            JSONObject chosenVersion = versionsArray.getJSONObject(0);
            for (int i = 0; i < versionsArray.length(); i++) {
                JSONObject candidate = versionsArray.getJSONObject(i);
                if ("release".equalsIgnoreCase(candidate.optString("version_type", "release"))) {
                    chosenVersion = candidate;
                    break;
                }
            }

            JSONArray files = chosenVersion.getJSONArray("files");
            if (files.length() == 0) return false;

            JSONObject primaryFile = files.getJSONObject(0);
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (f.optBoolean("primary", false)) {
                    primaryFile = f;
                    break;
                }
            }

            String downloadUrl = primaryFile.getString("url");
            String fileName = primaryFile.getString("filename");
            File targetJar = new File(modsDir, fileName);
            DownloadUtils.downloadFile(downloadUrl, targetJar);
            return targetJar.exists() && targetJar.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "Could not download optional/required mod " + projectSlug, e);
            return false;
        }
    }

    private static void cleanExistingBoostMods(File modsDir) {
        File[] files = modsDir.listFiles();
        if (files == null) return;
        String[] prefixes = {"fabric-api", "sodium", "lithium", "ferritecore", "immediatelyfast", "entityculling"};
        for (File f : files) {
            String lower = f.getName().toLowerCase();
            if (!lower.endsWith(".jar")) continue;
            for (String p : prefixes) {
                if (lower.contains(p)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    break;
                }
            }
        }
    }

    private static String joinStrings(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
