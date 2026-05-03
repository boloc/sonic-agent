/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.bridge.android;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.*;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.common.enums.AndroidKey;
import org.cloud.sonic.agent.common.maps.AndroidThreadMap;
import org.cloud.sonic.agent.common.maps.AndroidWebViewMap;
import org.cloud.sonic.agent.common.maps.ChromeDriverMap;
import org.cloud.sonic.agent.common.maps.GlobalProcessMap;
import org.cloud.sonic.agent.common.maps.WiFiDeviceIdMap;
import org.cloud.sonic.agent.tests.android.AndroidBatteryThread;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PortTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.cloud.sonic.agent.tools.file.DownloadTool;
import org.cloud.sonic.agent.tools.file.FileTool;
import org.cloud.sonic.agent.tools.file.UploadTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author ZhouYiXun
 * @des ADB工具类
 * @date 2021/08/16 19:26
 */
@DependsOn({"androidThreadPoolInit"})
@Component
@Slf4j
@Order(value = Ordered.HIGHEST_PRECEDENCE)
public class AndroidDeviceBridgeTool implements ApplicationListener<ContextRefreshedEvent> {
    public static AndroidDebugBridge androidDebugBridge = null;
    private static String uiaApkVersion;
    private static String apkVersion;
    private static RestTemplate restTemplate;

    private static Map<String, Integer> forwardPortMap = new ConcurrentHashMap<>();
    @Value("${sonic.saa}")
    private String ver;
    @Value("${sonic.saus}")
    private String uiaVer;
    @Autowired
    private RestTemplate restTemplateBean;

    @Autowired
    private AndroidDeviceStatusListener androidDeviceStatusListener;


