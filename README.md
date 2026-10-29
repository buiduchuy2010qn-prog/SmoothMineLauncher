# ⚡ SmoothMine Launcher (Android)

<img src="app_pojavlauncher/src/main/assets/smoothmine.png" align="left" width="128" height="128" alt="SmoothMine 3D Logo">

[![SmoothMine Android Build](https://github.com/buiduchuy2010qn-prog/SmoothMineLauncher/actions/workflows/android.yml/badge.svg)](https://github.com/buiduchuy2010qn-prog/SmoothMineLauncher/actions/workflows/android.yml)
[![Tải APK Mới Nhất](https://img.shields.io/github/v/release/buiduchuy2010qn-prog/SmoothMineLauncher?label=T%E1%BA%A3i%20APK&color=10B981)](https://github.com/buiduchuy2010qn-prog/SmoothMineLauncher/releases/latest)

**SmoothMine Launcher** là trình khởi chạy **Minecraft: Java Edition** cho Android được thiết kế để **phát triển, tùy biến và tự động đóng gói APK 100% trên đám mây GitHub**, xây dựng trên lõi mã nguồn mở `Amethyst-Android` (LGPL-3.0).

👉 **[Tải ngay bản APK mới nhất tại mục Releases](https://github.com/buiduchuy2010qn-prog/SmoothMineLauncher/releases/latest)**

---

## 🚀 Tính năng tối ưu hiệu năng độc quyền

1. **SmoothBoost Hardware Engine (`SmoothHardwareProfiler.java`)**
   - Tự quét GPU (`Qualcomm Adreno`, `ARM Mali`, `Immortalis`, `Vulkan`) & tổng/trống RAM thực tế.
   - Tự động chọn Renderer tối ưu (`MobileGlues GL 4.x`, `Light-The-Way GLES 3`, `Vulkan Zink/Turnip`, `NG-GL4ES`).
   - Tính ngưỡng RAM an toàn chống lỗi hệ thống Android `lmkd` tự tắt game + bật sẵn **Big Core Affinity**, **SurfaceView** trực tiếp và **JVM G1GC Low-Pause**.
2. **3 Chế Độ Hiệu Năng Tức Thì trên Màn Hình Chính**
   - 🔥 **Siêu Mượt FPS** • ⚖️ **Cân Bằng** • ✨ **Đồ Họa Đẹp**
3. **1-Chạm Cài Đặt Gói Mod X3 FPS (`FpsModpackInstaller.java`)**
   - Tự tải **Fabric Loader** + `Fabric API`, `Sodium`, `Lithium`, `FerriteCore`, `ImmediatelyFast`, `EntityCulling` từ Modrinth API v2 vào thư mục riêng `custom_instances/smoothboost_<version>` (`1.16.5` → `1.21.4`).

---

## 🛠️ Hướng dẫn tự chỉnh sửa & Build APK 100% trên Web GitHub (Không cần PC mạnh)

Bạn không cần cài Android Studio hay SDK nặng máy — mọi thao tác sửa giao diện, thêm tính năng và xuất file `.apk` đều làm trực tiếp trên GitHub:

### Bước 1: Mở trình sửa code ngay trên trình duyệt
- Truy cập repo **https://github.com/buiduchuy2010qn-prog/SmoothMineLauncher** và nhấn phím **`.` (dấu chấm)** trên bàn phím (hoặc đổi `.com` thành **`.dev`**: `https://github.dev/buiduchuy2010qn-prog/SmoothMineLauncher`) để mở **VS Code Web** miễn phí.
- Hoặc bấm nút xanh **`<> Code` → tab `Codespaces` → `Create codespace`** nếu muốn có cả máy ảo đám mây cài sẵn Java 21.

### Bước 2: Các file quan trọng để bạn tùy biến thêm
| Bạn muốn chỉnh gì? | Đường dẫn file trên GitHub |
|---|---|
| **Bảng màu giao diện & Nút bấm** | `app_pojavlauncher/src/main/res/values/colors.xml` & `res/drawable/bg_smooth_*.xml` |
| **Chữ hiển thị Tiếng Việt** | `app_pojavlauncher/src/main/res/values/smooth_strings.xml` |
| **Bố cục Bảng điều khiển SmoothBoost** | `app_pojavlauncher/src/main/res/layout/view_smoothboost_card.xml` |
| **Logic màn hình chính** | `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/fragments/MainMenuFragment.java` |
| **Thuật toán chia RAM, JVM Flags & GPU** | `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/smooth/SmoothHardwareProfiler.java` |
| **Danh sách Mod tăng FPS tự động tải** | `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/smooth/FpsModpackInstaller.java` |
| **Tên ứng dụng & Version** | `app_pojavlauncher/build.gradle` |

### Bước 3: Xuất file APK & Tạo trang tải Release mới tự động
- **Cách 1 (Tự động mỗi khi Commit):** Cứ bấm **Commit & Push** trên GitHub Web là tab **Actions** sẽ tự động chạy biên dịch C++/NDK và trả ra file `SmoothMine-Release-APK` sau ~4–5 phút.
- **Cách 2 (Phát hành bản Release chính thức 1-Chạm):**
  1. Vào tab **[Actions](https://github.com/buiduchuy2010qn-prog/SmoothMineLauncher/actions/workflows/android.yml)** trên GitHub.
  2. Bấm nút **Run workflow**.
  3. Tích vào ô **Tự động tạo trang tải GitHub Release mới**, nhập mã phiên bản (ví dụ `v1.0.1`), rồi bấm **Run workflow**.
  4. Sau 5 phút, file `.apk` mới sẽ tự động xuất hiện công khai tại mục **[Releases](https://github.com/buiduchuy2010qn-prog/SmoothMineLauncher/releases)**!

---

## 📜 Giấy phép (License)

Phát hành theo giấy phép **GNU LGPL-3.0**, kế thừa từ PojavLauncherTeam & AngelAuraMC/Amethyst-Android.
