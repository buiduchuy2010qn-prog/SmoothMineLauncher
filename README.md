# ⚡ SmoothMine Launcher (Android)

**SmoothMine Launcher** là trình khởi chạy **Minecraft: Java Edition** trên điện thoại Android được tùy biến chuyên sâu về **tối ưu FPS, quản lý RAM chống văng game và trải nghiệm 1-Chạm**, phát triển trên nền tảng lõi mã nguồn mở [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) (LGPL-3.0).

## 🚀 Tính năng nổi bật của SmoothMine

1. **SmoothBoost Hardware Engine (`SmoothHardwareProfiler`)**
   - Tự động quét GPU (`Qualcomm Adreno`, `ARM Mali`, `Immortalis`, `Vulkan`) và số nhân CPU để chọn sẵn **Renderer mượt nhất** (`MobileGlues GL 4.x`, `Light-The-Way GLES 3`, `Vulkan Zink/Turnip` hoặc `NG-GL4ES`).
   - Tự động tính toán mức cấp phát RAM thông minh theo tổng RAM & RAM trống thực tế, chừa khoảng thở cho driver đồ họa giúp **chống lỗi Android `lmkd` tự đóng game**.
   - Bật sẵn **Big Core Affinity** (ưu tiên nhân hiệu năng cao của CPU), chế độ kết xuất trực tiếp **SurfaceView** giảm độ trễ cảm ứng, cùng bộ **JVM G1GC Low-Pause Flags**.

2. **3 Chế Độ Hiệu Năng Tức Thì ngay tại Màn Hình Chính**
   - 🔥 **Siêu Mượt FPS**: Tối ưu tỷ lệ độ phân giải thông minh, tắt pipeline check thừa trong MobileGlues, đưa cấu hình `options.txt` về chuẩn PvP/FPS cao.
   - ⚖️ **Cân Bằng**: Mượt 60–120 FPS với tầm nhìn và độ nét hài hòa.
   - ✨ **Đồ Họa Đẹp**: Độ phân giải gốc 100%, bật Compute Shader & DSA cho Shader nhẹ.

3. **1-Chạm Cài Đặt Gói Mod X3 FPS (`FpsModpackInstaller`)**
   - Kết nối trực tiếp **Fabric Meta API** & **Modrinth API v2** để tự động tải và tạo instance riêng biệt (`custom_instances/smoothboost_<version>`) chống xung đột mod giữa các phiên bản (`1.16.5` -> `1.21.4`):
     - `Fabric API` + `Sodium` + `Lithium` + `FerriteCore` + `ImmediatelyFast` + `EntityCulling`
   - Tự động tạo `sodium-options.json` & `sodium-mixins.properties` tối ưu riêng cho GPU di động.

## 📦 Build APK

Dự án tích hợp sẵn GitHub Actions tại `.github/workflows/android.yml` tự động đóng gói JRE và xuất cả hai bản `SmoothMine-Release-APK` và `SmoothMine-Debug-APK`.

## 📜 Giấy phép (License)

Phát hành theo giấy phép **GNU LGPL-3.0**, kế thừa và tuân thủ bản quyền mã nguồn mở từ PojavLauncherTeam & AngelAuraMC/Amethyst-Android.