    @Override
    public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
        init();
        log.info("Enable Android Module");
    }

    /**
     * @return java.lang.String
     * @author ZhouYiXun
     * @des 获取系统安卓SDK路径
     * @date 2021/8/16 19:35
     */
    private static String getADBPathFromSystemEnv() {
        // 0) explicit override (highest priority)
        String override = System.getProperty("sonic.adb.path");
        if (!StringUtils.hasText(override)) {
            override = System.getenv("SONIC_ADB_PATH");
        }
        if (StringUtils.hasText(override)) {
            return override.trim();
        }

        // 1) ANDROID_SDK_ROOT / ANDROID_HOME (prefer modern ANDROID_SDK_ROOT)
        String sdkRoot = System.getenv("ANDROID_SDK_ROOT");
        if (!StringUtils.hasText(sdkRoot)) {
            sdkRoot = System.getenv("ANDROID_HOME");
        }
        if (StringUtils.hasText(sdkRoot)) {
            String base = sdkRoot.trim();
            String adb = base + File.separator + "platform-tools" + File.separator + "adb";
            // Windows compatibility: platform-tools/adb.exe
            if (isWindows() && new File(adb + ".exe").exists()) {
                return adb + ".exe";
            }
            return adb;
        }

        // 2) adb in PATH (helps avoid "multiple adb versions kill-server" for WiFi debugging)
        if (isAdbAvailableInPath()) {
            return "adb";
        }

        // 3) bundled adb
        String bundled = "plugins" + File.separator + "adb";
        if (isWindows() && new File(bundled + ".exe").exists()) {
            return bundled + ".exe";
        }
        return bundled;
    }

    private static boolean isWindows() {
        try {
            String os = System.getProperty("os.name");
            return os != null && os.toLowerCase(Locale.ROOT).contains("win");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isAdbAvailableInPath() {
        try {
            Process p = new ProcessBuilder("adb", "version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * @return void
     * @author ZhouYiXun
     * @des 定义方法
     * @date 2021/8/16 19:36
     */
    public void init() {
        apkVersion = ver;
        uiaApkVersion = uiaVer;
        restTemplate = restTemplateBean;
        //获取系统SDK路径
        String systemADBPath = getADBPathFromSystemEnv();
        log.info("ADB binary: {}", systemADBPath);
        //添加设备上下线监听
        AndroidDebugBridge.addDeviceChangeListener(androidDeviceStatusListener);
        try {
            AndroidDebugBridge.init(false);
            //开始创建ADB
            androidDebugBridge = AndroidDebugBridge.createBridge(systemADBPath, true, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            if (androidDebugBridge != null) {
                log.info("Android devices listening...");
            }
        } catch (IllegalStateException e) {
            log.warn("AndroidDebugBridge has been init!");
        }
        int count = 0;
        //获取设备列表，超时后退出
        while (!androidDebugBridge.hasInitialDeviceList()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            count++;
            if (count > 200) {
                break;
            }
        }
        ScheduleTool.scheduleAtFixedRate(
                new AndroidBatteryThread(),
                AndroidBatteryThread.DELAY,
                AndroidBatteryThread.DELAY,
                AndroidBatteryThread.TIME_UNIT
        );
    }

    /**
     * @return com.android.ddmlib.IDevice[]
     * @author ZhouYiXun
     * @des 获取真实在线设备列表
     * @date 2021/8/16 19:38
     */
    public static IDevice[] getRealOnLineDevices() {
        if (androidDebugBridge != null) {
            return androidDebugBridge.getDevices();
        } else {
            return null;
        }
    }

    /**
     * @param iDevice
     * @return void
     * @author ZhouYiXun
     * @des 重启设备
     * @date 2021/8/16 19:41
     */
    public static void reboot(IDevice iDevice) {
        if (iDevice != null) {
            executeCommand(iDevice, "reboot");
        }
    }

    public static void shutdown(IDevice iDevice) {
        if (iDevice != null) {
            executeCommand(iDevice, "reboot -p");
        }
    }

    /**
     * @param udId 设备唯一标识（USB 设备是序列号，WiFi 设备是 IP）
     * @return com.android.ddmlib.IDevice
     * @author ZhouYiXun
     * @des 根据udId获取iDevice对象
     * @date 2021/8/16 19:42
     */
    public static IDevice getIDeviceByUdId(String udId) {
        IDevice iDevice = null;
        IDevice[] iDevices = AndroidDeviceBridgeTool.getRealOnLineDevices();
        if (iDevices == null || iDevices.length == 0) {
            return null;
        }

        // 首先尝试从 WiFiDeviceIdMap 获取实际的 serialNumber
        String mappedSerialNumber = WiFiDeviceIdMap.getSerialNumber(udId);

        for (IDevice device : iDevices) {
            if (!device.getState().equals(IDevice.DeviceState.ONLINE)) {
                continue;
            }
            String deviceSerial = device.getSerialNumber();

            // 匹配方式1：直接匹配（USB 设备或完整的 IP:端口）
            if (deviceSerial.equals(udId)) {
                iDevice = device;
                break;
            }

            // 匹配方式2：通过映射表匹配（WiFi 设备，udId 是 IP）
            if (deviceSerial.equals(mappedSerialNumber)) {
                iDevice = device;
                break;
            }

            // 匹配方式3：WiFi 设备通过 IP 前缀匹配（备选，当映射表未命中时）
            if (WiFiDeviceIdMap.isWiFiDevice(deviceSerial)) {
                String deviceIp = WiFiDeviceIdMap.getStableUdId(deviceSerial);
                if (deviceIp.equals(udId)) {
                    // 更新映射表
                    WiFiDeviceIdMap.register(deviceSerial);
                    iDevice = device;
                    break;
                }
            }
        }

        if (iDevice == null) {
            log.info("Device 「{}」 has not connected!", udId);
        }
        return iDevice;
    }

    /**
     * @param iDevice
     * @return java.lang.String
     * @author ZhouYiXun
     * @des 获取屏幕大小
     * @date 2021/8/16 19:44
     */
    public static String getScreenSize(IDevice iDevice) {
        String size = "";
        try {
            size = executeCommand(iDevice, "wm size", 5000, TimeUnit.MILLISECONDS);
            if (size.contains("Override size")) {
                size = size.substring(size.indexOf("Override size"));
            } else {
                size = size.split(":")[1];
            }
            //注意顺序问题
            size = size.trim()
                    .replace(":", "")
                    .replace("Override size", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .replace(" ", "");
            if (size.length() > 20) {
                size = "unknown";
            }
        } catch (Exception e) {
            log.info("Get screen size failed, ignore when plug in moment...");
        }
        return size;
    }

    /**
     * @param iDevice
     * @param command
     * @return java.lang.String
     * @author ZhouYiXun
     * @des 发送shell指令给对应设备
     * @date 2021/8/16 19:47
     */
    public static String executeCommand(IDevice iDevice, String command) {
        CollectingOutputReceiver output = new CollectingOutputReceiver();
        try {
            iDevice.executeShellCommand(command, output, 0, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.info("Send shell command {} to device {} failed."
                    , command, iDevice.getSerialNumber());
            log.error(e.getMessage());
        }
        return output.getOutput();
    }

    /**
     * Execute shell command with a bounded timeout.
     * NOTE: {@link #executeCommand(IDevice, String)} uses an infinite timeout (0ms) and may block forever on WiFi ADB.
     */
    public static String executeCommand(IDevice iDevice, String command, long timeout, TimeUnit unit) {
        CollectingOutputReceiver output = new CollectingOutputReceiver();
        try {
            iDevice.executeShellCommand(command, output, timeout, unit);
        } catch (Exception e) {
            log.info("Send shell command {} to device {} failed (timeout={} {}).",
                    command, iDevice.getSerialNumber(), timeout, unit);
            log.error(e.getMessage());
        }
        return output.getOutput();
    }

    /**
     * Best-effort keyguard detection. Returns true when the lock screen is showing.
     * We intentionally use a short timeout to avoid blocking test execution on WiFi ADB.
     */
    public static boolean isKeyguardLocked(IDevice iDevice) {
        try {
            String out = executeCommand(iDevice, "dumpsys window policy", 3000, TimeUnit.MILLISECONDS);
            if (out == null) return false;
            String lower = out.toLowerCase(Locale.ROOT);
            // Cover common outputs across versions/ROMs.
            return lower.contains("mshowinglockscreen=true")
                    || lower.contains("iskeyguardshowing=true")
                    || lower.contains("keyguardshowing=true")
                    || lower.contains("keyguard is showing")
                    || (lower.contains("keyguard") && lower.contains("showing=true"))
                    || lower.contains("mkeyguardshowing=true");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isDigitsOnly(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Unlock the device using ADB input when a PIN is configured.
     * This is the most reliable approach on Android 15+ where UIAutomator2 may not access lock screen UI.
     *
     * @return true if an unlock attempt was performed
     */
    public static boolean unlockByPinIfLocked(IDevice iDevice, String pin) {
        // We support "one universal pin for all devices" scenario:
        // 1) If device is not on keyguard -> do nothing.
        // 2) If keyguard -> wake + swipe.
        // 3) Only if keyguard is STILL showing after swipe, we input the PIN (digits only) + ENTER.
        // This avoids typing digits on devices without lock password.
        if (!isKeyguardLocked(iDevice)) return false;

        String purePin = pin == null ? "" : pin.trim();

        log.info("Keyguard detected, trying to unlock via ADB: {} (pinProvided={}, pinLen={})",
                iDevice.getSerialNumber(), StringUtils.hasText(purePin), purePin.length());

        // wake screen (idempotent)
        wakeUpScreen(iDevice);

        // Best-effort: try to dismiss keyguard without touch first.
        // This reduces "accidental gesture" when the device can be dismissed by system/biometric/smart-lock.
        try {
            executeCommand(iDevice, "wm dismiss-keyguard", 2000, TimeUnit.MILLISECONDS);
            Thread.sleep(250);
        } catch (Exception ignored) {
        }
        if (!isKeyguardLocked(iDevice)) return true;
        try {
            // MENU key dismisses swipe-only keyguard on some ROMs/versions
            executeCommand(iDevice, "input keyevent 82", 2000, TimeUnit.MILLISECONDS);
            Thread.sleep(250);
        } catch (Exception ignored) {
        }
        if (!isKeyguardLocked(iDevice)) return true;

        // Re-check before swipe: keyguard state can change between checks (e.g. biometric unlock).
        if (!isKeyguardLocked(iDevice)) return true;

        // swipe up to reveal unlock/pin pad (works for most ROMs; safe even if already visible)
        String size = executeCommand(iDevice, "wm size", 3000, TimeUnit.MILLISECONDS);
        int width = 1080;
        int height = 1920;
        try {
            if (size != null && size.contains("x")) {
                String s = size.replace("\r", "").replace("\n", "");
                int idx = s.lastIndexOf(":");
                if (idx >= 0) {
                    s = s.substring(idx + 1).trim();
                }
                s = s.replace(" ", "");
                String[] wh = s.split("x");
                if (wh.length >= 2) {
                    width = Integer.parseInt(wh[0].trim());
                    height = Integer.parseInt(wh[1].trim());
                }
            }
        } catch (Exception ignored) {
        }
        int x = (int) (width * 0.5);
        // Avoid starting too close to nav bar/gesture area; use ratios.
        int startY = (int) (height * 0.85);
        int endY = (int) (height * 0.15);
        startY = Math.max(1, Math.min(height - 1, startY));
        endY = Math.max(1, Math.min(height - 1, endY));
        executeCommand(iDevice, "input swipe " + x + " " + startY + " " + x + " " + endY + " 320", 3000, TimeUnit.MILLISECONDS);

        // Some devices have no lock password: swipe already unlocks -> stop here.
        if (!isKeyguardLocked(iDevice)) {
            return true;
        }

        // If still locked, we need credentials. Only support digits PIN here.
        if (!StringUtils.hasText(purePin) || !isDigitsOnly(purePin)) {
            log.warn("Device still locked after swipe, but pin is blank/non-digits: {}", iDevice.getSerialNumber());
            return false;
        }

        // input pin via keyevents (KEYCODE_0=7 ... KEYCODE_9=16)
        for (int i = 0; i < purePin.length(); i++) {
            int keyCode = 7 + (purePin.charAt(i) - '0');
            executeCommand(iDevice, "input keyevent " + keyCode, 2000, TimeUnit.MILLISECONDS);
        }
        // press ENTER (some lock screens accept automatically; ENTER is safe)
        executeCommand(iDevice, "input keyevent 66", 2000, TimeUnit.MILLISECONDS);
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
        }
        // success means we are no longer on keyguard
        return !isKeyguardLocked(iDevice);
    }

    public static void install(IDevice iDevice, String path) throws InstallException {
        try {
            iDevice.installPackage(path,
                    true, new InstallReceiver(), 180L, 180L, TimeUnit.MINUTES
                    , "-r", "-t", "-g");
        } catch (InstallException e) {
            log.info("{} install failed, cause {}, retry...", path, e.getMessage());
            try {
                iDevice.installPackage(path,
                        true, new InstallReceiver(), 180L, 180L, TimeUnit.MINUTES
                        , "-r", "-t");
            } catch (InstallException e2) {
                log.info("{} install failed, cause {}, retry...", path, e2.getMessage());
                try {
                    iDevice.installPackage(path,
                            true, new InstallReceiver(), 180L, 180L, TimeUnit.MINUTES);
                } catch (InstallException e3) {
                    log.info("{} install failed, cause {}", path, e3.getMessage());
                    throw e3;
                }
            }
        }
    }

    public static boolean checkSonicApkVersion(IDevice iDevice) {
        String expected = apkVersion == null ? "" : apkVersion.trim();
        String actual = getPackageVersionName(iDevice, "org.cloud.sonic.android");
        boolean ok = StringUtils.hasText(expected) && expected.equals(actual);
        if (!ok) {
            log.info("Sonic Apk version mismatch. expected={}, actual={}, serial={}",
                    expected, actual, iDevice == null ? "null" : iDevice.getSerialNumber());
        }
        return ok;
    }

    public static boolean checkUiaApkVersion(IDevice iDevice) {
        String expected = uiaApkVersion == null ? "" : uiaApkVersion.trim();
        String actual = getPackageVersionName(iDevice, "io.appium.uiautomator2.server");
        boolean ok = StringUtils.hasText(expected) && expected.equals(actual);
        if (!ok) {
            log.info("UIA Apk version mismatch. expected={}, actual={}, serial={}",
                    expected, actual, iDevice == null ? "null" : iDevice.getSerialNumber());
        }
        return ok;
    }

    /**
     * Get versionName for a package from dumpsys output.
     * On some OEM ROMs the full dumpsys output can be large or slow; we try a short form first.
     */
    private static String getPackageVersionName(IDevice iDevice, String packageName) {
        if (iDevice == null || !StringUtils.hasText(packageName)) {
            return "";
        }
        String pkg = packageName.trim();
        String out = "";
        try {
            // Prefer a small output to avoid truncation in the receiver and reduce latency.
            out = executeCommand(iDevice,
                    "dumpsys package " + pkg + " | grep -m 1 versionName",
                    5000, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        if (!StringUtils.hasText(out) || out.toLowerCase(Locale.ROOT).contains("not found") || !out.contains("versionName=")) {
            out = executeCommand(iDevice, "dumpsys package " + pkg, 8000, TimeUnit.MILLISECONDS);
        }
        if (!StringUtils.hasText(out)) {
            return "";
        }
        try {
            Matcher m = Pattern.compile("versionName=([^\\s]+)").matcher(out);
            if (m.find()) {
                return m.group(1).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * @param iDevice
     * @param port
     * @param service
     * @return void
     * @author ZhouYiXun
     * @des 同adb forward指令，将设备内进程的端口暴露给pc本地，但是只能转发给localhost，不能转发给ipv4
     * @date 2021/8/16 19:52
     */
    public static void forward(IDevice iDevice, int port, String service) {
        String name = String.format("process-%s-forward-%s", iDevice.getSerialNumber(), service);
        Integer oldP = forwardPortMap.get(name);
        if (oldP != null) {
            removeForward(iDevice, oldP, service);
        }
        try {
            log.info("{} device {} port forward to {}", iDevice.getSerialNumber(), service, port);
            iDevice.createForward(port, service, IDevice.DeviceUnixSocketNamespace.ABSTRACT);
            forwardPortMap.put(name, port);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    public static void forward(IDevice iDevice, int port, int target) {
        String name = String.format("process-%s-forward-%d", iDevice.getSerialNumber(), target);
        Integer oldP = forwardPortMap.get(name);
        if (oldP != null) {
            removeForward(iDevice, oldP, target);
        }
        try {
            log.info("{} device {} forward to {}", iDevice.getSerialNumber(), target, port);
            iDevice.createForward(port, target);
            forwardPortMap.put(name, port);
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    /**
     * @param iDevice
     * @param port
     * @param serviceName
     * @return void
     * @author ZhouYiXun
     * @des 去掉转发
     * @date 2021/8/16 19:53
     */
    public static void removeForward(IDevice iDevice, int port, String serviceName) {
        try {
            log.info("cancel {} device {} port forward to {}", iDevice.getSerialNumber(), serviceName, port);
            iDevice.removeForward(port);
            String name = String.format("process-%s-forward-%s", iDevice.getSerialNumber(), serviceName);
            if (forwardPortMap.get(name) != null) {
                forwardPortMap.remove(name);
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    public static void removeForward(IDevice iDevice, int port, int target) {
        try {
            log.info("cancel {} device {} forward to {}", iDevice.getSerialNumber(), target, port);
            iDevice.removeForward(port);
            String name = String.format("process-%s-forward-%d", iDevice.getSerialNumber(), target);
            if (forwardPortMap.get(name) != null) {
                forwardPortMap.remove(name);
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    /**
     * @param iDevice
     * @param localPath
     * @param remotePath
     * @return void
     * @author ZhouYiXun
     * @des 推送文件
     * @date 2021/8/16 19:59
     */
//    public static void pushLocalFile(IDevice iDevice, String localPath, String remotePath) {
//        AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
//            //使用iDevice的pushFile方法好像有bug，暂时用命令行去推送
//            ProcessBuilder pb = new ProcessBuilder(new String[]{getADBPathFromSystemEnv(), "-s", iDevice.getSerialNumber(), "push", localPath, remotePath});
//            pb.redirectErrorStream(true);
//            try {
//                pb.start();
//            } catch (IOException e) {
//                log.error(e.getMessage());
//                return;
//            }
//        });
//    }

    /**
     * @param iDevice
     * @param keyNum
     * @return void
     * @author ZhouYiXun
     * @des 输入对应按键
     * @date 2021/8/16 19:59
     */
    public static void pressKey(IDevice iDevice, int keyNum) {
        executeCommand(iDevice, String.format("input keyevent %s", keyNum), 5000, TimeUnit.MILLISECONDS);
    }

    public static void pressKey(IDevice iDevice, AndroidKey androidKey) {
        executeCommand(iDevice, String.format("input keyevent %s", androidKey.getCode()), 5000, TimeUnit.MILLISECONDS);
    }

    /**
     * Wake up the device screen if it's off
     * Important for Android 15+ where locked screen can kill UIAutomator2
     */
    public static void wakeUpScreen(IDevice iDevice) {
        // IMPORTANT:
        // Do NOT run `dumpsys power` here. On WiFi ADB it can be slow/blocking and will stall test execution.
        // Sending WAKEUP (224) is idempotent when screen is already on, and is fast enough to call often.
        try {
            executeCommand(iDevice, "input keyevent 224", 2000, TimeUnit.MILLISECONDS);
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        } catch (Exception e) {
            log.warn("Failed to wake up screen: {}", e.getMessage());
        }
    }

    public static String uninstall(IDevice iDevice, String bundleId) throws InstallException {
        return iDevice.uninstallPackage(bundleId);
    }

    public static void forceStop(IDevice iDevice, String bundleId) {
        executeCommand(iDevice, String.format("am force-stop %s", bundleId), 5000, TimeUnit.MILLISECONDS);
    }

    public static String activateApp(IDevice iDevice, String bundleId) {
        return executeCommand(iDevice, String.format("monkey -p %s -c android.intent.category.LAUNCHER 1", bundleId), 5000, TimeUnit.MILLISECONDS);
    }

    /**
     * @param iDevice
     * @param key
     * @return java.lang.String
     * @author ZhouYiXun
     * @des 获取设备配置信息
     * @date 2021/8/16 20:01
     */
    public static String getProperties(IDevice iDevice, String key) {
        return iDevice.getProperty(key);
    }

    /**
     * @param sdk
     * @return java.lang.String
     * @author ZhouYiXun
     * @des 根据sdk匹配对应的文件
     * @date 2021/8/16 20:01
     */
    public static String matchMiniCapFile(String sdk) {
        String filePath;
        if (Integer.parseInt(sdk) < 16) {
            filePath = "minicap-nopie";
        } else {
            filePath = "minicap";
        }
        return filePath;
    }

    public static void startProxy(IDevice iDevice, String host, int port) {
        executeCommand(iDevice, String.format("settings put global http_proxy %s:%d", host, port), 5000, TimeUnit.MILLISECONDS);
    }

    public static void clearProxy(IDevice iDevice) {
        executeCommand(iDevice, "settings put global http_proxy :0", 5000, TimeUnit.MILLISECONDS);
    }

    public static void screen(IDevice iDevice, String type) {
        int p = getScreen(iDevice);
        try {
            switch (type) {
                case "abort" ->
                        executeCommand(iDevice, "content insert --uri content://settings/system --bind name:s:accelerometer_rotation --bind value:i:0", 5000, TimeUnit.MILLISECONDS);
                case "add" -> {
                    if (p == 3) {
                        p = 0;
                    } else {
                        p++;
                    }
                    executeCommand(iDevice, "content insert --uri content://settings/system --bind name:s:user_rotation --bind value:i:" + p, 5000, TimeUnit.MILLISECONDS);
                }
                case "sub" -> {
                    if (p == 0) {
                        p = 3;
                    } else {
                        p--;
                    }
                    executeCommand(iDevice, "content insert --uri content://settings/system --bind name:s:user_rotation --bind value:i:" + p, 5000, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }

    public static int getScreen(IDevice iDevice) {
        try {
            return Integer.parseInt(executeCommand(iDevice, "settings get system user_rotation", 5000, TimeUnit.MILLISECONDS)
                    .trim().replaceAll("\n", "")
                    .replace("\t", ""));
        } catch (Exception e) {
            log.error(e.getMessage());
            return 0;
        }
    }

    public static int getOrientation(IDevice iDevice) {
        if (iDevice == null) return 0;
        String inputs = executeCommand(iDevice, "dumpsys input", 5000, TimeUnit.MILLISECONDS);
        if (inputs == null) inputs = "";
        if (inputs.contains("SurfaceOrientation")) {
            try {
                // Be tolerant to different formats across Android versions/ROMs:
                // SurfaceOrientation: 0
                // SurfaceOrientation=0
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("SurfaceOrientation\\s*[:=]\\s*(\\d+)")
                        .matcher(inputs);
                if (m.find()) {
                    return BytesTool.getInt(m.group(1));
                }
            } catch (Exception ignored) {
            }
        }

        // Fallback: infer from "cur=WxH" in dumpsys window displays
        inputs = executeCommand(iDevice, "dumpsys window displays", 5000, TimeUnit.MILLISECONDS);
        if (inputs == null) inputs = "";
        int curIdx = inputs.indexOf("cur=");
        if (curIdx < 0) {
            return 0;
        }
        try {
            String orientationS = inputs.substring(curIdx).trim();
            int end = orientationS.indexOf(" ");
            if (end < 0) end = orientationS.length();
            String sizeT = orientationS.substring(4, end);
            String[] size = sizeT.split("x");
            if (size.length < 2) return 0;
            return BytesTool.getInt(size[0]) > BytesTool.getInt(size[1]) ? 1 : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static int[] getDisplayOfAllScreen(IDevice iDevice, int width, int height, int ori) {
        String out = executeCommand(iDevice, "dumpsys window windows", 5000, TimeUnit.MILLISECONDS);
        String[] windows = out.split("Window #");
        String packageName = getCurrentPackage(iDevice);
        int offsetx = 0, offsety = 0;
        if (packageName != null) {
            for (String window : windows) {
                if (window.contains("package=" + packageName)) {
                    String patten = "Frames: containing=\\[(\\d+\\.?\\d*),(\\d+\\.?\\d*)]\\[(\\d+\\.?\\d*),(\\d+\\.?\\d*)]";
                    Pattern pattern = Pattern.compile(patten);
                    Matcher m = pattern.matcher(window);
                    while (m.find()) {
                        if (m.groupCount() != 4) break;
                        offsetx = Integer.parseInt(m.group(1));
                        offsety = Integer.parseInt(m.group(2));
                        width = Integer.parseInt(m.group(3));
                        height = Integer.parseInt(m.group(4));

                        if (ori == 1 || ori == 3) {
                            int tempOffsetX = offsetx;
                            int tempWidth = width;

                            offsetx = offsety;
                            offsety = tempOffsetX;
                            width = height;
                            height = tempWidth;
                        }

                        width -= offsetx;
                        height -= offsety;
                    }
                }
            }
        }
        return new int[]{offsetx, offsety, width, height};
    }

    public static String getCurrentPackage(IDevice iDevice) {
        int api = Integer.parseInt(iDevice.getProperty(IDevice.PROP_BUILD_API_LEVEL));
        // Use bounded timeout to avoid hanging on WiFi ADB.
        String cmd = AndroidDeviceBridgeTool.executeCommand(iDevice,
                String.format("dumpsys window %s", api >= 29 ? "displays" : "windows"),
                3000, TimeUnit.MILLISECONDS);
        String result = "";
        try {
            String start = cmd.substring(cmd.indexOf("mCurrentFocus="));
            String end = start.substring(0, start.indexOf("/"));
            result = end.substring(end.lastIndexOf(" ") + 1);
        } catch (Exception ignored) {
        }
        if (result.length() == 0) {
            try {
                String start = cmd.substring(cmd.indexOf("mFocusedApp="));
                String startCut = start.substring(0, start.indexOf("/"));
                result = startCut.substring(startCut.lastIndexOf(" ") + 1);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static String getCurrentActivity(IDevice iDevice) {
        int api = Integer.parseInt(iDevice.getProperty(IDevice.PROP_BUILD_API_LEVEL));
        // Use bounded timeout to avoid hanging on WiFi ADB.
        String cmd = AndroidDeviceBridgeTool.executeCommand(iDevice,
                String.format("dumpsys window %s", api >= 29 ? "displays" : "windows"),
                3000, TimeUnit.MILLISECONDS);
        String result = "";
        try {
            Pattern pattern = Pattern.compile("mCurrentFocus=(?!null)[^,]+");
            Matcher matcher = pattern.matcher(cmd);
            if (matcher.find()) {
                String start = cmd.substring(matcher.start());
                String end = start.substring(start.indexOf("/") + 1);
                result = end.substring(0, end.indexOf("}"));
            }
        } catch (Exception ignored) {
        }
        if (result.length() == 0) {
            try {
                String start = cmd.substring(cmd.indexOf("mFocusedApp="));
                String end = start.substring(start.indexOf("/") + 1);
                result = end.substring(0, end.indexOf(" "));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * Swipe up from bottom to upper area.
     * Useful for unlocking and for some system credential screens.
     */
    public static void swipeUp(IDevice iDevice, int durationMs) {
        int[] wh = getWmSizeOrDefault(iDevice);
        int width = wh[0];
        int height = wh[1];
        int x = (int) (width * 0.5);
        int startY = height - 50;
        int endY = (int) (height * 0.20);
        int d = Math.max(150, durationMs);
        executeCommand(iDevice, "input swipe " + x + " " + startY + " " + x + " " + endY + " " + d,
                3000, TimeUnit.MILLISECONDS);
    }

    private static int[] getWmSizeOrDefault(IDevice iDevice) {
        int width = 1080;
        int height = 1920;
        try {
            String size = executeCommand(iDevice, "wm size", 3000, TimeUnit.MILLISECONDS);
            if (size != null && size.contains("x")) {
                String s = size.replace("\r", "").replace("\n", "");
                int idx = s.lastIndexOf(":");
                if (idx >= 0) {
                    s = s.substring(idx + 1).trim();
                }
                s = s.replace(" ", "");
                String[] wh = s.split("x");
                if (wh.length >= 2) {
                    width = Integer.parseInt(wh[0].trim());
                    height = Integer.parseInt(wh[1].trim());
                }
            }
        } catch (Exception ignored) {
        }
        return new int[]{width, height};
    }

    /**
     * Tap the screen with ratio coordinates.
     * For example, (0.5, 0.6) taps around the center-lower area where password fields often are.
     */
    public static void tapByRatio(IDevice iDevice, double xRatio, double yRatio) {
        int[] wh = getWmSizeOrDefault(iDevice);
        int width = wh[0];
        int height = wh[1];
        int x = (int) Math.max(1, Math.min(width - 1, width * xRatio));
        int y = (int) Math.max(1, Math.min(height - 1, height * yRatio));
        executeCommand(iDevice, "input tap " + x + " " + y, 2000, TimeUnit.MILLISECONDS);
    }

    /**
     * Input digits PIN via keyevent.
     *
     * @return true if input performed
     */
    public static boolean inputPinByKeyevent(IDevice iDevice, String pin, boolean pressEnter) {
        String purePin = pin == null ? "" : pin.trim();
        if (!StringUtils.hasText(purePin) || !isDigitsOnly(purePin)) {
            return false;
        }
        for (int i = 0; i < purePin.length(); i++) {
            int keyCode = 7 + (purePin.charAt(i) - '0'); // KEYCODE_0=7 ... KEYCODE_9=16
            executeCommand(iDevice, "input keyevent " + keyCode, 2000, TimeUnit.MILLISECONDS);
        }
        if (pressEnter) {
            executeCommand(iDevice, "input keyevent 66", 2000, TimeUnit.MILLISECONDS); // ENTER
        }
        return true;
    }

    /**
     * Input digits by keyevent with small delays to reduce dropped keys on some ROMs/WiFi ADB.
     */
    public static boolean inputPinByKeyeventSlow(IDevice iDevice, String pin, boolean pressEnter, int delayMs) {
        String purePin = pin == null ? "" : pin.trim();
        if (!StringUtils.hasText(purePin) || !isDigitsOnly(purePin)) return false;
        int d = Math.max(30, delayMs);
        for (int i = 0; i < purePin.length(); i++) {
            int keyCode = 7 + (purePin.charAt(i) - '0');
            executeCommand(iDevice, "input keyevent " + keyCode, 2000, TimeUnit.MILLISECONDS);
            try {
                Thread.sleep(d);
            } catch (InterruptedException ignored) {
            }
        }
        if (pressEnter) {
            // ENTER / NUMPAD_ENTER / DPAD_CENTER as fallbacks
            executeCommand(iDevice, "input keyevent 66", 2000, TimeUnit.MILLISECONDS);
            executeCommand(iDevice, "input keyevent 160", 2000, TimeUnit.MILLISECONDS);
            executeCommand(iDevice, "input keyevent 23", 2000, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    /**
     * Input digits using a single "input text" command to reduce flakiness on WiFi ADB/OEM ROMs.
     *
     * @return true if command was sent
     */
    public static boolean inputDigitsByText(IDevice iDevice, String digits, boolean pressEnter) {
        String d = digits == null ? "" : digits.trim();
        if (!StringUtils.hasText(d) || !isDigitsOnly(d)) return false;
        // For digits-only, no escaping is needed.
        executeCommand(iDevice, "input text " + d, 3000, TimeUnit.MILLISECONDS);
        if (pressEnter) {
            executeCommand(iDevice, "input keyevent 66", 2000, TimeUnit.MILLISECONDS); // ENTER
            executeCommand(iDevice, "input keyevent 160", 2000, TimeUnit.MILLISECONDS); // NUMPAD_ENTER
            executeCommand(iDevice, "input keyevent 23", 2000, TimeUnit.MILLISECONDS); // DPAD_CENTER
        }
        return true;
    }

    /**
     * Detect "confirm device credential" style screens (not keyguard) that may require PIN/password to continue,
     * e.g. during install/permission changes on some ROMs.
     */
    public static boolean isCredentialConfirmationShowing(IDevice iDevice) {
        try {
            String pkg = getCurrentPackage(iDevice);
            String act = getCurrentActivity(iDevice);
            String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
            String a = act == null ? "" : act.toLowerCase(Locale.ROOT);
            // Common AOSP/Settings activities:
            // - ConfirmLockPassword / ConfirmLockPattern / ConfirmLockPin
            // - ConfirmDeviceCredentialActivity
            if (a.contains("confirmlock") || a.contains("confirmdevicecredential")) return true;
            if (a.contains("confirm") && (a.contains("credential") || a.contains("lock") || a.contains("password") || a.contains("pin") || a.contains("pattern"))) {
                return true;
            }
            // Some OEM security flows still live under settings/security packages.
            if ((p.contains("settings") || p.contains("security") || p.contains("safecenter")) && a.contains("confirm")) {
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Enter PIN if the current foreground is a credential confirmation screen.
     * This does NOT try to find UI elements; it relies on focused input field + ENTER.
     */
    public static boolean enterPinIfCredentialConfirmation(IDevice iDevice, String pin) {
        if (!isCredentialConfirmationShowing(iDevice)) return false;
        wakeUpScreen(iDevice);
        // DO NOT swipe up here: on gesture navigation (e.g. OPPO), swipe-up can be treated as HOME.
        // Keep it generic and non-invasive: rely on focused field and submit keys.

        String purePin = pin == null ? "" : pin.trim();
        if (!StringUtils.hasText(purePin) || !isDigitsOnly(purePin)) {
            log.warn("Credential confirmation requires digits pin, but pin is blank/non-digits: {}", iDevice.getSerialNumber());
            return false;
        }

        int maxAttempt = 2;
        for (int attempt = 0; attempt < maxAttempt; attempt++) {
            if (!isCredentialConfirmationShowing(iDevice)) return true;
            inputDigitsByText(iDevice, purePin, true);
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
            }
            if (!isCredentialConfirmationShowing(iDevice)) return true;
            // fallback for ROMs that block input text
            inputPinByKeyeventSlow(iDevice, purePin, true, 120);
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
            }
            if (!isCredentialConfirmationShowing(iDevice)) return true;
        }
        return !isCredentialConfirmationShowing(iDevice);
    }

    /**
     * A universal helper for "may need pin here" use cases:
     * - If on keyguard: swipe, and only if still locked then input pin.
     * - Else if on credential confirmation screen: input pin.
     */
    public static boolean enterPinIfNeeded(IDevice iDevice, String pin) {
        // Rule:
        // - If on keyguard (lock screen), we MUST swipe to show keypad (this is the only place we swipe).
        // - Else, if on ConfirmLockPassword / ConfirmDeviceCredential, NEVER swipe (OPPO gesture may go HOME).
        if (isKeyguardLocked(iDevice)) {
            log.info("Keyguard detected, using unlock flow (may swipe): {}", iDevice.getSerialNumber());
            return unlockByPinIfLocked(iDevice, pin);
        }
        if (isCredentialConfirmationShowing(iDevice)) {
            log.info("Credential confirmation detected, entering pin without swipe: {}", iDevice.getSerialNumber());
            return enterPinIfCredentialConfirmation(iDevice, pin);
        }
        return false;
    }

    public static void pushToCamera(IDevice iDevice, String url) {
        try {
            File image = DownloadTool.download(url);
            iDevice.pushFile(image.getAbsolutePath(), "/sdcard/DCIM/Camera/" + image.getName());
            executeCommand(iDevice, "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/" + image.getName(), 5000, TimeUnit.MILLISECONDS);
        } catch (IOException | AdbCommandRejectedException | SyncException | TimeoutException e) {
            e.printStackTrace();
        }
    }

    public static void searchDevice(IDevice iDevice) {
        executeCommand(iDevice, "am start -n org.cloud.sonic.android/.plugin.activityPlugin.SearchActivity", 5000, TimeUnit.MILLISECONDS);
    }

    public static void controlBattery(IDevice iDevice, int type) {
        if (type == 0) {
            executeCommand(iDevice, "dumpsys battery unplug && dumpsys battery set status 1", 5000, TimeUnit.MILLISECONDS);
        }
        if (type == 1) {
            executeCommand(iDevice, "dumpsys battery reset", 5000, TimeUnit.MILLISECONDS);
        }
    }

    public static String pullFile(IDevice iDevice, String path) {
        String result = null;
        File base = new File("test-output" + File.separator + "pull");
        String filename = base.getAbsolutePath() + File.separator + UUID.randomUUID();
        File file = new File(filename);
        file.mkdirs();
        String system = System.getProperty("os.name").toLowerCase();
        String processName = String.format("process-%s-pull-file", iDevice.getSerialNumber());
        // 使用辅助方法终止旧进程并从 Map 中移除，防止内存泄漏
        GlobalProcessMap.terminateAndRemove(processName);
        try {
            Process process = null;
            String command = String.format("%s -s %s pull %s %s", getADBPathFromSystemEnv(), iDevice.getSerialNumber(), path, file.getAbsolutePath());
            if (system.contains("win")) {
                process = Runtime.getRuntime().exec(new String[]{"cmd", "/c", command});
            } else if (system.contains("linux") || system.contains("mac")) {
                process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            }
            GlobalProcessMap.putAndTerminateOld(processName, process);
            boolean isRunning;
            int wait = 0;
            do {
                Thread.sleep(500);
                wait++;
                isRunning = false;
                if (process == null) {
                    break;
                }
                List<ProcessHandle> processHandleList = process.children().collect(Collectors.toList());
                if (processHandleList.size() == 0) {
                    if (process.isAlive()) {
                        isRunning = true;
                    }
                } else {
                    for (ProcessHandle p : processHandleList) {
                        if (p.isAlive()) {
                            isRunning = true;
                            break;
                        }
                    }
                }
                if (wait >= 20) {
                    process.children().forEach(ProcessHandle::destroy);
                    process.destroy();
                    break;
                }
            } while (isRunning);
            File re = new File(filename + File.separator + (path.lastIndexOf("/") == -1 ? path : path.substring(path.lastIndexOf("/"))));
            result = UploadTools.upload(re, "packageFiles");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            FileTool.deleteDir(file);
            // 清理进程引用，防止内存泄漏
            GlobalProcessMap.terminateAndRemove(processName);
        }
        return result;
    }

    public static boolean installSonicApk(IDevice iDevice) {
        String path = executeCommand(iDevice, "pm path org.cloud.sonic.android", 5000, TimeUnit.MILLISECONDS).trim()
                .replaceAll("package:", "")
                .replaceAll("\n", "")
                .replaceAll("\t", "");
        // 只检查是否已安装，不再校验版本号
        if (path.length() > 0) {
            log.info("Sonic Apk already installed, skip install.");
            return true;
        } else {
            log.info("Sonic Apk not installed, starting install without uninstall...");
            try {
                install(iDevice, "plugins/sonic-android-apk.apk");
                executeCommand(iDevice, "appops set org.cloud.sonic.android POST_NOTIFICATION allow", 5000, TimeUnit.MILLISECONDS);
                executeCommand(iDevice, "appops set org.cloud.sonic.android RUN_IN_BACKGROUND allow", 5000, TimeUnit.MILLISECONDS);
                executeCommand(iDevice, "dumpsys deviceidle whitelist +org.cloud.sonic.android", 5000, TimeUnit.MILLISECONDS);
                log.info("Sonic Apk install successful.");
                return true;
            } catch (InstallException e) {
                log.info("Sonic Apk install failed.");
                return false;
            }
        }
    }

    public static int startUiaServer(IDevice iDevice, int port) throws InstallException {
        Thread s = AndroidThreadMap.getMap().get(String.format("%s-uia-thread", iDevice.getSerialNumber()));
        if (s != null) {
            s.interrupt();
            AndroidThreadMap.getMap().remove(String.format("%s-uia-thread", iDevice.getSerialNumber()));
        }

        // 在设备端彻底停止旧的 instrument 进程，防止 Android 16 上残留进程导致新启动失败
        executeCommand(iDevice, "am force-stop io.appium.uiautomator2.server", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "am force-stop io.appium.uiautomator2.server.test", 5000, TimeUnit.MILLISECONDS);

        // 等待系统完成进程回收，Android 16 需要更长时间
        int apiLevel = 0;
        try {
            String apiStr = iDevice.getProperty(IDevice.PROP_BUILD_API_LEVEL);
            if (apiStr != null) {
                apiLevel = Integer.parseInt(apiStr.trim());
            }
        } catch (Exception ignored) {}
        try {
            Thread.sleep(apiLevel >= 36 ? 1500 : 500);
        } catch (InterruptedException ignored) {}

        // Wake up screen before starting UIAutomator2 Server (important for Android 15+)
        wakeUpScreen(iDevice);

        // 只检查是否已安装，不再校验版本号，也不主动卸载
        String uiaPath = executeCommand(iDevice, "pm path io.appium.uiautomator2.server", 5000, TimeUnit.MILLISECONDS).trim()
                .replaceAll("package:", "")
                .replaceAll("\n", "")
                .replaceAll("\t", "");
        String uiaTestPath = executeCommand(iDevice, "pm path io.appium.uiautomator2.server.test", 5000, TimeUnit.MILLISECONDS).trim()
                .replaceAll("package:", "")
                .replaceAll("\n", "")
                .replaceAll("\t", "");
        if (uiaPath.length() > 0 && uiaTestPath.length() > 0) {
            log.info("UIA server already installed, skip install.");
        } else {
            log.info("UIA server not fully installed, starting install without uninstall...");
            install(iDevice, "plugins/sonic-appium-uiautomator2-server.apk");
            install(iDevice, "plugins/sonic-appium-uiautomator2-server-test.apk");
            executeCommand(iDevice, "appops set io.appium.uiautomator2.server RUN_IN_BACKGROUND allow", 5000, TimeUnit.MILLISECONDS);
            executeCommand(iDevice, "appops set io.appium.uiautomator2.server.test RUN_IN_BACKGROUND allow", 5000, TimeUnit.MILLISECONDS);
            executeCommand(iDevice, "dumpsys deviceidle whitelist +io.appium.uiautomator2.server", 5000, TimeUnit.MILLISECONDS);
            executeCommand(iDevice, "dumpsys deviceidle whitelist +io.appium.uiautomator2.server.test", 5000, TimeUnit.MILLISECONDS);
        }

        // Additional settings to prevent process from being killed
        // apiLevel 已在方法开头获取

        // 只有 Android 15+ (API 35+) 才需要额外的保活设置
        // Android 14 及以下版本保持原有逻辑，不添加额外处理
        if (apiLevel >= 35) {
            log.info("Android 15+ detected, applying aggressive keep-alive settings");
            try {
                // ===== Android 15/16 aggressive keep-alive settings =====
                // 合并为两条 shell 命令（用 ; 串联），减少 ADB 往返次数，避免逐条执行导致耗时过长
                String uia2ServerKeepAlive = String.join(" ; ",
                        "cmd appops set io.appium.uiautomator2.server RUN_ANY_IN_BACKGROUND allow",
                        "cmd appops set io.appium.uiautomator2.server RUN_IN_BACKGROUND allow",
                        "am set-standby-bucket io.appium.uiautomator2.server active",
                        "dumpsys deviceidle whitelist +io.appium.uiautomator2.server",
                        "cmd deviceidle whitelist +io.appium.uiautomator2.server",
                        "cmd appops set io.appium.uiautomator2.server AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",
                        "cmd activity set-inactive io.appium.uiautomator2.server false"
                );
                String uia2TestKeepAlive = String.join(" ; ",
                        "cmd appops set io.appium.uiautomator2.server.test RUN_ANY_IN_BACKGROUND allow",
                        "cmd appops set io.appium.uiautomator2.server.test RUN_IN_BACKGROUND allow",
                        "am set-standby-bucket io.appium.uiautomator2.server.test active",
                        "dumpsys deviceidle whitelist +io.appium.uiautomator2.server.test",
                        "cmd deviceidle whitelist +io.appium.uiautomator2.server.test",
                        "cmd appops set io.appium.uiautomator2.server.test AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",
                        "cmd activity set-inactive io.appium.uiautomator2.server.test false"
                );
                executeCommand(iDevice, uia2ServerKeepAlive, 10000, TimeUnit.MILLISECONDS);
                executeCommand(iDevice, uia2TestKeepAlive, 10000, TimeUnit.MILLISECONDS);

            } catch (Exception e) {
                log.warn("Failed to apply some Android 15+ settings, continuing anyway: {}", e.getMessage());
            }
        }

        // Keep the port forward for the whole driver session.
        // Previously it was created/removed inside the instrumentation thread, which can cause intermittent timeouts
        // if the thread ends early or the forward is removed before the client finishes establishing the session.
        forward(iDevice, port, 6790);

        UiaThread uiaThread = new UiaThread(iDevice, port);
        uiaThread.start();

        // 基于连接类型和 API Level 确定等待时间，不针对特定厂商
        boolean isNetworkAdb = iDevice.getSerialNumber() != null && iDevice.getSerialNumber().contains(":");
        int maxWait = 20; // 默认: 20 * 800ms = 16 秒
        if (isNetworkAdb && apiLevel >= 36) {
            maxWait = 55; // WiFi + Android 16+: 55 * 800ms = 44 秒
        } else if (isNetworkAdb && apiLevel >= 35) {
            maxWait = 45; // WiFi + Android 15: 45 * 800ms = 36 秒
        } else if (isNetworkAdb && apiLevel >= 31) {
            maxWait = 35; // WiFi + Android 12-14: 35 * 800ms = 28 秒
        } else if (apiLevel >= 35) {
            maxWait = 30; // USB + Android 15+: 30 * 800ms = 24 秒
        } else if (isNetworkAdb) {
            maxWait = 30; // WiFi + 低版本: 24 秒
        }

        int wait = 0;
        while (!uiaThread.getIsOpen()) {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            wait++;
            if (wait >= maxWait) {
                break;
            }
        }

        // HTTP 就绪探测超时：基于连接类型和 API Level
        // 高版本 Android 和 WiFi 连接需要更长超时
        long probeTimeout;
        if (isNetworkAdb && apiLevel >= 36) {
            probeTimeout = 60_000; // WiFi + Android 16+: 60 秒
        } else if (isNetworkAdb && apiLevel >= 35) {
            probeTimeout = 50_000; // WiFi + Android 15: 50 秒
        } else if (isNetworkAdb && apiLevel >= 31) {
            probeTimeout = 40_000; // WiFi + Android 12-14: 40 秒
        } else if (apiLevel >= 35) {
            probeTimeout = 35_000; // USB + Android 15+: 35 秒
        } else if (isNetworkAdb) {
            probeTimeout = 30_000; // WiFi + 低版本: 30 秒
        } else {
            probeTimeout = 8_000;  // USB + 低版本: 8 秒
        }
        log.info("UIAutomator2 probe timeout: {}ms (WiFi={}, API={})", probeTimeout, isNetworkAdb, apiLevel);
        if (!waitForLocalHttpReady(port, probeTimeout)) {
            uiaThread.interrupt();
            removeForward(iDevice, port, 6790);
            throw new RuntimeException("UIAutomator2 server HTTP readiness probe timeout on local port " + port);
        }

        AndroidThreadMap.getMap().put(String.format("%s-uia-thread", iDevice.getSerialNumber()), uiaThread);
        return port;
    }

    public static int startUiaServer(IDevice iDevice) throws InstallException {
        return startUiaServer(iDevice, PortTool.getPort());
    }

    public static int startUiaServerWithRetry(IDevice iDevice) throws InstallException {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            int port = PortTool.getPort();
            try {
                if (attempt > 1) {
                    repairUiaServerEnvironment(iDevice, attempt);
                }
                log.info("Starting UIAutomator2 server, device={}, attempt={}, localPort={}",
                        iDevice.getSerialNumber(), attempt, port);
                return startUiaServer(iDevice, port);
            } catch (Exception e) {
                lastException = e;
                log.warn("Start UIAutomator2 server failed, device={}, attempt={}, localPort={}, cause={}",
                        iDevice.getSerialNumber(), attempt, port, e.getMessage());
                removeForward(iDevice, port, 6790);
                try {
                    Thread.sleep(attempt * 1500L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lastException instanceof InstallException installException) {
            throw installException;
        }
        throw new RuntimeException("UIAutomator2 server failed after recovery attempts", lastException);
    }

    private static void repairUiaServerEnvironment(IDevice iDevice, int attempt) throws InstallException {
        log.warn("Repair UIAutomator2 environment, device={}, attempt={}", iDevice.getSerialNumber(), attempt);
        Thread s = AndroidThreadMap.getMap().remove(String.format("%s-uia-thread", iDevice.getSerialNumber()));
        if (s != null) {
            s.interrupt();
        }

        executeCommand(iDevice, "am force-stop io.appium.uiautomator2.server", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "am force-stop io.appium.uiautomator2.server.test", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "pm clear io.appium.uiautomator2.server", 10000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "pm clear io.appium.uiautomator2.server.test", 10000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "settings put global hidden_api_policy 1", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "settings put global hidden_api_policy_pre_p_apps 1", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "settings put global hidden_api_policy_p_apps 1", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "cmd appops set io.appium.uiautomator2.server RUN_ANY_IN_BACKGROUND allow", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "cmd appops set io.appium.uiautomator2.server.test RUN_ANY_IN_BACKGROUND allow", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "cmd deviceidle whitelist +io.appium.uiautomator2.server", 5000, TimeUnit.MILLISECONDS);
        executeCommand(iDevice, "cmd deviceidle whitelist +io.appium.uiautomator2.server.test", 5000, TimeUnit.MILLISECONDS);

        wakeUpScreen(iDevice);
    }

    static class UiaThread extends Thread {

        private IDevice iDevice;
        private boolean isOpen = false;

        public UiaThread(IDevice iDevice, int port) {
            this.iDevice = iDevice;
        }

        public boolean getIsOpen() {
            return isOpen;
        }

        @Override
        public void run() {
            try {
                iDevice.executeShellCommand("am instrument -w io.appium.uiautomator2.server.test/androidx.test.runner.AndroidJUnitRunner -e DISABLE_SUPPRESS_ACCESSIBILITY_SERVICES true -e disableAnalytics true",
                        new IShellOutputReceiver() {
                            @Override
                            public void addOutput(byte[] bytes, int i, int i1) {
                                String res = new String(bytes, i, i1);
                                log.info(res);
                                if (res.contains("io.appium.uiautomator2.server.test.AppiumUiAutomator2Server:")) {
                                    try {
                                        // Wait longer for HTTP server to bind on HarmonyOS/network ADB
                                        Thread.sleep(3500);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    isOpen = true;
                                }
                            }

                            @Override
                            public void flush() {
                            }

                            @Override
                            public boolean isCancelled() {
                                return false;
                            }
                        }, 0, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void interrupt() {
            super.interrupt();
        }
    }

    /**
     * Wait until local forwarded port can respond to HTTP, to reduce intermittent UIAutomator2 timeouts.
     */
    private static boolean waitForLocalHttpReady(int localPort, long timeoutMs) {
        long start = System.currentTimeMillis();
        int attempt = 0;
        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", localPort), 1500);
                socket.setSoTimeout(1500);
                OutputStream os = socket.getOutputStream();
                // UIAutomator2 typically responds on /status (some builds may use /wd/hub/status).
                os.write(("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
                InputStream is = socket.getInputStream();
                byte[] buf = new byte[64];
                int n = is.read(buf);
                if (n > 0) {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("UIAutomator2 HTTP ready on localhost:{} after {}ms (attempt {})", localPort, elapsed, attempt);
                    return true;
                }
            } catch (IOException e) {
                // ignore and retry, but log every 5th attempt for debugging
                if (attempt % 5 == 0) {
                    log.debug("UIAutomator2 probe attempt {} on localhost:{} failed: {}", attempt, localPort, e.getMessage());
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                return false;
            }
        }
        log.warn("UIAutomator2 readiness probe timeout on localhost:{} after {}ms ({} attempts)", localPort, timeoutMs, attempt);
        return false;
    }

    public static void clearWebView(IDevice iDevice) {
        List<JSONObject> has = AndroidWebViewMap.getMap().get(iDevice);
        if (has != null && has.size() > 0) {
            for (JSONObject j : has) {
                AndroidDeviceBridgeTool.removeForward(iDevice, j.getInteger("port"), j.getString("name"));
            }
        }
        AndroidWebViewMap.getMap().remove(iDevice);
    }

    public static void sendKeysByKeyboard(IDevice iDevice, String msg) {
        executeCommand(iDevice, String.format("am broadcast -a SONIC_KEYBOARD --es msg \"%s\"", msg), 5000, TimeUnit.MILLISECONDS);
    }

    public static boolean setClipperByKeyboard(IDevice iDevice, String msg) {
        String suc = executeCommand(iDevice, String.format("am broadcast -a SONIC_CLIPPER_SET --es msg \"%s\"", msg), 5000, TimeUnit.MILLISECONDS);
        return suc.contains("result=-1");
    }

    public static String getClipperByKeyboard(IDevice iDevice) {
        String suc = executeCommand(iDevice, "am broadcast -a SONIC_CLIPPER_GET", 5000, TimeUnit.MILLISECONDS);
        if (suc.contains("result=-1")) {
            return suc.substring(suc.indexOf("data=\"") + 6, suc.length() - 2);
        } else {
            return "";
        }
    }

    /**
     * 获取完整的ChromeVersion，格式:83.0.4103.106
     *
     * @param iDevice     IDevice
     * @param packageName 应用包名
     * @return 完整的ChromeVersion
     */
    public static String getFullChromeVersion(IDevice iDevice, String packageName) {
        String chromeVersion = "";
        List<JSONObject> result = getWebView(iDevice);
        if (result.size() > 0) {
            for (JSONObject j : result) {
                if (packageName.equals(j.getString("package"))) {
                    chromeVersion = j.getString("version");
                    break;
                }
            }
        }
        if (chromeVersion.length() == 0) {
            return null;
        } else {
            chromeVersion = chromeVersion.replace("Chrome/", "");
        }
        return chromeVersion;
    }

    /**
     * 只获取ChromeVersion的主版本
     *
     * @param chromeVersion 完整的ChromeVersion，格式:83.0.4103.106
     * @return 主版本，如83
     */
    public static String getMajorChromeVersion(String chromeVersion) {
        if (StringUtils.hasText(chromeVersion)) {
            return null;
        }
        int end = (chromeVersion.contains(".") ? chromeVersion.indexOf(".") : chromeVersion.length() - 1);
        return chromeVersion.substring(0, end);
    }

    /**
     * 根据IDevice以及完整的ChromeVersion获取chromeDriver
     *
     * @param iDevice           IDevice
     * @param fullChromeVersion 完整的版本号，形如:83.0.4103.106
     * @return chromeDriver file
     * @throws IOException IOException
     */
    public static File getChromeDriver(IDevice iDevice, String fullChromeVersion) throws IOException {
        if (fullChromeVersion == null) {
            return null;
        }
        clearWebView(iDevice);

        String system = System.getProperty("os.name").toLowerCase();
        File search = new File(String.format("webview/%s_chromedriver%s", fullChromeVersion,
                (system.contains("win") ? ".exe" : "")));
        if (search.exists()) {
            return search;
        }

        String majorChromeVersion = getMajorChromeVersion(fullChromeVersion);
        boolean greaterThen114 = majorChromeVersion != null && Integer.parseInt(majorChromeVersion) > 114;
        HttpHeaders headers = new HttpHeaders();
        if (system.contains("win")) {
            system = "win32";
        } else if (system.contains("linux")) {
            system = "linux64";
        } else {
            String arch = System.getProperty("os.arch").toLowerCase();
            if (arch.contains("aarch64")) {
                if (greaterThen114) {
                    system = "mac-arm64";
                } else {
                    String driverList = restTemplate.exchange(String.format("https://registry.npmmirror.com/-/binary/chromedriver/%s/",
                            ChromeDriverMap.getMap().get(majorChromeVersion)), HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
                    boolean findM1ChromeDriver = false;
                    for (Object obj : JSONArray.parseArray(driverList)) {
                        JSONObject jsonObject = JSONObject.parseObject(obj.toString());
                        String fullName = jsonObject.getString("name");
                        if (fullName.contains("m1") || fullName.contains("arm")) {
                            system = fullName.substring(fullName.indexOf("mac"), fullName.indexOf("."));
                            findM1ChromeDriver = true;
                            break;
                        }
                    }
                    // <=86版本，google未提供M1架构的chromeDriver，改为固定用chromedriver_mac64.zip
                    if (!findM1ChromeDriver) {
                        system = "mac64";
                    }
                }
            } else {
                if (greaterThen114) {
                    system = "mac-x64";
                } else {
                    system = "mac64";
                }
            }
        }
        File file;
        if (greaterThen114) {
            // Starting with M115 the ChromeDriver release process is integrated with that of Chrome.
            // The latest Chrome + ChromeDriver releases per release channel (Stable, Beta, Dev, Canary) are available
            // at the Chrome for Testing (CfT) availability dashboard.
            file = DownloadTool.download(String.format(
                    "https://storage.googleapis.com/chrome-for-testing-public/%s/%s/chromedriver-%s.zip",
                    ChromeDriverMap.getMap().get(majorChromeVersion), system, system));
        } else {
            file = DownloadTool.download(String.format(
                    "https://cdn.npmmirror.com/binaries/chromedriver/%s/chromedriver_%s.zip",
                    ChromeDriverMap.getMap().get(majorChromeVersion), system));
        }
        return FileTool.unZipChromeDriver(file, fullChromeVersion, greaterThen114, system);
    }

    public static List<JSONObject> getWebView(IDevice iDevice) {
        clearWebView(iDevice);
        List<JSONObject> has = new ArrayList<>();
        Set<String> webSet = new HashSet<>();
        String[] out = AndroidDeviceBridgeTool
                .executeCommand(iDevice, "cat /proc/net/unix", 5000, TimeUnit.MILLISECONDS).split("\n");
        for (String w : out) {
            if (w.contains("webview") || w.contains("WebView") || w.contains("_devtools_remote")) {
                if (w.contains("@") && w.indexOf("@") + 1 < w.length()) {
                    webSet.add(w.substring(w.indexOf("@") + 1));
                }
            }
        }
        List<JSONObject> result = new ArrayList<>();
        if (webSet.size() > 0) {
            for (String ws : webSet) {
                int port = PortTool.getPort();
                AndroidDeviceBridgeTool.forward(iDevice, port, ws);
                JSONObject j = new JSONObject();
                j.put("port", port);
                j.put("name", ws);
                has.add(j);
                JSONObject r = new JSONObject();
                r.put("port", port);
                try {
                    // Use hutool HttpRequest with short timeout instead of RestTemplate
                    String versionResp = cn.hutool.http.HttpRequest.get("http://localhost:" + port + "/json/version")
                            .timeout(5000)  // 5 seconds timeout
                            .execute()
                            .body();
                    JSONObject versionJson = JSONObject.parseObject(versionResp);
                    r.put("version", versionJson.getString("Browser"));
                    r.put("package", versionJson.getString("Android-Package"));
                } catch (Exception e) {
                    log.debug("WebView {} version request failed: {}", ws, e.getMessage());
                    continue;
                }
                try {
                    String listResp = cn.hutool.http.HttpRequest.get("http://localhost:" + port + "/json/list")
                            .timeout(5000)  // 5 seconds timeout
                            .execute()
                            .body();
                    JSONArray listJson = JSONArray.parseArray(listResp);
                    List<JSONObject> child = new ArrayList<>();
                    for (int i = 0; i < listJson.size(); i++) {
                        JSONObject objE = listJson.getJSONObject(i);
                        JSONObject c = new JSONObject();
                        c.put("favicon", objE.getString("faviconUrl"));
                        c.put("title", objE.getString("title"));
                        c.put("url", objE.getString("url"));
                        c.put("id", objE.getString("id"));
                        child.add(c);
                    }
                    r.put("children", child);
                    result.add(r);
                } catch (Exception e) {
                    log.debug("WebView {} list request failed: {}", ws, e.getMessage());
                }
            }
            AndroidWebViewMap.getMap().put(iDevice, has);
        }
        return result;
    }
}
