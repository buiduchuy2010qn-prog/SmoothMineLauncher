package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.smooth.FpsModpackInstaller;
import net.kdt.pojavlaunch.smooth.GameFileOptimizer;
import net.kdt.pojavlaunch.smooth.SmoothHardwareProfiler;
import net.kdt.pojavlaunch.smooth.SmoothHardwareProfiler.HardwareReport;
import net.kdt.pojavlaunch.smooth.SmoothHardwareProfiler.SmoothMode;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;

    private TextView mTierBadge;
    private TextView mGpuText;
    private TextView mRendererText;
    private TextView mRamText;
    private ProgressBar mRamBar;
    private Button mBtnModeUltra;
    private Button mBtnModeBalanced;
    private Button mBtnModeQuality;
    private Button mBtnDeepFileOpt;

    private final Handler mTelemetryHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTelemetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded() && getContext() != null) {
                refreshSmoothDashboard();
                mTelemetryHandler.postDelayed(this, 3000L);
            }
        }
    };

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        SmoothHardwareProfiler.ensureFirstLaunchAutoTune(requireContext());

        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        // SmoothBoost Dashboard Views
        mTierBadge = view.findViewById(R.id.smooth_tier_badge);
        mGpuText = view.findViewById(R.id.smooth_gpu_text);
        mRendererText = view.findViewById(R.id.smooth_renderer_text);
        mRamText = view.findViewById(R.id.smooth_ram_text);
        mRamBar = view.findViewById(R.id.smooth_ram_bar);
        mBtnModeUltra = view.findViewById(R.id.btn_mode_ultra);
        mBtnModeBalanced = view.findViewById(R.id.btn_mode_balanced);
        mBtnModeQuality = view.findViewById(R.id.btn_mode_quality);
        Button btnInstallFpsPack = view.findViewById(R.id.btn_install_fps_pack);
        mBtnDeepFileOpt = view.findViewById(R.id.btn_deep_file_optimize);
        Button btnSwitchRendererRam = view.findViewById(R.id.btn_switch_renderer_ram);

        if (mBtnModeUltra != null) {
            mBtnModeUltra.setOnClickListener(v -> onSelectSmoothMode(SmoothMode.ULTRA_FPS));
        }
        if (mBtnModeBalanced != null) {
            mBtnModeBalanced.setOnClickListener(v -> onSelectSmoothMode(SmoothMode.BALANCED));
        }
        if (mBtnModeQuality != null) {
            mBtnModeQuality.setOnClickListener(v -> onSelectSmoothMode(SmoothMode.QUALITY));
        }
        if (btnInstallFpsPack != null) {
            btnInstallFpsPack.setOnClickListener(v -> FpsModpackInstaller.showInstallDialog(requireActivity(), profileName -> {
                if (isAdded()) {
                    mVersionSpinner.reloadProfiles();
                    refreshSmoothDashboard();
                }
            }));
        }
        if (mBtnDeepFileOpt != null) {
            mBtnDeepFileOpt.setOnClickListener(v -> GameFileOptimizer.showOptimizerDialog(requireActivity(), () -> {
                if (isAdded()) {
                    refreshSmoothDashboard();
                }
            }));
        }
        if (btnSwitchRendererRam != null) {
            btnSwitchRendererRam.setOnClickListener(v -> showRendererAndRamDialog());
        }

        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        if (hasOnlineProfile()) {
            mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
            mInstallJarButton.setOnLongClickListener(v -> {
                runInstallerWithConfirmation(true);
                return true;
            });
        } else {
            mInstallJarButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));
        }
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v -> {
            System.gc();
            ensureSodiumCompatibleBeforeLaunch();
            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });

        mShareLogsButton.setOnClickListener(v -> shareLog(requireContext()));

        mOpenDirectoryButton.setOnClickListener(v -> {
            if (Tools.isDemoProfile(v.getContext())) {
                hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
            } else if (!hasOnlineProfile()) {
                hasNoOnlineProfileDialog(requireActivity());
            } else {
                openPath(v.getContext(), getCurrentProfileDirectory(), false);
            }
        });

        mNewsButton.setOnLongClickListener(v -> {
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });

        refreshSmoothDashboard();
    }

    private void onSelectSmoothMode(SmoothMode mode) {
        Context ctx = requireContext();
        HardwareReport report = SmoothHardwareProfiler.applyPreset(ctx, mode);
        refreshSmoothDashboard();
        Toast.makeText(
                ctx,
                getString(R.string.smooth_toast_preset_applied, mode.displayName, report.allocatedRamMb, report.resolutionScale),
                Toast.LENGTH_SHORT
        ).show();
    }

    private void showRendererAndRamDialog() {
        Context ctx = requireContext();
        Tools.RenderersList renderersList = Tools.getCompatibleRenderers(ctx);
        List<String> ids = renderersList.rendererIds;
        CharSequence[] labels = new CharSequence[ids.size()];

        String currentRenderer = "opengles_mobileglues";
        MinecraftProfile currentProfile = null;
        try {
            LauncherProfiles.load();
            currentProfile = LauncherProfiles.getCurrentProfile();
            if (Tools.isValidString(currentProfile.pojavRendererName)) {
                currentRenderer = currentProfile.pojavRendererName;
            }
        } catch (Throwable ignored) {}

        int checkedItem = 0;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            labels[i] = SmoothHardwareProfiler.getFriendlyRendererName(id);
            if (id.equals(currentRenderer)) {
                checkedItem = i;
            }
        }

        final MinecraftProfile finalProfile = currentProfile;
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.smooth_renderer_picker_title)
                .setSingleChoiceItems(labels, checkedItem, (dialog, which) -> {
                    String chosenId = ids.get(which);
                    if (finalProfile != null) {
                        finalProfile.pojavRendererName = chosenId;
                        LauncherProfiles.write();
                    }
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putBoolean("sodium_override", !"opengles2".equals(chosenId))
                            .apply();
                    refreshSmoothDashboard();
                    dialog.dismiss();
                })
                .setNeutralButton("🧹 Dọn RAM Ngay", (dialog, which) -> {
                    System.gc();
                    HardwareReport rep = SmoothHardwareProfiler.applyPreset(ctx, SmoothHardwareProfiler.getSavedMode());
                    refreshSmoothDashboard();
                    Toast.makeText(ctx, getString(R.string.smooth_toast_ram_cleaned, rep.freeRamMb), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void ensureSodiumCompatibleBeforeLaunch() {
        try {
            if (!Tools.hasMods("sodium", "embeddium", "rubidium", "xenon")) return;
            LauncherProfiles.load();
            MinecraftProfile profile = LauncherProfiles.getCurrentProfile();
            String rdr = profile.pojavRendererName;
            if (rdr == null || "opengles2".equals(rdr)) {
                profile.pojavRendererName = "opengles_mobileglues";
                LauncherProfiles.write();
                Toast.makeText(requireContext(), R.string.smooth_sodium_auto_fixed, Toast.LENGTH_SHORT).show();
            }
            LauncherPreferences.DEFAULT_PREF.edit().putBoolean("sodium_override", true).apply();
        } catch (Throwable ignored) {}
    }

    private void refreshSmoothDashboard() {
        if (!isAdded() || getContext() == null || mGpuText == null) return;
        HardwareReport report = SmoothHardwareProfiler.getHardwareReport(getContext());

        if (mTierBadge != null) {
            mTierBadge.setText(report.deviceTierLabel);
        }
        mGpuText.setText(String.format(
                Locale.US,
                "GPU: %s (GLES %d%s) • %d Nhân CPU",
                report.gpuRenderer,
                report.glesMajor,
                report.hasVulkan ? " + Vulkan" : "",
                report.cpuCores
        ));
        if (mRendererText != null) {
            mRendererText.setText(String.format(
                    Locale.US,
                    "Renderer: %s • Độ nét: %d%%",
                    report.recommendedRendererName,
                    report.resolutionScale
            ));
        }
        if (mRamText != null) {
            mRamText.setText(String.format(
                    Locale.US,
                    "RAM Trống: %d / %d MB • Cấp cho Game: %d MB",
                    report.freeRamMb,
                    report.totalRamMb,
                    report.allocatedRamMb
            ));
        }
        if (mRamBar != null && report.totalRamMb > 0) {
            int usedPercent = Math.min(100, Math.max(5, ((report.totalRamMb - report.freeRamMb) * 100) / report.totalRamMb));
            mRamBar.setProgress(usedPercent);
        }

        updateModeChipStyle(mBtnModeUltra, report.currentMode == SmoothMode.ULTRA_FPS);
        updateModeChipStyle(mBtnModeBalanced, report.currentMode == SmoothMode.BALANCED);
        updateModeChipStyle(mBtnModeQuality, report.currentMode == SmoothMode.QUALITY);
        if (mBtnDeepFileOpt != null) {
            boolean deepActive = GameFileOptimizer.isDeepOptimizationActive();
            mBtnDeepFileOpt.setText(deepActive ? R.string.smooth_btn_file_opt_active : R.string.smooth_btn_file_opt_idle);
        }
    }

    private void updateModeChipStyle(Button btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundResource(active ? R.drawable.bg_smooth_chip_active : R.drawable.bg_smooth_chip_idle);
        btn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if (!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if (profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
        mTelemetryHandler.removeCallbacks(mTelemetryRunnable);
        mTelemetryHandler.post(mTelemetryRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        mTelemetryHandler.removeCallbacks(mTelemetryRunnable);
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0) {
            Tools.installMod(requireActivity(), isCustomArgs);
        } else {
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
        }
    }
}
