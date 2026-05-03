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
package org.cloud.sonic.agent.tests.handlers;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceThreadPool;
import org.cloud.sonic.agent.common.enums.AndroidKey;
import org.cloud.sonic.agent.common.enums.ConditionEnum;
import org.cloud.sonic.agent.common.enums.SonicEnum;
import org.cloud.sonic.agent.common.interfaces.ErrorType;
import org.cloud.sonic.agent.common.interfaces.ResultDetailStatus;
import org.cloud.sonic.agent.common.interfaces.StepType;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.AndroidThreadMap;
import org.cloud.sonic.agent.common.maps.ChromeDriverMap;
import org.cloud.sonic.agent.common.models.HandleContext;
import org.cloud.sonic.agent.tests.LogUtil;
import org.cloud.sonic.agent.tests.RunStepThread;
import org.cloud.sonic.agent.tests.script.GroovyScriptImpl;
import org.cloud.sonic.agent.tests.script.PythonScriptImpl;
import org.cloud.sonic.agent.tests.script.ScriptRunner;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PortTool;
import org.cloud.sonic.agent.tools.SpringTool;
import org.cloud.sonic.agent.tools.file.DownloadTool;
import org.cloud.sonic.agent.tools.file.UploadTools;
import org.cloud.sonic.driver.android.AndroidDriver;
import org.cloud.sonic.driver.android.enmus.AndroidSelector;
import org.cloud.sonic.driver.android.service.AndroidElement;
import org.cloud.sonic.driver.common.models.BaseElement;
import org.cloud.sonic.driver.common.models.WindowSize;
import org.cloud.sonic.driver.common.tool.SonicRespException;
import org.cloud.sonic.driver.poco.PocoDriver;
import org.cloud.sonic.driver.poco.enums.PocoEngine;
import org.cloud.sonic.driver.poco.enums.PocoSelector;
import org.cloud.sonic.driver.poco.models.PocoElement;
import org.cloud.sonic.vision.cv.AKAZEFinder;
import org.cloud.sonic.vision.cv.SIFTFinder;
import org.cloud.sonic.vision.cv.SimilarityChecker;
import org.cloud.sonic.vision.cv.TemMatcher;
import org.cloud.sonic.vision.models.FindResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.util.CollectionUtils;

import javax.imageio.stream.FileImageOutputStream;
import java.io.File;
import java.util.*;
import java.util.concurrent.Future;

import static org.testng.Assert.*;

/**
 * @author ZhouYiXun
 * @des 安卓自动化处理类
 * @date 2021/8/16 20:10
 */
public class AndroidStepHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AndroidStepHandler.class);
    public LogUtil log = new LogUtil();
    private AndroidDriver androidDriver;
    private ChromeDriver chromeDriver;
    private PocoDriver pocoDriver = null;
    private JSONObject globalParams = new JSONObject();
    private IDevice iDevice;
    private int uiaPort = 0;
    private volatile long lastUiaRecoverTimeMs = 0L;

    // UIAutomator2 心跳保活线程
    private volatile Thread uiaHeartbeatThread = null;
    private volatile boolean uiaHeartbeatRunning = false;
    // 心跳间隔（毫秒）- 8 秒发送一次轻量级请求保持连接
    private static final long UIA_HEARTBEAT_INTERVAL_MS = 8_000;
    // 连续心跳失败次数阈值，超过则触发自愈
    private static final int UIA_HEARTBEAT_FAIL_THRESHOLD = 3;
    // 自愈冷却时间（毫秒）- 避免频繁自愈
    private static final long UIA_RECOVER_COOLDOWN_MS = 15_000;
    /**
     * uiautomator2 的 XPath2 在部分 Android 版本/ROM 上会触发
     * "Cannot set AccessibilityNodeInfo's field 'mSealed'..." 之类的反射限制异常。
     * 开启 enforceXPath1 可回退到 XPath1 解析以绕过该问题。
     */
    private volatile boolean enforceXPath1Enabled = false;
    // 当前设备的 API Level，用于针对高版本 Android 的稳定性优化
    private volatile int currentApiLevel = 0;
    // 是否为 WiFi ADB 连接
    private volatile boolean isWifiAdbConnection = false;
    private int status = ResultDetailStatus.PASS;
    private int[] screenWindowPosition = {0, 0, 0, 0};
    private int pocoPort = 0;
    private int targetPort = 0;

    private String targetPackage = "";

    // 断言元素个数，三种元素类型的定义
    private static final int ANDROID_ELEMENT_TYPE = 1001;
    private static final int WEB_ELEMENT_TYPE = 1002;
    private static final int POCO_ELEMENT_TYPE = 1003;

    // 屏幕的宽度与高度信息
    private int screenWidth = 0;
    private int screenHeight = 0;

    public String getTargetPackage() {
        return targetPackage;
    }

    public LogUtil getLog() {
        return log;
    }

    public void setTestMode(int caseId, int resultId, String udId, String type, String sessionId) {
        log.caseId = caseId;
        log.resultId = resultId;
        log.udId = udId;
        log.type = type;
        log.sessionId = sessionId;
    }

    public void setGlobalParams(JSONObject jsonObject) {
        globalParams = jsonObject;
    }

    /**
     * @author ZhouYiXun
     * @des 启动安卓驱动，连接设备
     * @date 2021/8/16 20:01
     */
    public void startAndroidDriver(IDevice iDevice, int uiaPort) throws Exception {
        this.iDevice = iDevice;
        this.uiaPort = uiaPort;
        int retry = 0;
        Exception out = null;

        // 只基于 API Level 和连接类型判断，不针对特定厂商
        boolean isWifiAdb = iDevice.getSerialNumber() != null && iDevice.getSerialNumber().contains(":");
        this.isWifiAdbConnection = isWifiAdb;

        int apiLevel = 0;
        try {
            String apiStr = iDevice.getProperty(IDevice.PROP_BUILD_API_LEVEL);
            if (apiStr != null) {
                apiLevel = Integer.parseInt(apiStr.trim());
            }
        } catch (Exception ignored) {}
        this.currentApiLevel = apiLevel;

        // 基于 API Level 分级：越高版本限制越多，需要更多重试
        int maxRetry;
        long retryDelay;
        if (isWifiAdb && apiLevel >= 36) {
            maxRetry = 10;     // WiFi + Android 16+: 最多重试
            retryDelay = 5000;
        } else if (isWifiAdb && apiLevel >= 35) {
            maxRetry = 8;      // WiFi + Android 15
            retryDelay = 4000;
        } else if (isWifiAdb || apiLevel >= 35) {
            maxRetry = 6;      // WiFi 或 Android 15+
            retryDelay = 3500;
        } else if (apiLevel >= 31) {
            maxRetry = 5;      // Android 12+
            retryDelay = 3000;
        } else {
            maxRetry = 4;      // 默认
            retryDelay = 2000;
        }

        while (retry <= maxRetry) {
            try {
                androidDriver = new AndroidDriver("http://127.0.0.1:" + uiaPort);
                break;
            } catch (Exception e) {
                log.sendStepLog(StepType.WARN, String.format("连接 UIAutomator2 Server 失败！重试第 %d 次...", retry + 1), "");
                out = e;
            }
            retry++;
            Thread.sleep(retryDelay);
        }
        if (androidDriver == null) {
            log.sendStepLog(StepType.ERROR, "连接 UIAutomator2 Server 失败！", "");
            setResultDetailStatus(ResultDetailStatus.FAIL);
            throw out;
        }

        // 超时策略：基于 API Level 和连接类型，不针对厂商
        int globalTimeout;
        if (isWifiAdb && apiLevel >= 35) {
            globalTimeout = 45000; // WiFi + Android 15+: 45 秒
        } else if (isWifiAdb && apiLevel >= 31) {
            globalTimeout = 40000; // WiFi + Android 12-14: 40 秒
        } else if (isWifiAdb || apiLevel >= 35) {
            globalTimeout = 35000; // WiFi 或 Android 15+: 35 秒
        } else if (apiLevel >= 31) {
            globalTimeout = 35000; // Android 12+: 35 秒
        } else {
            globalTimeout = 30000; // 默认 30 秒
        }
        androidDriver.getUiaClient().setGlobalTimeOut(globalTimeout);
        log.sendStepLog(StepType.INFO, "UIAutomator2 超时设置", "API Level: " + apiLevel + ", 超时: " + globalTimeout + "ms");
        log.sendStepLog(StepType.PASS, "连接 UIAutomator2 Server 成功", "");

        // 只有 Android 16+ (API 36+) 才主动启用 enforceXPath1
        // Android 15 及以下版本保持原有逻辑：遇到问题时由 tryEnableEnforceXPath1() 自动切换
        if (apiLevel >= 36) {
            try {
                JSONObject settings = new JSONObject();
                settings.put("enforceXPath1", true);
                androidDriver.setAppiumSettings(settings);
                enforceXPath1Enabled = true;
                log.sendStepLog(StepType.INFO, "Android 16+ 兼容性设置", "已启用 enforceXPath1");
            } catch (Exception e) {
                // 失败也没关系，运行时会自动重试
                LOGGER.debug("Pre-enable enforceXPath1 failed for device={}, will retry at runtime",
                           iDevice.getSerialNumber());
            }
        }

        // 获取屏幕的宽度与高度
        String screenSizeInfo = AndroidDeviceBridgeTool.getScreenSize(iDevice);
        String[] winSize = screenSizeInfo.split("x");
        screenWidth = BytesTool.getInt(winSize[0]);
        screenHeight = BytesTool.getInt(winSize[1]);
        log.androidInfo("Android", iDevice.getProperty(IDevice.PROP_BUILD_VERSION),
                iDevice.getSerialNumber(), iDevice.getProperty(IDevice.PROP_DEVICE_MANUFACTURER),
                iDevice.getProperty(IDevice.PROP_DEVICE_MODEL),
                screenSizeInfo);

        // 启动心跳保活线程，防止 UIAutomator2 Server 被系统杀死
        startUiaHeartbeat();
    }

    /**
     * 启动 UIAutomator2 心跳保活线程。
     * 定期发送轻量级请求（getSessionId）保持连接活跃，防止 Android 系统杀死空闲进程。
     * 注意：心跳线程只负责检测问题和记录日志，不主动触发自愈（避免死锁）。
     * 自愈由用户操作时的异常处理来触发。
     */
    private void startUiaHeartbeat() {
        stopUiaHeartbeat(); // 确保旧线程已停止
        uiaHeartbeatRunning = true;
        final Thread currentHeartbeatThread = new Thread(() -> {
            int consecutiveFailures = 0;
            LOGGER.info("UIAutomator2 heartbeat thread started for device: {}, interval={}ms",
                    iDevice != null ? iDevice.getSerialNumber() : "unknown", UIA_HEARTBEAT_INTERVAL_MS);
            while (uiaHeartbeatRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(UIA_HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // 使用本地变量避免并发修改问题
                final AndroidDriver driver = androidDriver;
                if (!uiaHeartbeatRunning || driver == null) {
                    break;
                }
                try {
                    // 发送轻量级请求：获取 session ID（几乎不消耗资源）
                    String sessionId = driver.getSessionId();
                    if (sessionId != null && !sessionId.isEmpty()) {
                        consecutiveFailures = 0; // 重置失败计数
                        LOGGER.debug("UIAutomator2 heartbeat OK: device={}, sessionId={}",
                                iDevice.getSerialNumber(), sessionId);
                    } else {
                        consecutiveFailures++;
                        LOGGER.warn("UIAutomator2 heartbeat returned null session: device={}, failures={}",
                                iDevice.getSerialNumber(), consecutiveFailures);
                    }
                } catch (Exception e) {
                    consecutiveFailures++;
                    LOGGER.warn("UIAutomator2 heartbeat failed: device={}, failures={}, error={}",
                            iDevice.getSerialNumber(), consecutiveFailures, e.getMessage());

                    // 第一次失败时尝试唤醒屏幕，Android 16 可能因屏幕关闭而限制后台 instrument
                    if (consecutiveFailures == 1 && iDevice != null) {
                        try {
                            AndroidDeviceBridgeTool.wakeUpScreen(iDevice);
                        } catch (Exception ignored) {}
                    }

                    // 连续失败达到阈值，记录警告但不主动触发自愈（避免死锁）
                    // 自愈会在下次用户操作失败时由 recoverUiaIfNeeded 触发
                    if (consecutiveFailures >= UIA_HEARTBEAT_FAIL_THRESHOLD) {
                        LOGGER.warn("UIAutomator2 heartbeat failed {} times for device: {}, recovery will be triggered on next operation",
                                consecutiveFailures, iDevice.getSerialNumber());
                        // 标记需要恢复，但不在心跳线程中执行（避免死锁）
                        // 退出心跳循环，等待下次操作触发自愈后重建心跳
                        break;
                    }
                }
            }
            LOGGER.info("UIAutomator2 heartbeat thread stopped for device: {}",
                    iDevice != null ? iDevice.getSerialNumber() : "unknown");
        }, "uia-heartbeat-" + (iDevice != null ? iDevice.getSerialNumber() : "unknown"));
        uiaHeartbeatThread = currentHeartbeatThread;
        uiaHeartbeatThread.setDaemon(true);
        uiaHeartbeatThread.start();
    }

    /**
     * 停止 UIAutomator2 心跳保活线程
     */
    private void stopUiaHeartbeat() {
        uiaHeartbeatRunning = false;
        if (uiaHeartbeatThread != null) {
            uiaHeartbeatThread.interrupt();
            try {
                uiaHeartbeatThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            uiaHeartbeatThread = null;
        }
    }

    private static boolean isUiaConnectionError(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(Locale.ROOT);
                if (m.contains("read timed out")
                        || m.contains("sockettimeoutexception")
                        || m.contains("unexpected end of file")
                        || m.contains("connection refused")
                        || m.contains("socketexception")
                        || m.contains("broken pipe")
                        // Session 丢失/过期错误也需要自愈
                        || m.contains("session") && m.contains("not known")
                        || m.contains("session") && m.contains("does not exist")
                        || m.contains("invalid session")
                        || m.contains("nosuchsession")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isEnforceXPath1WorkaroundError(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            String msg = cur.getMessage();
            if (msg != null) {
                // Keep checks simple and robust across different driver/server versions.
                // Example:
                // Cannot set AccessibilityNodeInfo's field 'mSealed' to 'true'. Try changing the 'enforceXPath1' driver setting to 'true'...
                String m = msg.toLowerCase(Locale.ROOT);
                // Prefer matching the explicit hint if present; otherwise match the known hidden-api symptom.
                if (m.contains("enforcexpath1")
                        || (m.contains("accessibilitynodeinfo") && m.contains("msealed"))) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean tryEnableEnforceXPath1(String selector, Throwable cause) {
        if (enforceXPath1Enabled) return false;
        if (androidDriver == null) return false;
        if (!"xpath".equals(selector)) return false;
        if (!isEnforceXPath1WorkaroundError(cause)) return false;
        try {
            JSONObject settings = new JSONObject();
            settings.put("enforceXPath1", true);
            androidDriver.setAppiumSettings(settings);
            enforceXPath1Enabled = true;
            log.sendStepLog(StepType.WARN, "检测到 XPath2 兼容性异常，已自动切换为 XPath1", "settings.enforceXPath1=true");
            return true;
        } catch (Throwable ex) {
            // 不要因为兜底逻辑影响原始异常链路
            LOGGER.warn("Enable enforceXPath1 failed: device={}, err={}",
                    iDevice == null ? "" : iDevice.getSerialNumber(),
                    compactErrorMessage(ex));
            return false;
        }
    }

    /**
     * 将 XPath 2.0 的 matches() 函数转换为 XPath 1.0 兼容的 contains() 组合
     * 例如: matches(@text, '9.*1.*台.*湾.*版') -> contains(@text, '9') and contains(@text, '1') and contains(@text, '台') and contains(@text, '湾') and contains(@text, '版')
     */
    private String convertMatchesToContains(String xpath) {
        if (xpath == null || !xpath.contains("matches(")) {
            return xpath;
        }

        try {
            // 匹配 matches(@attr, 'pattern') 或 matches(@attr, "pattern")
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "matches\\s*\\(\\s*(@[\\w-]+)\\s*,\\s*['\"]([^'\"]+)['\"]\\s*\\)");
            java.util.regex.Matcher matcher = pattern.matcher(xpath);

            StringBuffer result = new StringBuffer();
            while (matcher.find()) {
                String attr = matcher.group(1);  // @text, @content-desc 等
                String regex = matcher.group(2); // 正则表达式

                // 将正则表达式按 .* 或 .+ 分割，提取关键词
                String[] parts = regex.split("\\.\\*|\\+\\*|\\.\\+|\\+\\+");
                List<String> keywords = new ArrayList<>();
                for (String part : parts) {
                    // 清理正则特殊字符，只保留实际文本
                    String cleaned = part.replaceAll("[\\^\\$\\[\\]\\(\\)\\{\\}\\|\\\\]", "").trim();
                    if (!cleaned.isEmpty()) {
                        keywords.add(cleaned);
                    }
                }

                if (keywords.isEmpty()) {
                    // 无法提取关键词，保持原 matches() 不变
                    matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
                    continue;
                }

                // 构建 contains() 组合
                StringBuilder containsExpr = new StringBuilder();
                for (int i = 0; i < keywords.size(); i++) {
                    if (i > 0) {
                        containsExpr.append(" and ");
                    }
                    containsExpr.append("contains(").append(attr).append(", '").append(keywords.get(i)).append("')");
                }

                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(containsExpr.toString()));
            }
            matcher.appendTail(result);

            return result.toString();
        } catch (Exception e) {
            LOGGER.warn("Failed to convert matches() to contains(): xpath={}, error={}", xpath, e.getMessage());
            return xpath;
        }
    }

    private static String compactErrorMessage(Throwable e) {
        if (e == null) return "";
        String msg = e.getMessage();
        if (msg != null && msg.trim().length() > 0) return msg.trim();
        return e.getClass().getName();
    }

    /**
     * Best-effort recovery for UIAutomator2: wake screen -> restart uia server -> recreate driver.
     * This is intentionally conservative to avoid infinite loops; only triggers for connection-style errors.
     */
    private void recoverUiaIfNeeded(Throwable e) {
        if (iDevice == null) return;
        if (!isUiaConnectionError(e)) return;
        long now = System.currentTimeMillis();
        // 自愈冷却时间：自愈需要时间（重启 Server + 重建连接），太频繁会适得其反
        long sinceLast = now - lastUiaRecoverTimeMs;
        if (sinceLast < UIA_RECOVER_COOLDOWN_MS) {
            LOGGER.debug("Skip UIA recover (cooldown): device={}, sinceLast={}ms, cooldown={}ms, reason={}",
                    iDevice.getSerialNumber(), sinceLast, UIA_RECOVER_COOLDOWN_MS, compactErrorMessage(e));
            return;
        }
        lastUiaRecoverTimeMs = now;
        String reason = compactErrorMessage(e);
        log.sendStepLog(StepType.WARN, "UIAutomator2 响应超时，尝试自愈", reason);
        LOGGER.warn("UIA recover start: device={}, uiaPort={}, reason={}", iDevice.getSerialNumber(), uiaPort, reason);
        try {
            AndroidDeviceBridgeTool.wakeUpScreen(iDevice);
            Thread.sleep(600);
        } catch (Exception ignored) {
        }
        // If we know the local forwarded port, restart uia server and driver
        if (uiaPort > 0) {
            try {
                // 先停止心跳线程，避免在恢复过程中干扰
                stopUiaHeartbeat();

                // close old driver best-effort with timeout; it may already be half-broken
                try {
                    if (androidDriver != null) {
                        final AndroidDriver driverToClose = androidDriver;
                        androidDriver = null; // 立即置 null，让心跳线程能检测到
                        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                        java.util.concurrent.Future<?> future = executor.submit(() -> {
                            try {
                                driverToClose.closeDriver();
                            } catch (Exception ignored) {
                            }
                        });
                        try {
                            future.get(3, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (java.util.concurrent.TimeoutException te) {
                            future.cancel(true);
                        } finally {
                            executor.shutdownNow();
                        }
                    }
                } catch (Exception ignored) {
                }
                long restartStart = System.currentTimeMillis();
                int startedPort = AndroidDeviceBridgeTool.startUiaServer(iDevice, uiaPort);
                long restartCost = System.currentTimeMillis() - restartStart;
                LOGGER.warn("UIA server restarted: device={}, requestedPort={}, startedPort={}, cost={}ms",
                        iDevice.getSerialNumber(), uiaPort, startedPort, restartCost);
                // recreate driver
                long driverStart = System.currentTimeMillis();
                startAndroidDriver(iDevice, uiaPort);
                long driverCost = System.currentTimeMillis() - driverStart;
                LOGGER.warn("UIA driver recreated: device={}, port={}, cost={}ms", iDevice.getSerialNumber(), uiaPort, driverCost);

                // 自愈后等待连接稳定 - Android 16 需要更长时间
                try {
                    long stabilizeWait = (currentApiLevel >= 36) ? 3500 : 1500;
                    if (isWifiAdbConnection && currentApiLevel >= 36) {
                        stabilizeWait = 5000; // WiFi + Android 16: 更长等待
                    }
                    Thread.sleep(stabilizeWait);
                    // 额外验证连接是否真正可用
                    if (androidDriver != null) {
                        androidDriver.getSessionId();
                        // Android 16 再次验证，确保稳定
                        if (currentApiLevel >= 36) {
                            Thread.sleep(500);
                            androidDriver.getPageSource(); // 更重的验证
                        }
                    }
                } catch (Exception ignored) {}

                log.sendStepLog(StepType.INFO, "UIAutomator2 自愈完成", "");
            } catch (Exception ex) {
                log.sendStepLog(StepType.WARN, "UIAutomator2 自愈失败", compactErrorMessage(ex));
                LOGGER.warn("UIA recover failed: device={}, uiaPort={}, reason={}, error={}",
                        iDevice.getSerialNumber(), uiaPort, reason, compactErrorMessage(ex));
            }
        } else {
            LOGGER.warn("UIA recover skipped: device={}, uiaPort is unknown/0, reason={}", iDevice.getSerialNumber(), reason);
        }
    }

    public void switchWindowMode(HandleContext handleContext, boolean isMulti) throws SonicRespException {
        handleContext.setStepDes("切换窗口模式");
        handleContext.setDetail("切换为： " + (isMulti ? "多窗口模式" : "单窗口模式"));
        JSONObject settings = new JSONObject();
        settings.put("enableMultiWindows", isMulti);
        androidDriver.setAppiumSettings(settings);
    }

    public void switchIgnoreMode(HandleContext handleContext, boolean isIgnore) throws SonicRespException {
        handleContext.setStepDes("切换忽略不重要视图模式");
        handleContext.setDetail("切换为： " + (isIgnore ? "忽略" : "不忽略"));
        JSONObject settings = new JSONObject();
        settings.put("ignoreUnimportantViews", isIgnore);
        androidDriver.setAppiumSettings(settings);
    }

    public void switchVisibleMode(HandleContext handleContext, boolean isVisible) throws SonicRespException {
        handleContext.setStepDes("切换Invisible控件展示");
        handleContext.setDetail("切换为： " + (isVisible ? "显示" : "隐藏"));
        JSONObject settings = new JSONObject();
        settings.put("allowInvisibleElements", isVisible);
        androidDriver.setAppiumSettings(settings);
    }

    /**
     * @author ZhouYiXun
     * @des 关闭driver
     * @date 2021/8/16 20:21
     */
    public void closeAndroidDriver() {
        // 首先停止心跳保活线程
        stopUiaHeartbeat();

        // 关闭 driver 的最大超时时间（秒），防止 HTTP 请求阻塞导致锁无法释放
        final int CLOSE_DRIVER_TIMEOUT_SECONDS = 10;

        try {
            if (chromeDriver != null) {
                chromeDriver.quit();
            }
            if (pocoDriver != null) {
                pocoDriver.closeDriver();
                if (iDevice != null) {
                    AndroidDeviceBridgeTool.removeForward(iDevice, pocoPort, targetPort);
                }
                pocoDriver = null;
            }
            if (androidDriver != null) {
                // 使用超时包装，防止 closeDriver 阻塞过久
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                // 保存引用，因为后面要置 null
                final AndroidDriver driverToClose = androidDriver;
                java.util.concurrent.Future<?> future = executor.submit(() -> {
                    try {
                        driverToClose.closeDriver();
                    } catch (Exception e) {
                        // 忽略关闭时的异常，仅记录日志
                    }
                });
                // 立即置 null，让心跳线程能检测到 driver 已关闭
                androidDriver = null;
                try {
                    future.get(CLOSE_DRIVER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    log.sendStepLog(StepType.PASS, "退出连接设备", "");
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    log.sendStepLog(StepType.WARN, "关闭 Driver 超时，强制跳过",
                            "closeDriver 操作超过 " + CLOSE_DRIVER_TIMEOUT_SECONDS + " 秒未响应");
                } catch (Exception e) {
                    log.sendStepLog(StepType.WARN, "关闭 Driver 异常", e.getMessage());
                } finally {
                    executor.shutdownNow();
                }
            }
        } catch (Exception e) {
            log.sendStepLog(StepType.WARN, "测试终止异常！请检查设备连接状态", e.fillInStackTrace().toString());
            setResultDetailStatus(ResultDetailStatus.WARN);
        } finally {
            try {
                if (iDevice != null) {
                    // 在设备端彻底停止 instrument 进程，防止 Android 16 上残留进程累积
                    AndroidDeviceBridgeTool.executeCommand(iDevice, "am force-stop io.appium.uiautomator2.server", 5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    AndroidDeviceBridgeTool.executeCommand(iDevice, "am force-stop io.appium.uiautomator2.server.test", 5000, java.util.concurrent.TimeUnit.MILLISECONDS);

                    String uiaThreadKey = String.format("%s-uia-thread", iDevice.getSerialNumber());
                    Thread s = AndroidThreadMap.getMap().get(uiaThreadKey);
                    if (s != null) {
                        s.interrupt();
                        // 从 Map 中移除线程引用，防止内存泄漏
                        AndroidThreadMap.getMap().remove(uiaThreadKey);
                    }
                    AndroidDeviceBridgeTool.clearWebView(iDevice);
                    // 清理 UIAutomator2 端口转发，防止资源泄漏
                    if (uiaPort > 0) {
                        AndroidDeviceBridgeTool.removeForward(iDevice, uiaPort, 6790);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void waitDevice(int waitCount) {
        log.sendStepLog(StepType.INFO, "设备非空闲状态！第" + waitCount + "次等待连接...", "");
    }

    public void waitDeviceTimeOut() {
        log.sendStepLog(StepType.ERROR, "等待设备超时！测试跳过！", "");
        //测试标记为异常
        setResultDetailStatus(ResultDetailStatus.WARN);
    }

    public String getUdId() {
        return iDevice.getSerialNumber();
    }

    public AndroidDriver getAndroidDriver() {
        return androidDriver;
    }

    public JSONObject getGlobalParams() {
        return globalParams;
    }

    public IDevice getiDevice() {
        return iDevice;
    }

    private boolean isLockStatus = false;

    /**
     * 通用 PIN 处理（推荐在自定义脚本里调用）：
     * - 锁屏(Keyguard)：仅此处允许上滑；上滑后仍锁才输入 PIN
     * - 安装/策略弹出的 ConfirmLockPassword：不做上滑；优先用无坐标输入（text/keyevent），必要时再用 UIA 点击/网格点兜底
     *
     * @return true 表示执行了“可能需要的 PIN 流程”且页面不再停留在锁屏/确认页；false 表示无需 PIN 或失败
     */
    public boolean enterPinIfNeededByUia(String pin) {
        if (iDevice == null) return false;

        // 1) Keyguard lock screen (ADB only)
        if (AndroidDeviceBridgeTool.isKeyguardLocked(iDevice)) {
            return AndroidDeviceBridgeTool.unlockByPinIfLocked(iDevice, pin);
        }

        // 2) Credential confirmation screen
        if (!AndroidDeviceBridgeTool.isCredentialConfirmationShowing(iDevice)) {
            return false;
        }
        if (androidDriver == null) {
            // fallback without driver
            return AndroidDeviceBridgeTool.enterPinIfCredentialConfirmation(iDevice, pin);
        }

        String purePin = pin == null ? "" : pin.trim();
        if (purePin.length() == 0) return false;
        for (int i = 0; i < purePin.length(); i++) {
            char c = purePin.charAt(i);
            if (c < '0' || c > '9') return false;
        }

        // Prefer non-coordinate input first to avoid "tap offset" on some OEM keypads.
        // 1) best-effort focus input field
        focusCredentialInputIfExists();
        // 2) clear possible leftover input (avoid appending digits on retries)
        clearFocusedInputBestEffort(Math.min(16, purePin.length() + 8));
        // 3) type digits (no enter key - system auto-submits after PIN input)
        AndroidDeviceBridgeTool.inputDigitsByText(iDevice, purePin, false);
        postSubmitForCredentialConfirmation();
        if (!AndroidDeviceBridgeTool.isCredentialConfirmationShowing(iDevice)) return true;

        // Retry with keyevents (some ROMs block "input text")
        focusCredentialInputIfExists();
        clearFocusedInputBestEffort(Math.min(16, purePin.length() + 8));
        AndroidDeviceBridgeTool.inputPinByKeyeventSlow(iDevice, purePin, false, 120);
        postSubmitForCredentialConfirmation();
        if (!AndroidDeviceBridgeTool.isCredentialConfirmationShowing(iDevice)) return true;

        // If still showing, try UIA-click digit buttons (more stable than grid taps).
        if (tapPinByUiaDigitButtons(purePin)) {
            postSubmitForCredentialConfirmation();
            if (!AndroidDeviceBridgeTool.isCredentialConfirmationShowing(iDevice)) return true;
        }

        // Last resort: grid tap by keyboard bounds (may be inaccurate on some ROMs).
        try {
            AndroidElement keyboard = androidDriver.findElement(AndroidSelector.Id, "com.android.settings:id/keyboard_num", 1);
            String bounds = keyboard.getAttribute("bounds");
            if (bounds != null && bounds.contains("][")) {
                tapPinByKeyboardBounds(bounds, purePin);
                postSubmitForCredentialConfirmation();
                return !AndroidDeviceBridgeTool.isCredentialConfirmationShowing(iDevice);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void clickIfExists(String id) {
        try {
            AndroidElement e = androidDriver.findElement(AndroidSelector.Id, id, 1);
            if (e != null) e.click();
        } catch (Throwable ignored) {
        }
    }

    private void focusCredentialInputIfExists() {
        try {
            AndroidElement input = androidDriver.findElement(AndroidSelector.Id, "com.android.settings:id/input_view", 1);
            if (input != null) {
                input.click();
                return;
            }
        } catch (Throwable ignored) {
        }
        // Some ROMs use different ids; keep best-effort and non-fatal.
        try {
            AndroidElement input = androidDriver.findElement(AndroidSelector.Id, "com.android.settings:id/password_entry", 1);
            if (input != null) input.click();
        } catch (Throwable ignored) {
        }
    }

    private void clearFocusedInputBestEffort(int times) {
        int t = Math.max(0, times);
        for (int i = 0; i < t; i++) {
            AndroidDeviceBridgeTool.executeCommand(iDevice, "input keyevent 67", 2000, java.util.concurrent.TimeUnit.MILLISECONDS); // DEL
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void postSubmitForCredentialConfirmation() {
        // try confirm buttons if present (no enter keys - system auto-submits after PIN input)
        clickIfExists("com.android.settings:id/menu_continue");
        clickIfExists("android:id/button1");
        try {
            Thread.sleep(550);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Try clicking digit buttons by UIA (text/description). This avoids coordinate mapping issues on some ROMs.
     */
    private boolean tapPinByUiaDigitButtons(String pin) {
        if (pin == null || pin.isBlank()) return false;
        try {
            for (int i = 0; i < pin.length(); i++) {
                String d = String.valueOf(pin.charAt(i));
                AndroidElement key = null;
                try {
                    key = androidDriver.findElement(
                            AndroidSelector.UIAUTOMATOR,
                            "new UiSelector().text(\"" + d + "\").clickable(true)",
                            1
                    );
                } catch (Throwable ignored) {
                }
                if (key == null) {
                    try {
                        key = androidDriver.findElement(
                                AndroidSelector.UIAUTOMATOR,
                                "new UiSelector().description(\"" + d + "\").clickable(true)",
                                1
                        );
                    } catch (Throwable ignored) {
                    }
                }
                if (key == null) return false;
                key.click();
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void tapPinByKeyboardBounds(String bounds, String pin) {
        int[] b = parseBounds(bounds);
        if (b == null) return;
        int x1 = b[0], y1 = b[1], x2 = b[2], y2 = b[3];
        int w = Math.max(1, x2 - x1);
        int h = Math.max(1, y2 - y1);
        // Inset a bit to avoid container padding/borders on OEM ROMs.
        int insetX = Math.max(0, (int) (w * 0.04));
        int insetY = Math.max(0, (int) (h * 0.04));
        x1 += insetX;
        x2 -= insetX;
        y1 += insetY;
        y2 -= insetY;
        w = Math.max(1, x2 - x1);
        h = Math.max(1, y2 - y1);
        double cellW = w / 3.0;
        double cellH = h / 4.0;
        if (cellW <= 1 || cellH <= 1) return;

        for (int i = 0; i < pin.length(); i++) {
            int d = pin.charAt(i) - '0';
            int row;
            int col;
            if (d == 0) {
                row = 3;
                col = 1;
            } else {
                row = (d - 1) / 3;
                col = (d - 1) % 3;
            }
            int tapX = (int) Math.round(x1 + col * cellW + cellW / 2.0);
            int tapY = (int) Math.round(y1 + row * cellH + cellH / 2.0);
            AndroidDeviceBridgeTool.executeCommand(iDevice, "input tap " + tapX + " " + tapY, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static int[] parseBounds(String bounds) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]");
            java.util.regex.Matcher m = p.matcher(bounds);
            if (!m.find()) return null;
            return new int[]{
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4))
            };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @author ZhouYiXun
     * @des 设置测试状态
     * @date 2021/8/16 23:46
     */
    public void setResultDetailStatus(int status) {
        if (!isLockStatus && status > this.status) {
            this.status = status;
        }
    }

    public void sendStatus() {
        log.sendStatusLog(status);
    }

    //判断有无出错
    public int getStatus() {
        return status;
    }

    //调试每次重设状态
    public void resetResultDetailStatus() {
        status = 1;
    }

    /**
     * @return boolean
     * @author ZhouYiXun
     * @des 检测是否低电量
     * @date 2021/8/16 23:16
     */
    public boolean getBattery() {
        String battery = AndroidDeviceBridgeTool.executeCommand(iDevice, "dumpsys battery", 5000, java.util.concurrent.TimeUnit.MILLISECONDS);
        String realLevel = battery.substring(battery.indexOf("level")).trim();
        int level = BytesTool.getInt(realLevel.substring(7, realLevel.indexOf("\n")));
        if (level <= 10) {
            log.sendStepLog(StepType.ERROR, "设备电量过低!", "跳过本次测试...");
            return true;
        } else {
            return false;
        }
    }

    private int xpathId = 1;

    /**
     * @return com.alibaba.fastjson.JSONArray
     * @author ZhouYiXun
     * @des 获取页面xpath信息
     * @date 2021/8/16 23:16
     */
    public JSONArray getResource() {
        try {
            JSONArray elementList = new JSONArray();
            Document doc = Jsoup.parse(androidDriver.getPageSource());
            String xpath = "/hierarchy";
            elementList.addAll(getChildren(doc.body().children().get(0).children(), xpath));
            xpathId = 1;
            return elementList;
        } catch (SonicRespException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * @param xpath 父级节点xpath
     * @return com.alibaba.fastjson.JSONArray
     * @author ZhouYiXun
     * @des 获取子节点信息
     * @date 2021/8/16 23:36
     */
    public JSONArray getChildren(org.jsoup.select.Elements elements, String xpath) {
        JSONArray elementList = new JSONArray();
        for (int i = 0; i < elements.size(); i++) {
            JSONObject ele = new JSONObject();
            int tagCount = 0;
            int siblingIndex = 0;
            String indexXpath;
            for (int j = 0; j < elements.size(); j++) {
                if (elements.get(j).attr("class").equals(elements.get(i).attr("class"))) {
                    tagCount++;
                }
                if (i == j) {
                    siblingIndex = tagCount;
                }
            }
            if (tagCount == 1) {
                indexXpath = xpath + "/" + elements.get(i).attr("class");
            } else {
                indexXpath = xpath + "/" + elements.get(i).attr("class") + "[" + siblingIndex + "]";
            }
            ele.put("id", xpathId);
            xpathId++;
            ele.put("label", "<" + elements.get(i).attr("class") + ">");
            JSONObject detail = new JSONObject();
            detail.put("xpath", indexXpath);
            for (Attribute attr : elements.get(i).attributes()) {
                if (attr.getKey().equals("bounds")) {
                    String bounds = attr.getValue().replace("][", ":");
                    String pointStart = bounds.substring(1, bounds.indexOf(":"));
                    String pointEnd = bounds.substring(bounds.indexOf(":") + 1, bounds.indexOf("]"));
                    detail.put("bStart", pointStart);
                    detail.put("bEnd", pointEnd);
                }
                detail.put(attr.getKey(), attr.getValue());
            }
            ele.put("detail", detail);
            if (elements.get(i).children().size() > 0) {
                ele.put("children", getChildren(elements.get(i).children(), indexXpath));
            }
            elementList.add(ele);
        }
        return elementList;
    }

    public void install(HandleContext handleContext, String path) {
        handleContext.setStepDes("安装应用");
        path = TextHandler.replaceTrans(path, globalParams);
        handleContext.setDetail("App安装路径： " + path);
        File localFile = new File(path);
        try {
            if (path.contains("http")) {
                localFile = DownloadTool.download(path);
            }
            log.sendStepLog(StepType.INFO, "", "开始安装App，请稍后...");
            AndroidDeviceBridgeTool.install(iDevice, localFile.getAbsolutePath());
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void uninstall(HandleContext handleContext, String appPackage) {
        handleContext.setStepDes("卸载应用");
        appPackage = TextHandler.replaceTrans(appPackage, globalParams);
        try {
            String errorMessage = AndroidDeviceBridgeTool.uninstall(iDevice, appPackage);
            if (errorMessage == null) {
                handleContext.setDetail("App包名：" + appPackage + "卸载成功");
            } else {
                handleContext.setE(new Exception("uninstall app " + appPackage + " failed,errorMessage:" + errorMessage));
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    /**
     * @author ZhouYiXun
     * @des 终止app
     * @date 2021/8/16 23:46
     */
    public void terminate(HandleContext handleContext, String packageName) {
        handleContext.setStepDes("终止应用");
        packageName = TextHandler.replaceTrans(packageName, globalParams);
        handleContext.setDetail("应用包名： " + packageName);
        try {
            AndroidDeviceBridgeTool.forceStop(iDevice, packageName);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void appReset(HandleContext handleContext, String bundleId) {
        handleContext.setStepDes("清空App内存缓存");
        bundleId = TextHandler.replaceTrans(bundleId, globalParams);
        handleContext.setDetail("清空 " + bundleId);
        if (iDevice != null) {
            AndroidDeviceBridgeTool.executeCommand(iDevice, "pm clear " + bundleId);
        }
    }

    public void appAutoGrantPermissions(HandleContext handleContext, String packageName) {
        handleContext.setStepDes("自动授权应用权限");
        String targetPackageName = TextHandler.replaceTrans(packageName, globalParams);
        handleContext.setDetail("授权 " + targetPackageName);
        if (iDevice != null) {
            String dumpsysCommandStr = String.format("dumpsys package %s", targetPackageName);
            String getDetailCommandResult = AndroidDeviceBridgeTool.executeCommand(iDevice, dumpsysCommandStr);
            List<AndroidPermissionItem> allPermissionItems =
                    AndroidPermissionExtractor.extractPermissions(getDetailCommandResult,
                    Arrays.asList("install", "runtime"), true);
            allPermissionItems.stream().filter(permissionItem -> !permissionItem.isGranted())
                    .forEach(permissionItem -> {
                        String curPermission = permissionItem.getPermission();
                        String grandCommandStr = String.format("pm grant %s %s", targetPackageName, curPermission);
                        AndroidDeviceBridgeTool.executeCommand(iDevice, grandCommandStr);
                    });
        }
    }

    public void openApp(HandleContext handleContext, String appPackage) {
        handleContext.setStepDes("打开应用");
        appPackage = TextHandler.replaceTrans(appPackage, globalParams);
        handleContext.setDetail("App包名： " + appPackage);
        try {
            String result = AndroidDeviceBridgeTool.activateApp(iDevice, appPackage);
            if (result.contains("No activities found to run")) {
                throw new Exception(result);
            }
            targetPackage = appPackage;
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void rotateDevice(HandleContext handleContext, String text) {
        try {
            String s = "";
            handleContext.setDetail("");
            switch (text) {
                case "screenSub" -> {
                    s = "sub";
                    handleContext.setStepDes("左转屏幕");
                }
                case "screenAdd" -> {
                    s = "add";
                    handleContext.setStepDes("右转屏幕");
                }
                case "screenAbort" -> {
                    s = "abort";
                    handleContext.setStepDes("关闭自动旋转");
                }
            }
            AndroidDeviceBridgeTool.screen(iDevice, s);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void lock(HandleContext handleContext) {
        handleContext.setStepDes("锁定屏幕");
        handleContext.setDetail("");
        try {
            AndroidDeviceBridgeTool.pressKey(iDevice, AndroidKey.POWER);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void unLock(HandleContext handleContext) {
        handleContext.setStepDes("解锁屏幕");
        handleContext.setDetail("");
        try {
            // 使用 WAKEUP 键 (keyevent 224) 唤醒屏幕
            // WAKEUP 键只会唤醒屏幕，不会像 POWER 键那样切换屏幕状态
            // 即使屏幕已经是亮的，发送 WAKEUP 也不会锁屏
            AndroidDeviceBridgeTool.executeCommand(iDevice, "input keyevent 224");
            // 等待屏幕完全亮起
            Thread.sleep(500);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void airPlaneMode(HandleContext handleContext, boolean enable) {
        handleContext.setStepDes("切换飞行模式");
        handleContext.setDetail(enable ? "打开" : "关闭");
        try {
            if (enable) {
                AndroidDeviceBridgeTool.executeCommand(iDevice, "settings put global airplane_mode_on 1");
            } else {
                AndroidDeviceBridgeTool.executeCommand(iDevice, "settings put global airplane_mode_on 0");
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void wifiMode(HandleContext handleContext, boolean enable) {
        handleContext.setStepDes("开关WIFI");
        handleContext.setDetail(enable ? "打开" : "关闭");
        try {
            if (enable) {
                AndroidDeviceBridgeTool.executeCommand(iDevice, "svc wifi enable");
            } else {
                AndroidDeviceBridgeTool.executeCommand(iDevice, "svc wifi disable");
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void locationMode(HandleContext handleContext, boolean enable) {
        handleContext.setStepDes("切换位置服务");
        handleContext.setDetail("");
        try {
            if (enable) {
                AndroidDeviceBridgeTool.executeCommand(iDevice, "settings put secure location_providers_allowed +gps");
            } else {
                AndroidDeviceBridgeTool.executeCommand(iDevice, "settings put secure location_providers_allowed -gps");
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void asserts(HandleContext handleContext, String actual, String expect, String type) {
        handleContext.setStepDes("");
        try {
            switch (type) {
                case "assertEquals" -> {
                    handleContext.setDetail("真实值： " + actual + " 期望等于： " + expect);
                    handleContext.setStepDes("断言验证(相等)");
                    assertEquals(actual, expect);
                }
                case "assertNotEquals" -> {
                    handleContext.setDetail("真实值： " + actual + " 期望不等于： " + expect);
                    handleContext.setStepDes("断言验证(不相等)");
                    assertNotEquals(actual, expect);
                }
                case "assertTrue" -> {
                    handleContext.setDetail("真实值： " + actual + " 期望包含： " + expect);
                    handleContext.setStepDes("断言验证(包含)");
                    assertTrue(actual.contains(expect));
                }
                case "assertNotTrue" -> {
                    handleContext.setDetail("真实值： " + actual + " 期望不包含： " + expect);
                    handleContext.setStepDes("断言验证(不包含)");
                    assertFalse(actual.contains(expect));
                }
            }
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public String getText(HandleContext handleContext, String des, String selector, String pathValue) {
        String s = "";
        handleContext.setStepDes("获取" + des + "文本");
        handleContext.setDetail("获取" + selector + ":" + pathValue + "文本");
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                s = findEle(selector, pathValue).getText();
                log.sendStepLog(StepType.INFO, "", "文本获取结果: " + s);
                return s;
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("getText connection error -> recover and retry: device={}, selector={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, pathValue, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return s;
            }
        }
        return s;
    }

    public void toWebView(HandleContext handleContext, String packageName, String process) {
        packageName = TextHandler.replaceTrans(packageName, globalParams);
        process = TextHandler.replaceTrans(process, globalParams);
        handleContext.setStepDes("切换到" + packageName + " WebView");
        handleContext.setDetail("AndroidProcess: " + process);
        try {
            if (chromeDriver != null) {
                chromeDriver.quit();
            }
            String fullChromeVersion = AndroidDeviceBridgeTool.getFullChromeVersion(iDevice, packageName);
            if (fullChromeVersion != null) {
                String majorChromeVersion = AndroidDeviceBridgeTool.getMajorChromeVersion(fullChromeVersion);
                if (ChromeDriverMap.shouldUseJdkHttpClient(majorChromeVersion)) {
                    System.setProperty("webdriver.http.factory", "jdk-http-client");
                } else {
                    // 删除webdriver.http.factory配置选项的设置，否则测试完111以上的高版本，再切换回测试低版本会有问题
                    System.clearProperty("webdriver.http.factory");
                }
            }
            ChromeDriverService chromeDriverService = new ChromeDriverService.Builder().usingAnyFreePort()
                    .usingDriverExecutable(AndroidDeviceBridgeTool.getChromeDriver(iDevice, fullChromeVersion)).build();
            ChromeOptions chromeOptions = new ChromeOptions();
            chromeOptions.addArguments("--remote-allow-origins=*");
            chromeOptions.setExperimentalOption("androidDeviceSerial", iDevice.getSerialNumber());
            chromeOptions.setExperimentalOption("androidPackage", packageName);
            if (process != null && process.length() > 0) {
                chromeOptions.setExperimentalOption("androidProcess", process);
            }
            chromeOptions.setExperimentalOption("androidUseRunningApp", true);
            chromeDriver = new ChromeDriver(chromeDriverService, chromeOptions);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void click(HandleContext handleContext, String des, String selector, String pathValue) {
        handleContext.setStepDes("点击" + des);
        handleContext.setDetail("点击" + selector + ": " + pathValue);
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                findEle(selector, pathValue).click();
                return; // 成功则退出
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("click connection error -> recover and retry: device={}, selector={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, pathValue, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return;
            }
        }
    }

    public void sendKeys(HandleContext handleContext, String des, String selector, String pathValue, String keys) {
        keys = TextHandler.replaceTrans(keys, globalParams);
        handleContext.setStepDes("对" + des + "输入内容");
        handleContext.setDetail("对" + selector + ": " + pathValue + " 输入: " + keys);
        final String finalKeys = keys;
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                findEle(selector, pathValue).sendKeys(finalKeys);
                return; // 成功则退出
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("sendKeys connection error -> recover and retry: device={}, selector={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, pathValue, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return;
            }
        }
    }

    public void sendKeysByActions(HandleContext handleContext, String des, String selector, String pathValue, String keys) {
        keys = TextHandler.replaceTrans(keys, globalParams);
        handleContext.setStepDes("对" + des + "输入内容");
        handleContext.setDetail("对" + selector + ": " + pathValue + " 输入: " + keys);
        final String finalKeys = keys;
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AndroidElement androidElement = findEle(selector, pathValue);
                if (androidElement != null) {
                    androidElement.click();
                    androidDriver.sendKeys(finalKeys);
                }
                return; // 成功则退出
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("sendKeysByActions connection error -> recover and retry: device={}, selector={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, pathValue, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return;
            }
        }
    }

    public void getTextAndAssert(HandleContext handleContext, String des, String selector, String pathValue, String expect) {
        try {
            String s = getText(handleContext, des, selector, pathValue);
            if (handleContext.getE() != null) {
                return;
            }
            handleContext.setStepDes("验证" + des + "文本");
            handleContext.setDetail("验证" + selector + ":" + pathValue + "文本");
            try {
                expect = TextHandler.replaceTrans(expect, globalParams);
                assertEquals(s, expect);
                log.sendStepLog(StepType.INFO, "验证文本", "真实值： " + s + " 期望值： " + expect);
            } catch (AssertionError e) {
                handleContext.setE(e);
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void longPressPoint(HandleContext handleContext, String des, String xy, int time) {
        xy = TextHandler.replaceTrans(xy, globalParams);
        double x = Double.parseDouble(xy.substring(0, xy.indexOf(",")));
        double y = Double.parseDouble(xy.substring(xy.indexOf(",") + 1));
        int[] point = computedPoint(x, y);
        handleContext.setStepDes("长按" + des);
        handleContext.setDetail("长按坐标" + time + "毫秒 (" + point[0] + "," + point[1] + ")");
        try {
            AndroidTouchHandler.longPress(iDevice, point[0], point[1], time);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void keyCode(HandleContext handleContext, String key) {
        keyCode(handleContext, AndroidKey.valueOf(key).getCode());
    }

    public void keyCode(HandleContext handleContext, int key) {
        handleContext.setStepDes("按系统按键" + key + "键");
        handleContext.setDetail("");
        try {
            if (iDevice != null) {
                AndroidDeviceBridgeTool.pressKey(iDevice, key);
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void tap(HandleContext handleContext, String des, String xy) {
        xy = TextHandler.replaceTrans(xy, globalParams);
        double x = Double.parseDouble(xy.substring(0, xy.indexOf(",")));
        double y = Double.parseDouble(xy.substring(xy.indexOf(",") + 1));
        int[] point = computedPoint(x, y);
        handleContext.setStepDes("点击" + des);
        handleContext.setDetail("点击坐标(" + point[0] + "," + point[1] + ")");
        try {
            AndroidTouchHandler.tap(iDevice, point[0], point[1]);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void swipePoint(HandleContext handleContext, String des1, String xy1, String des2, String xy2) {
        // 让坐标系也支持变量替换
        xy1 = TextHandler.replaceTrans(xy1, globalParams);
        xy2 = TextHandler.replaceTrans(xy2, globalParams);
        double x1 = Double.parseDouble(xy1.substring(0, xy1.indexOf(",")));
        double y1 = Double.parseDouble(xy1.substring(xy1.indexOf(",") + 1));
        int[] point1 = computedPoint(x1, y1);
        double x2 = Double.parseDouble(xy2.substring(0, xy2.indexOf(",")));
        double y2 = Double.parseDouble(xy2.substring(xy2.indexOf(",") + 1));
        int[] point2 = computedPoint(x2, y2);
        handleContext.setStepDes("滑动拖拽" + des1 + "到" + des2);
        handleContext.setDetail("拖动坐标(" + point1[0] + "," + point1[1] + ")到(" + point2[0] + "," + point2[1] + ")");
        try {
            AndroidTouchHandler.swipe(iDevice, point1[0], point1[1], point2[0], point2[1]);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void swipe(HandleContext handleContext, String des, String selector, String pathValue, String des2, String selector2, String pathValue2) {
        handleContext.setStepDes("滑动拖拽" + des + "到" + des2);
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AndroidElement webElement = findEle(selector, pathValue);
                AndroidElement webElement2 = findEle(selector2, pathValue2);
                int x1 = webElement.getRect().getX();
                int y1 = webElement.getRect().getY();
                int x2 = webElement2.getRect().getX();
                int y2 = webElement2.getRect().getY();
                handleContext.setDetail("拖动坐标(" + x1 + "," + y1 + ")到(" + x2 + "," + y2 + ")");
                AndroidTouchHandler.swipe(iDevice, x1, y1, x2, y2);
                return; // 成功则退出
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("swipe connection error -> recover and retry: device={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(), compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return;
            }
        }
    }

    public void dragByPoint(HandleContext handleContext, String des1, String xy1, String des2, String xy2) {
        xy1 = TextHandler.replaceTrans(xy1, globalParams);
        xy2 = TextHandler.replaceTrans(xy2, globalParams);
        double x1 = Double.parseDouble(xy1.substring(0, xy1.indexOf(",")));
        double y1 = Double.parseDouble(xy1.substring(xy1.indexOf(",") + 1));
        int[] point1 = computedPoint(x1, y1);
        double x2 = Double.parseDouble(xy2.substring(0, xy2.indexOf(",")));
        double y2 = Double.parseDouble(xy2.substring(xy2.indexOf(",") + 1));
        int[] point2 = computedPoint(x2, y2);
        handleContext.setStepDes("模拟长按「" + des1 + "」然后拖拽移动到「" + des2 + "」松手");
        handleContext.setDetail("拖拽坐标(" + point1[0] + "," + point1[1] + ")的元素移动到(" + point2[0] + "," + point2[1] + ")");
        try {
            AndroidTouchHandler.drag(iDevice, point1[0], point1[1], point2[0], point2[1]);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void dragByEle(HandleContext handleContext, String des, String selector, String pathValue, String des2, String selector2, String pathValue2) {
        handleContext.setStepDes("模拟长按「" + des + "」然后拖拽移动到「" + des2 + "」松手");
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AndroidElement webElement = findEle(selector, pathValue);
                AndroidElement webElement2 = findEle(selector2, pathValue2);
                int x1 = webElement.getRect().getX();
                int y1 = webElement.getRect().getY();
                int x2 = webElement2.getRect().getX();
                int y2 = webElement2.getRect().getY();
                handleContext.setDetail("拖拽坐标(" + x1 + "," + y1 + ")的元素移动到(" + x2 + "," + y2 + ")");
                AndroidTouchHandler.drag(iDevice, x1, y1, x2, y2);
                return; // 成功则退出
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("dragByEle connection error -> recover and retry: device={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(), compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return;
            }
        }
    }

    public void motionEventByPoint(HandleContext handleContext, String des, String xy, String motionEventType) throws SonicRespException {
        double x = Double.parseDouble(xy.substring(0, xy.indexOf(",")));
        double y = Double.parseDouble(xy.substring(xy.indexOf(",") + 1));
        int[] point = computedPoint(x, y);
        handleContext.setStepDes("通过坐标" + des + "触发" + motionEventType + "-Motion事件");
        handleContext.setDetail("对坐标" + point[0] + "," + point[1] + String.format("执行Motion事件(%s)", motionEventType));
        try {
            AndroidTouchHandler.motionEvent(iDevice, motionEventType, point[0], point[1]);
        } catch (SonicRespException e) {
            handleContext.setE(e);
        }
    }

    public void motionEventByEle(HandleContext handleContext, String des, String selector, String pathValue, String motionEventType) throws SonicRespException {
        try {
            AndroidElement webElement = findEle(selector, pathValue);
            int x = webElement.getRect().getX();
            int y = webElement.getRect().getY();
            handleContext.setStepDes("通过" + des + "触发" + motionEventType + "-Motion事件");
            handleContext.setDetail("对坐标" + x + "," + y + String.format("执行Motion事件(%s)", motionEventType));
            AndroidTouchHandler.motionEvent(iDevice, motionEventType, x, y);
        } catch (SonicRespException e) {
            handleContext.setE(e);
        }
    }

    public void swipeByDefinedDirection(HandleContext handleContext, String slideDirection, int distance) throws Exception {
        String size = AndroidDeviceBridgeTool.getScreenSize(iDevice);
        String[] winSize = size.split("x");
        int width = BytesTool.getInt(winSize[0]);
        int height = BytesTool.getInt(winSize[1]);
        log.sendStepLog(StepType.INFO, "", "设备分辨率为：" + width + "x" + height);

        int centerX = (int) Math.ceil(width / 2.0);
        int centerY = (int) Math.ceil(height / 2.0);
        int targetY;
        int targetX;

        switch (slideDirection) {
            case "up" -> {
                handleContext.setStepDes("从设备中心位置向上滑动" + distance + "像素");
                targetY = centerY - distance;
                if (targetY < 0) {
                    targetY = 0;
                    log.sendStepLog(StepType.INFO, "", "滑动距离超出设备顶部，默认取顶部边界值" + "<" + targetY + ">");
                }
                try {
                    AndroidTouchHandler.swipe(iDevice, centerX, centerY, centerX, targetY);
                } catch (Exception e) {
                    handleContext.setE(e);
                }
                handleContext.setDetail("拖动坐标(" + centerX + "," + centerY + ")到(" + centerX + "," + targetY + ")");
            }
            case "down" -> {
                handleContext.setStepDes("从设备中心位置向下滑动" + distance + "像素");
                targetY = centerY + distance;
                if (targetY > height) {
                    targetY = height;
                    log.sendStepLog(StepType.INFO, "", "滑动距离超出设备底部，默认取底部边界值" + "<" + targetY + ">");
                }
                try {
                    AndroidTouchHandler.swipe(iDevice, centerX, centerY, centerX, targetY);
                } catch (Exception e) {
                    handleContext.setE(e);
                }
                handleContext.setDetail("拖动坐标(" + centerX + "," + centerY + ")到(" + centerX + "," + targetY + ")");
            }
            case "left" -> {
                handleContext.setStepDes("从设备中心位置向左滑动" + distance + "像素");
                targetX = centerX - distance;
                if (targetX < 0) {
                    targetX = 0;
                    log.sendStepLog(StepType.INFO, "", "滑动距离超出设备左侧，默认取左侧边界值" + "<" + targetX + ">");
                }
                try {
                    AndroidTouchHandler.swipe(iDevice, centerX, centerY, targetX, centerY);
                } catch (Exception e) {
                    handleContext.setE(e);
                }
                handleContext.setDetail("拖动坐标(" + centerX + "," + centerY + ")到(" + targetX + "," + centerY + ")");
            }
            case "right" -> {
                handleContext.setStepDes("从设备中心位置向右滑动" + distance + "像素");
                targetX = centerX + distance;
                if (targetX > width) {
                    targetX = width;
                    log.sendStepLog(StepType.INFO, "", "滑动距离超出设备右侧，默认取右侧边界值" + "<" + targetX + ">");
                }
                try {
                    AndroidTouchHandler.swipe(iDevice, centerX, centerY, targetX, centerY);
                } catch (Exception e) {
                    handleContext.setE(e);
                }
                handleContext.setDetail("拖动坐标(" + centerX + "," + centerY + ")到(" + targetX + "," + centerY + ")");
            }
            default ->
                    throw new Exception("Sliding in this direction is not supported. Only up/down/left/right are supported!");
        }
    }


    public void longPress(HandleContext handleContext, String des, String selector, String pathValue, int time) {
        handleContext.setStepDes("长按" + des);
        handleContext.setDetail("长按控件元素" + time + "毫秒 ");
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AndroidElement webElement = findEle(selector, pathValue);
                int x = webElement.getRect().getX();
                int y = webElement.getRect().getY();
                int width = webElement.getRect().getWidth();
                int height = webElement.getRect().getHeight();
                int centerX = x + (int) Math.ceil(width / 2.0);
                int centerY = y + (int) Math.ceil(height / 2.0);
                AndroidTouchHandler.longPress(iDevice, centerX, centerY, time);
                return; // 成功则退出
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("longPress connection error -> recover and retry: device={}, selector={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, pathValue, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return;
            }
        }
    }

    public void clear(HandleContext handleContext, String des, String selector, String pathValue) {
        handleContext.setStepDes("清空" + des);
        handleContext.setDetail("清空" + selector + ": " + pathValue);
        // 添加自愈重试逻辑：当 UIAutomator2 连接断开时，自愈后重试一次
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                findEle(selector, pathValue).clear();
                return; // 成功则退出
            } catch (Exception e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("clear connection error -> recover and retry: device={}, selector={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, pathValue, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue; // 自愈后重试
                }
                handleContext.setE(e);
                return;
            }
        }
    }

    public void scrollToEle(HandleContext handleContext, String des, String selector, String pathValue, int maxTryTime,
                            String direction) {
        String directionStr = "down".equals(direction) ? "向下" : "向上";
        handleContext.setStepDes(directionStr + "滚动到控件 " + des + " 可见");

        final int xOffset = 20;
        boolean scrollToSuccess = false;
        int tryScrollNums = 0;

        while (tryScrollNums < maxTryTime) {
            try {
                AndroidElement w = findEle(selector, pathValue, 1);
                if (w != null) {
                    scrollToSuccess = true;
                    break;
                }
            } catch (Exception ignored) {
            }

            try {
                if ("up".equals(direction)) {
                    AndroidTouchHandler.swipe(iDevice, xOffset, screenHeight / 3, xOffset, screenHeight * 2 / 3, 1000);
                } else if ("down".equals(direction)) {
                    AndroidTouchHandler.swipe(iDevice, xOffset, screenHeight * 2 / 3, xOffset, screenHeight / 3, 1000);
                } else {
                    handleContext.setE(new Exception("未知的滚动到方向类型设置"));
                }
            } catch (Exception e) {
                handleContext.setE(e);
            }

            tryScrollNums++;
        }

        if (scrollToSuccess) {
            handleContext.setDetail("实际滚动：" + tryScrollNums + "次后控件" + des + "可见");
        } else {
            handleContext.setE(new Exception("尝试滚动：" + maxTryTime + "次后控件" + des + "依然不可见"));
        }
    }

    public void isExistEle(HandleContext handleContext, String des, String selector, String pathValue, boolean expect) {
        handleContext.setStepDes("判断控件 " + des + " 是否存在");
        handleContext.setDetail("期望值：" + (expect ? "存在" : "不存在"));
        boolean hasEle = false;
        try {
            AndroidElement w = findEle(selector, pathValue);
            if (w != null) {
                hasEle = true;
            }
        } catch (Exception ignored) {
        }
        try {
            assertEquals(hasEle, expect);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    /**
     * 断言元素存在个数的方法
     *
     * @param handleContext HandleContext
     * @param des           元素名称
     * @param selector      定位方式
     * @param pathValue     定位值
     * @param operation     操作类型
     * @param expectedCount 期望数量
     * @param elementType   元素的类型
     */
    public void isExistEleNum(HandleContext handleContext, String des, String selector, String pathValue, String operation
            , int expectedCount, int elementType) {
        handleContext.setStepDes("判断控件 " + des + " 存在的个数");
        List elementList = new ArrayList<>();
        switch (elementType) {
            case ANDROID_ELEMENT_TYPE:
                try {
                    elementList = findEleList(selector, pathValue);
                } catch (SonicRespException e) {
                    // 查找元素不存在时会抛异常
                } catch (Exception ignored) {
                }
                break;
            case WEB_ELEMENT_TYPE:
                try {
                    elementList = findWebEleList(selector, pathValue);
                } catch (SonicRespException e) {
                    // 查找元素不存在时会抛异常
                } catch (Exception ignored) {
                }
                break;
            case POCO_ELEMENT_TYPE:
                try {
                    elementList = findPocoEleList(selector, pathValue);
                } catch (Throwable e) {
                    // 查找元素不存在时会抛异常
                }
                break;
            default:
                handleContext.setE(new AssertionError("未知的元素类型" + elementType + "，无法断言元素个数"));
                break;
        }
        String runDetail = "期望个数：" + operation + " " + expectedCount + "，实际个数：" + " " + (elementList == null ? 0 : elementList.size());
        handleContext.setDetail(runDetail);
        AssertUtil assertUtil = new AssertUtil();
        boolean isSuccess = assertUtil.assertElementNum(operation, expectedCount, elementList);
        try {
            assertTrue(isSuccess);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public void getUrl(HandleContext handleContext, String expect) {
        String title = chromeDriver.getCurrentUrl();
        handleContext.setStepDes("验证网页网址");
        handleContext.setDetail("网址：" + title + "，期望值：" + expect);
        try {
            assertEquals(title, expect);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public void getTitle(HandleContext handleContext, String expect) {
        String title = chromeDriver.getTitle();
        handleContext.setStepDes("验证网页标题");
        handleContext.setDetail("标题：" + title + "，期望值：" + expect);
        try {
            assertEquals(title, expect);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public void getActivity(HandleContext handleContext, String expect) {
        expect = TextHandler.replaceTrans(expect, globalParams);
        String currentActivity = AndroidDeviceBridgeTool.getCurrentActivity(iDevice);
        handleContext.setStepDes("验证当前Activity");
        handleContext.setDetail("activity：" + currentActivity + "，期望值：" + expect);
        try {
            assertEquals(currentActivity, expect);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public void getElementAttr(HandleContext handleContext, String des, String selector, String pathValue, String attr, String expect) {
        handleContext.setStepDes("验证控件 " + des + " 属性");
        handleContext.setDetail("属性：" + attr + "，期望值：" + expect);
        String attrValue;
        try {
            if (attr.equals("centerCoordinate")) {
                String bounds = findEle(selector, pathValue).getAttribute("bounds"); // [x1,y1][x2,y2]
                attrValue = getCenterCoordinate(bounds);
            } else {
                attrValue = findEle(selector, pathValue).getAttribute(attr);
            }
            log.sendStepLog(StepType.INFO, "", attr + " 属性获取结果: " + attrValue);
            try {
                assertEquals(attrValue, expect);
            } catch (AssertionError e) {
                handleContext.setE(e);
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void obtainElementAttr(HandleContext handleContext, String des, String selector, String pathValue,
                                  String attr, String variable) {
        handleContext.setStepDes("获取控件 " + des + " 属性到变量");
        handleContext.setDetail("目标属性：" + attr + "，目标变量：" + variable);
        String attrValue;
        try {
            // 自定义一个获取控件中心坐标的逻辑，方便通过控件获取一个坐标去做滑动、拖拽等操作
            if (attr.equals("centerCoordinate")) {
                String bounds = findEle(selector, pathValue).getAttribute("bounds"); // [x1,y1][x2,y2]
                attrValue = getCenterCoordinate(bounds);
            } else {
                attrValue = findEle(selector, pathValue).getAttribute(attr);
            }
            log.sendStepLog(StepType.INFO, "", attr + " 属性获取结果: " + attrValue);
            globalParams.put(variable, attrValue);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    private String getCenterCoordinate(String bounds) {
        String[] parts = bounds.split("]\\[");
        String[] xy = parts[0].substring(1).split(",");
        String[] xy2 = parts[1].substring(0, parts[1].length() - 1).split(",");
        return (Integer.parseInt(xy2[0]) + Integer.parseInt(xy[0])) / 2 + "," + (Integer.parseInt(xy2[1]) + Integer.parseInt(xy[1])) / 2;
    }

    public void logElementAttr(HandleContext handleContext, String des, String selector, String pathValue, String attr) {
        handleContext.setStepDes("日志输出控件 " + des + " 属性");
        handleContext.setDetail("目标属性：" + attr);
        List<String> attrs = JSON.parseArray(attr, String.class);
        StringBuilder logs = new StringBuilder();
        for (String a : attrs) {
            try {
                String attrValue = findEle(selector, pathValue).getAttribute(a);
                logs.append(String.format(" %s=%s,", a, attrValue));
            } catch (Exception e) {
                handleContext.setE(e);
            }
        }
        if (logs.length() > 0) {
            logs = new StringBuilder(logs.substring(0, logs.length() - 1));
        }
        log.sendStepLog(StepType.INFO, "", "属性获取结果:" + logs);
    }

    public void logPocoElementAttr(HandleContext handleContext, String des, String selector, String pathValue, String attr) {
        handleContext.setStepDes("日志输出控件 " + des + " 属性");
        handleContext.setDetail("目标属性：" + attr);
        List<String> attrs = JSON.parseArray(attr, String.class);
        StringBuilder logs = new StringBuilder();
        for (String a : attrs) {
            try {
                String attrValue = findPocoEle(selector, pathValue).getAttribute(a);
                logs.append(String.format(" %s=%s,", a, attrValue));
            } catch (Throwable e) {
                handleContext.setE(e);
            }
        }
        if (logs.length() > 0) {
            logs = new StringBuilder(logs.substring(0, logs.length() - 1));
        }
        log.sendStepLog(StepType.INFO, "", "属性获取结果:" + logs);
    }

    public void clickByImg(HandleContext handleContext, String des, String pathValue) {
        handleContext.setStepDes("点击图片" + des);
        handleContext.setDetail(pathValue);
        File file = null;
        if (pathValue.startsWith("http")) {
            try {
                file = DownloadTool.download(pathValue);
            } catch (Exception e) {
                handleContext.setE(e);
                return;
            }
        }
        FindResult findResult = null;
        try {
            SIFTFinder siftFinder = new SIFTFinder();
            findResult = siftFinder.getSIFTFindResult(file, getScreenToLocal(), true);
        } catch (Exception e) {
            log.sendStepLog(StepType.WARN, "SIFT图像算法出错，切换算法中...",
                    "");
        }
        if (findResult != null) {
            String url = UploadTools.upload(findResult.getFile(), "imageFiles");
            log.sendStepLog(StepType.INFO, "图片定位到坐标：(" + findResult.getX() + "," + findResult.getY() + ")  耗时：" + findResult.getTime() + " ms",
                    url);
        } else {
            log.sendStepLog(StepType.INFO, "SIFT算法无法定位图片，切换AKAZE算法中...",
                    "");
            try {
                AKAZEFinder akazeFinder = new AKAZEFinder();
                findResult = akazeFinder.getAKAZEFindResult(file, getScreenToLocal(), true);
            } catch (Exception e) {
                log.sendStepLog(StepType.WARN, "AKAZE图像算法出错，切换模版匹配算法中...",
                        "");
            }
            if (findResult != null) {
                String url = UploadTools.upload(findResult.getFile(), "imageFiles");
                log.sendStepLog(StepType.INFO, "图片定位到坐标：(" + findResult.getX() + "," + findResult.getY() + ")  耗时：" + findResult.getTime() + " ms",
                        url);
            } else {
                log.sendStepLog(StepType.INFO, "AKAZE算法无法定位图片，切换模版匹配算法中...",
                        "");
                try {
                    TemMatcher temMatcher = new TemMatcher();
                    findResult = temMatcher.getTemMatchResult(file, getScreenToLocal(), true);
                } catch (Exception e) {
                    log.sendStepLog(StepType.WARN, "模版匹配算法出错",
                            "");
                }
                if (findResult != null) {
                    String url = UploadTools.upload(findResult.getFile(), "imageFiles");
                    log.sendStepLog(StepType.INFO, "图片定位到坐标：(" + findResult.getX() + "," + findResult.getY() + ")  耗时：" + findResult.getTime() + " ms",
                            url);
                } else {
                    handleContext.setE(new Exception("图片定位失败！"));
                }
            }
        }
        if (findResult != null) {
            try {
                AndroidTouchHandler.tap(iDevice, findResult.getX(), findResult.getY());
            } catch (Exception e) {
                log.sendStepLog(StepType.ERROR, "点击" + des + "失败！", "");
                handleContext.setE(e);
            }
        }
    }

    public void readText(HandleContext handleContext, String language, String text) {
//        TextReader textReader = new TextReader();
//        String result = textReader.getTessResult(getScreenToLocal(), language);
//        log.sendStepLog(StepType.INFO, "",
//                "图像文字识别结果：<br>" + result);
//        String filter = result.replaceAll(" ", "");
        handleContext.setStepDes("图像文字识别");
        handleContext.setDetail("（该功能暂时关闭）期望包含文本：" + text);
//        if (!filter.contains(text)) {
//            handleDes.setE(new Exception("图像文字识别不通过！"));
//        }
    }

    public void toHandle(HandleContext handleContext, String params) throws Exception {
        params = TextHandler.replaceTrans(params, globalParams);
        handleContext.setStepDes("切换Handle");
        handleContext.setDetail("");
        Thread.sleep(1000);
        List<String> handles;
        try {
            handles = new ArrayList<>(chromeDriver.getWindowHandles());
        } catch (Exception e) {
            handleContext.setE(e);
            return;
        }
        if (BytesTool.isInt(params)) {
            int index = BytesTool.getInt(params);
            if (index <= handles.size() - 1 || handles.get(index) != null) {
                try {
                    chromeDriver.switchTo().window(handles.get(index));
                } catch (Exception ignored) {
                }
                handleContext.setDetail("切换到Handle:" + params);
                log.sendStepLog(StepType.INFO, "页面标题:" + chromeDriver.getTitle(), "");
                return;
            } else {
                handleContext.setE(new SonicRespException(String.format("Handle list size is [%d], can not get the [%d] item", handles.size(), index)));
            }
        } else {
            for (int i = 0; i < handles.size(); i++) {
                try {
                    chromeDriver.switchTo().window(handles.get(i));
                } catch (Exception ignored) {
                }
                if (chromeDriver.getTitle().contains(params) || chromeDriver.getCurrentUrl().contains(params)) {
                    handleContext.setDetail("切换到Handle:" + params);
                    log.sendStepLog(StepType.INFO, "页面标题:" + chromeDriver.getTitle(), "");
                    return;
                }
            }
        }
        handleContext.setE(new SonicRespException("Handle not found!"));
    }

    public File getScreenToLocal() {
        File folder = new File("test-output");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File output = new File(folder + File.separator + log.udId + Calendar.getInstance().getTimeInMillis() + ".png");
        try {
            byte[] bt = androidDriver.screenshot();
            FileImageOutputStream imageOutput = new FileImageOutputStream(output);
            imageOutput.write(bt, 0, bt.length);
            imageOutput.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return output;
    }

    public void checkImage(HandleContext handleContext, String des, String pathValue, double matchThreshold) {
        try {
            log.sendStepLog(StepType.INFO, "开始检测" + des + "兼容", "检测与当前设备截图相似度，期望相似度为" + matchThreshold + "%");
            File file = null;
            if (pathValue.startsWith("http")) {
                file = DownloadTool.download(pathValue);
            }
            SimilarityChecker similarityChecker = new SimilarityChecker();
            double score = similarityChecker.getSimilarMSSIMScore(file, getScreenToLocal(), true);
            handleContext.setStepDes("检测" + des + "图片相似度");
            handleContext.setDetail("相似度为" + score * 100 + "%");
            if (score == 0) {
                handleContext.setE(new Exception("图片相似度检测不通过！比对图片分辨率不一致！"));
            } else if (score < (matchThreshold / 100)) {
                handleContext.setE(new Exception("图片相似度检测不通过！expect " + matchThreshold + " but " + score * 100));
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void exceptionLog(Throwable e) {
        log.sendStepLog(StepType.WARN, "", "异常信息： " + e.fillInStackTrace().toString());
    }

    public void errorScreen() {
        int maxRetry = 2;
        for (int retry = 0; retry <= maxRetry; retry++) {
            try {
                if (retry > 0) {
                    // 尝试自愈后重试
                    Thread.sleep(500);
                }
                File folder = new File("test-output");
                if (!folder.exists()) {
                    folder.mkdirs();
                }
                byte[] bt = androidDriver.screenshot();
                File output = new File(folder + File.separator + UUID.randomUUID() + ".png");
                FileImageOutputStream imageOutput = new FileImageOutputStream(output);
                imageOutput.write(bt, 0, bt.length);
                imageOutput.close();
                log.sendStepLog(StepType.WARN, "获取异常截图", UploadTools
                        .upload(output, "imageFiles"));
                return; // 成功则退出
            } catch (Exception e) {
                if (retry == 0 && isUiaConnectionError(e)) {
                    // 首次失败且是连接错误，尝试自愈
                    recoverUiaIfNeeded(e);
                } else if (retry >= maxRetry) {
                    log.sendStepLog(StepType.ERROR, "捕获截图失败", e.getMessage() != null ? e.getMessage() : "");
                }
            }
        }
    }

    public void stepScreen(HandleContext handleContext) {
        handleContext.setStepDes("获取截图");
        handleContext.setDetail("");
        String url;
        // 高版本 Android 可能因为临时超时导致截图失败，增加重试机制
        int maxRetry = 3;
        Exception lastException = null;
        for (int retry = 0; retry < maxRetry; retry++) {
            try {
                // 首次失败后尝试唤醒屏幕
                if (retry > 0) {
                    AndroidDeviceBridgeTool.wakeUpScreen(iDevice);
                    Thread.sleep(500);
                    log.sendStepLog(StepType.WARN, "截图失败，重试第 " + retry + " 次...", "");
                }
                File folder = new File("test-output");
                if (!folder.exists()) {
                    folder.mkdirs();
                }
                File output = new File(folder + File.separator + iDevice.getSerialNumber() + Calendar.getInstance().getTimeInMillis() + ".png");
                byte[] bt = androidDriver.screenshot();
                FileImageOutputStream imageOutput = new FileImageOutputStream(output);
                imageOutput.write(bt, 0, bt.length);
                imageOutput.close();
                url = UploadTools.upload(output, "imageFiles");
                handleContext.setDetail(url);
                return; // 成功则退出
            } catch (Exception e) {
                lastException = e;
            }
        }
        // 所有重试都失败
        handleContext.setE(lastException);
    }

    public Set<String> getWebView() {
        Set<String> webView = new HashSet<>();
        List<JSONObject> result = AndroidDeviceBridgeTool.getWebView(iDevice);
        if (result.size() > 0) {
            for (JSONObject j : result) {
                webView.add(j.getString("package"));
            }
        }
        AndroidDeviceBridgeTool.clearWebView(iDevice);
        return webView;
    }

    public void pause(HandleContext handleContext, int time) {
        handleContext.setStepDes("强制等待");
        handleContext.setDetail("等待" + time + " ms");
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            handleContext.setE(e);
        }
    }

    public void runMonkey(HandleContext handleContext, JSONObject content, List<JSONObject> text) {
        handleContext.setStepDes("运行随机事件测试完毕");
        handleContext.setDetail("");
        String packageName = content.getString("packageName");
        int pctNum = content.getInteger("pctNum");
        if (!AndroidDeviceBridgeTool.executeCommand(iDevice, "pm list package").contains(packageName)) {
            log.sendStepLog(StepType.ERROR, "应用未安装！", "设备未安装 " + packageName);
            handleContext.setE(new Exception("未安装应用"));
            return;
        }
        JSONArray options = content.getJSONArray("options");
        WindowSize windowSize = null;
        try {
            windowSize = androidDriver.getWindowSize();
        } catch (SonicRespException e) {
            e.printStackTrace();
        }
        int width = windowSize.getWidth();
        int height = windowSize.getHeight();
        int sleepTime = 50;
        int systemEvent = 0;
        int tapEvent = 0;
        int longPressEvent = 0;
        int swipeEvent = 0;
        int navEvent = 0;
        boolean isOpenH5Listener = false;
        boolean isOpenPackageListener = false;
        boolean isOpenActivityListener = false;
        boolean isOpenNetworkListener = false;
        if (!options.isEmpty()) {
            for (Object j : options) {
                JSONObject jsonOption = JSON.parseObject(j.toString());
                if (jsonOption.getString("name").equals("sleepTime")) {
                    sleepTime = jsonOption.getInteger("value");
                }
                if (jsonOption.getString("name").equals("systemEvent")) {
                    systemEvent = jsonOption.getInteger("value");
                }
                if (jsonOption.getString("name").equals("tapEvent")) {
                    tapEvent = jsonOption.getInteger("value");
                }
                if (jsonOption.getString("name").equals("longPressEvent")) {
                    longPressEvent = jsonOption.getInteger("value");
                }
                if (jsonOption.getString("name").equals("swipeEvent")) {
                    swipeEvent = jsonOption.getInteger("value");
                }
                if (jsonOption.getString("name").equals("navEvent")) {
                    navEvent = jsonOption.getInteger("value");
                }
                if (jsonOption.getString("name").equals("isOpenH5Listener")) {
                    isOpenH5Listener = jsonOption.getBoolean("value");
                }
                if (jsonOption.getString("name").equals("isOpenPackageListener")) {
                    isOpenPackageListener = jsonOption.getBoolean("value");
                }
                if (jsonOption.getString("name").equals("isOpenActivityListener")) {
                    isOpenActivityListener = jsonOption.getBoolean("value");
                }
                if (jsonOption.getString("name").equals("isOpenNetworkListener")) {
                    isOpenNetworkListener = jsonOption.getBoolean("value");
                }
            }
        }
        int finalSleepTime = sleepTime;
        int finalTapEvent = tapEvent;
        int finalLongPressEvent = longPressEvent;
        int finalSwipeEvent = swipeEvent;
        int finalSystemEvent = systemEvent;
        int finalNavEvent = navEvent;
        Future<?> randomThread = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
                    log.sendStepLog(StepType.INFO, "", "随机事件数：" + pctNum +
                            "<br>目标应用：" + packageName
                            + "<br>用户操作时延：" + finalSleepTime + " ms"
                            + "<br>轻触事件权重：" + finalTapEvent
                            + "<br>长按事件权重：" + finalLongPressEvent
                            + "<br>滑动事件权重：" + finalSwipeEvent
                            + "<br>物理按键事件权重：" + finalSystemEvent
                            + "<br>系统事件权重：" + finalNavEvent
                    );
                    openApp(new HandleContext(), packageName);
                    int totalCount = finalSystemEvent + finalTapEvent + finalLongPressEvent + finalSwipeEvent + finalNavEvent;
                    for (int i = 0; i < pctNum; i++) {
                        try {
                            int random = new Random().nextInt(totalCount);
                            if (random < finalSystemEvent) {
                                int key = new Random().nextInt(4);
                                String keyType = switch (key) {
                                    case 0 -> "HOME";
                                    case 1 -> "BACK";
                                    case 2 -> "MENU";
                                    case 3 -> "APP_SWITCH";
                                    default -> "";
                                };
                                AndroidDeviceBridgeTool.pressKey(iDevice, AndroidKey.valueOf(keyType).getCode());
                            }
                            if (random >= finalSystemEvent && random < (finalSystemEvent + finalTapEvent)) {
                                int x = new Random().nextInt(width - 60) + 60;
                                int y = new Random().nextInt(height - 60) + 60;
                                AndroidTouchHandler.tap(iDevice, x, y);
                            }
                            if (random >= (finalSystemEvent + finalTapEvent) && random < (finalSystemEvent + finalTapEvent + finalLongPressEvent)) {
                                int x = new Random().nextInt(width - 60) + 60;
                                int y = new Random().nextInt(height - 60) + 60;
                                AndroidTouchHandler.longPress(iDevice, x, y, (new Random().nextInt(3) + 1) * 1000);
                            }
                            if (random >= (finalSystemEvent + finalTapEvent + finalLongPressEvent) && random < (finalSystemEvent + finalTapEvent + finalLongPressEvent + finalSwipeEvent)) {
                                int x1 = new Random().nextInt(width - 60) + 60;
                                int y1 = new Random().nextInt(height - 80) + 80;
                                int x2 = new Random().nextInt(width - 60) + 60;
                                int y2 = new Random().nextInt(height - 80) + 80;
                                AndroidTouchHandler.swipe(iDevice, x1, y1, x2, y2);
                            }
                            if (random >= (finalSystemEvent + finalTapEvent + finalLongPressEvent + finalSwipeEvent) && random < (finalSystemEvent + finalTapEvent + finalLongPressEvent + finalSwipeEvent + finalNavEvent)) {
                                int a = new Random().nextInt(2);
                                if (a == 1) {
                                    AndroidDeviceBridgeTool.executeCommand(iDevice, "svc wifi enable");
                                } else {
                                    AndroidDeviceBridgeTool.executeCommand(iDevice, "svc wifi disable");
                                }
                            }
                            Thread.sleep(finalSleepTime);
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                    }
                }
        );
        boolean finalIsOpenH5Listener = isOpenH5Listener;
        Future<?> H5Listener = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
                    if (finalIsOpenH5Listener) {
                        int h5Time = 0;
                        while (!randomThread.isDone()) {
                            try {
                                Thread.sleep(8000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            try {
                                if (androidDriver.findElementList(AndroidSelector.CLASS_NAME, "android.webkit.WebView").size() > 0) {
                                    h5Time++;
                                    AndroidDeviceBridgeTool.executeCommand(iDevice, "input keyevent 4");
                                } else {
                                    h5Time = 0;
                                }
                                if (h5Time >= 12) {
                                    AndroidDeviceBridgeTool.forceStop(iDevice, packageName);
                                    h5Time = 0;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
        );
        boolean finalIsOpenPackageListener = isOpenPackageListener;
        Future<?> packageListener = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
                    if (finalIsOpenPackageListener) {
                        while (!randomThread.isDone()) {
                            int waitTime = 0;
                            while (waitTime <= 10 && (!randomThread.isDone())) {
                                try {
                                    Thread.sleep(5000);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                if (!AndroidDeviceBridgeTool.getCurrentActivity(iDevice).contains(packageName)) {
                                    AndroidDeviceBridgeTool.activateApp(iDevice, packageName);
                                }
                                waitTime++;
                            }
                            AndroidDeviceBridgeTool.activateApp(iDevice, packageName);
                        }
                    }
                }
        );
        boolean finalIsOpenActivityListener = isOpenActivityListener;
        Future<?> activityListener = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
                    if (finalIsOpenActivityListener) {
                        if (text.isEmpty()) {
                            return;
                        }
                        Set<String> blackList = new HashSet<>();
                        for (JSONObject activities : text) {
                            blackList.add(activities.getString("name"));
                        }
                        while (!randomThread.isDone()) {
                            try {
                                Thread.sleep(8000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (blackList.contains(AndroidDeviceBridgeTool.getCurrentActivity(iDevice))) {
                                AndroidDeviceBridgeTool.executeCommand(iDevice, "input keyevent 4");
                            } else continue;
                            try {
                                Thread.sleep(8000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (blackList.contains(AndroidDeviceBridgeTool.getCurrentActivity(iDevice))) {
                                AndroidDeviceBridgeTool.forceStop(iDevice, packageName);
                            }
                        }
                    }
                }
        );
        boolean finalIsOpenNetworkListener = isOpenNetworkListener;
        Future<?> networkListener = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
                    if (finalIsOpenNetworkListener) {
                        while (!randomThread.isDone()) {
                            try {
                                Thread.sleep(8000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            AndroidDeviceBridgeTool.executeCommand(iDevice, "settings put global airplane_mode_on 0");
                            try {
                                Thread.sleep(8000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            AndroidDeviceBridgeTool.executeCommand(iDevice, "svc wifi enable");
                        }
                    }
                }
        );
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        log.sendStepLog(StepType.INFO, "", "测试目标包：" + packageName +
                (isOpenPackageListener ? "<br>应用包名监听器已开启..." : "") +
                (isOpenH5Listener ? "<br>H5页面监听器已开启..." : "") +
                (isOpenActivityListener ? "<br>黑名单Activity监听器..." : "") +
                (isOpenNetworkListener ? "<br>网络状态监听器已开启..." : ""));
        while (!randomThread.isDone() || (!packageListener.isDone()) || (!activityListener.isDone()) || (!networkListener.isDone()) || (!H5Listener.isDone())) {
        }
    }

    public void publicStep(HandleContext handleContext, String name, JSONArray stepArray) {
        log.sendStepLog(StepType.WARN, "公共步骤「" + name + "」开始执行", "");
        isLockStatus = true;
        for (Object publicStep : stepArray) {
            JSONObject stepDetail = (JSONObject) publicStep;
            try {
                SpringTool.getBean(StepHandlers.class)
                        .runStep(stepDetail, handleContext, (RunStepThread) Thread.currentThread());
            } catch (Throwable e) {
                handleContext.setE(e);
                break;
            }
        }
        isLockStatus = false;
        handleContext.setStepDes("IGNORE");
        if (handleContext.getE() != null && (!handleContext.getE().getMessage().startsWith("IGNORE:"))) {
            handleContext.setStepDes("执行公共步骤 " + name);
            handleContext.setDetail("");
            handleContext.setE(new SonicRespException("Exception thrown during child step running."));
        }
        log.sendStepLog(StepType.WARN, "公共步骤「" + name + "」执行完毕", "");
    }

    public void startPocoDriver(HandleContext handleContext, String engine, int port) {
        handleContext.setStepDes("启动PocoDriver");
        handleContext.setDetail("");
        if (pocoPort == 0) {
            pocoPort = PortTool.getPort();
        }
        targetPort = port;
        AndroidDeviceBridgeTool.forward(iDevice, pocoPort, targetPort);
        pocoDriver = new PocoDriver(PocoEngine.valueOf(engine), pocoPort);
    }

    private int intervalInit = 3000;
    private int retryInit = 3;

    public void setDefaultFindPocoElementInterval(HandleContext handleContext, Integer retry, Integer interval) {
        handleContext.setStepDes("设置查找POCO控件策略");
        handleContext.setDetail(String.format("Retry count: %d, retry interval: %d ms", retry, interval));
        if (retry != null) {
            retryInit = retry;
        }
        if (interval != null) {
            intervalInit = interval;
        }
    }

    public PocoElement findPocoEle(String selector, String pathValue) throws Throwable {
        PocoElement pocoElement = null;
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);
        int wait = 0;
        String errMsg = "";
        while (wait < retryInit) {
            wait++;
            pocoDriver.getPageSourceForXmlElement();
            try {
                switch (selector) {
                    case "poco" -> pocoElement = pocoDriver.findElement(PocoSelector.POCO, pathValue);
                    case "xpath" -> pocoElement = pocoDriver.findElement(PocoSelector.XPATH, pathValue);
                    case "cssSelector", "pocoIterator" ->
                            pocoElement = pocoDriver.findElement(PocoSelector.CSS_SELECTOR, pathValue);
                    default ->
                            log.sendStepLog(StepType.ERROR, "查找控件元素失败", "这个控件元素类型: " + selector + " 不存在!!!");
                }
                if (pocoElement != null) {
                    break;
                }
            } catch (Throwable e) {
                errMsg = e.getMessage();
            }
            if (wait < retryInit) {
                try {
                    Thread.sleep(intervalInit);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        if (pocoElement == null) {
            throw new SonicRespException(errMsg);
        }
        return pocoElement;
    }

    public List<PocoElement> findPocoEleList(String selector, String pathValue) throws Throwable {
        List<PocoElement> pocoElements = null;
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);
        int wait = 0;
        String errMsg = "";
        while (wait < retryInit) {
            wait++;
            pocoDriver.getPageSourceForXmlElement();
            try {
                switch (selector) {
                    case "poco" -> pocoElements = pocoDriver.findElements(PocoSelector.POCO, pathValue);
                    case "xpath" -> pocoElements = pocoDriver.findElements(PocoSelector.XPATH, pathValue);
                    case "cssSelector", "pocoIterator" ->
                            pocoElements = pocoDriver.findElements(PocoSelector.CSS_SELECTOR, pathValue);
                    default ->
                            log.sendStepLog(StepType.ERROR, "查找控件元素列表失败", "这个控件元素类型: " + selector + " 不存在!!!");
                }
                if (pocoElements != null) {
                    break;
                }
            } catch (Throwable e) {
                errMsg = e.getMessage();
            }
            if (wait < retryInit) {
                try {
                    Thread.sleep(intervalInit);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        if (pocoElements == null) {
            throw new SonicRespException(errMsg);
        }
        return pocoElements;
    }

    public void isExistPocoEle(HandleContext handleContext, String des, String selector, String value, boolean expect) {
        handleContext.setStepDes("判断控件 " + des + " 是否存在");
        handleContext.setDetail("期望值：" + (expect ? "存在" : "不存在"));
        boolean hasEle = false;
        try {
            PocoElement w = findPocoEle(selector, value);
            if (w != null) {
                hasEle = true;
            }
        } catch (Throwable ignored) {
        }
        try {
            assertEquals(hasEle, expect);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public void iteratorPocoElement(HandleContext handleContext, String des, String selector, String value) {

        List<PocoElement> pocoElements;

        if (handleContext.iteratorElement == null) {
            handleContext.setStepDes("迭代控件列表 " + des);
            try {
                pocoElements = findPocoEleList(selector, value);
                List<BaseElement> res = new ArrayList<>(pocoElements);
                handleContext.iteratorElement = res.iterator();
            } catch (Throwable e) {
                handleContext.setE(e);
                return;
            }
            log.sendStepLog(StepType.INFO, "", "迭代控件列表长度：" + pocoElements.size());
        }

        if (handleContext.iteratorElement.hasNext()) {
            handleContext.currentIteratorElement = handleContext.iteratorElement.next();
            PocoElement p = (PocoElement) handleContext.currentIteratorElement;
            handleContext.setStepDes("当前迭代控件：" + p.currentNodeSelector);
            handleContext.setDetail("");

        } else {
            handleContext.iteratorElement = null;
            handleContext.currentIteratorElement = null;
            log.sendStepLog(StepType.INFO, "", "迭代控件列表完毕...");
            handleContext.setStepDes("迭代控件列表 " + des);
            handleContext.setE(new Exception("exit while"));
        }
    }

    public void pocoClick(HandleContext handleContext, String des, String selector, String value) {
        handleContext.setStepDes("点击" + des);
        handleContext.setDetail("点击 " + value);
        try {
            PocoElement w = findPocoEle(selector, value);
            if (w != null) {
                List<Float> pos = w.getPayload().getPos();
                int[] realCoordinates = getTheRealCoordinatesOfPoco(pos.get(0), pos.get(1));
                AndroidTouchHandler.tap(iDevice, realCoordinates[0], realCoordinates[1]);
            } else {
                throw new SonicRespException(value + " not found!");
            }
        } catch (Throwable e) {
            handleContext.setE(e);
        }
    }

    public void pocoLongPress(HandleContext handleContext, String des, String selector, String value, int time) {
        handleContext.setStepDes("长按" + des);
        handleContext.setDetail("长按 " + value);
        try {
            PocoElement w = findPocoEle(selector, value);
            if (w != null) {
                List<Float> pos = w.getPayload().getPos();
                int[] realCoordinates = getTheRealCoordinatesOfPoco(pos.get(0), pos.get(1));
                AndroidTouchHandler.longPress(iDevice, realCoordinates[0], realCoordinates[1], time);
            } else {
                throw new SonicRespException(value + " not found!");
            }
        } catch (Throwable e) {
            handleContext.setE(e);
        }
    }

    public void pocoSwipe(HandleContext handleContext, String des, String selector, String value, String des2, String selector2, String value2) {
        handleContext.setStepDes("滑动拖拽" + des + "到" + des2);
        handleContext.setDetail("拖拽 " + value + " 到 " + value2);
        try {
            PocoElement w1 = findPocoEle(selector, value);
            PocoElement w2 = findPocoEle(selector2, value2);
            if (w1 != null && w2 != null) {
                List<Float> pos1 = w1.getPayload().getPos();
                int[] realCoordinates1 = getTheRealCoordinatesOfPoco(pos1.get(0), pos1.get(1));

                List<Float> pos2 = w2.getPayload().getPos();
                int[] realCoordinate2 = getTheRealCoordinatesOfPoco(pos2.get(0), pos2.get(1));
                AndroidTouchHandler.swipe(iDevice, realCoordinates1[0], realCoordinates1[1], realCoordinate2[0], realCoordinate2[1]);
            } else {
                throw new SonicRespException(value + " or " + value2 + " not found!");
            }
        } catch (Throwable e) {
            handleContext.setE(e);
        }
    }

    public void getPocoElementAttr(HandleContext handleContext, String des, String selector, String pathValue, String attr, String expect) {
        handleContext.setStepDes("验证控件 " + des + " 属性");
        handleContext.setDetail("属性：" + attr + "，期望值：" + expect);
        String attrValue = getPocoAttrValue(handleContext, selector, pathValue, attr);
        log.sendStepLog(StepType.INFO, "", attr + " 属性获取结果: " + attrValue);
        try {
            assertEquals(attrValue, expect);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public void obtainPocoElementAttr(HandleContext handleContext, String des, String selector, String pathValue,
                                  String attr, String variable) {
        handleContext.setStepDes("获取控件 " + des + " 属性到变量");
        handleContext.setDetail("目标属性：" + attr + "，目标变量：" + variable);
        try {
            String attrValue = getPocoAttrValue(handleContext, selector, pathValue, attr);
            log.sendStepLog(StepType.INFO, "", attr + " 属性获取结果: " + attrValue);
            globalParams.put(variable, attrValue);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    private String getPocoAttrValue(HandleContext handleContext, String selector, String pathValue, String attr) {
        String attrValue = "";
        try {
            PocoElement pocoElement = findPocoEle(selector, pathValue);
            switch (attr) {
                case "type" -> attrValue = pocoElement.getPayload().getType();
                case "layer" -> attrValue = pocoElement.getPayload().getLayer();
                case "tag" -> attrValue = pocoElement.getPayload().getTag();
                case "text" -> attrValue = pocoElement.getPayload().getText();
                case "texture" -> attrValue = pocoElement.getPayload().getTexture();
                case "_instanceId" -> attrValue = pocoElement.getPayload().get_instanceId() + "";
                case "name" -> attrValue = pocoElement.getPayload().getName();
                case "visible" -> attrValue = pocoElement.getPayload().getVisible().toString();
                case "clickable" -> attrValue = pocoElement.getPayload().getClickable().toString();
                case "_ilayer" -> attrValue = pocoElement.getPayload().get_ilayer() + "";
                case "global" -> attrValue = pocoElement.getPayload().getZOrders().getGlobal() + "";
                case "local" -> attrValue = pocoElement.getPayload().getZOrders().getLocal() + "";
                case "components" -> attrValue = pocoElement.getPayload().getComponents().toString();
                case "anchorPoint" -> attrValue = pocoElement.getPayload().getAnchorPoint().toString();
                case "scale" -> attrValue = pocoElement.getPayload().getScale().toString();
                case "size" -> attrValue = pocoElement.getPayload().getSize().toString();
                case "pos" -> attrValue = pocoElement.getPayload().getPos().toString();
            }
        } catch (Throwable e) {
            handleContext.setE(e);
        }
        return attrValue;
    }

    public String getPocoText(HandleContext handleContext, String des, String selector, String pathValue) {
        String s = "";
        handleContext.setStepDes("获取" + des + "文本");
        handleContext.setDetail("获取" + selector + ":" + pathValue + "文本");
        try {
            PocoElement w = findPocoEle(selector, pathValue);
            if (w != null) {
                s = w.getPayload().getText();
                log.sendStepLog(StepType.INFO, "", "文本获取结果: " + s);
            } else {
                throw new SonicRespException(pathValue + " not found!");
            }
        } catch (Throwable e) {
            handleContext.setE(e);
        }
        return s;
    }

    public void getPocoTextAndAssert(HandleContext handleContext, String des, String selector, String pathValue, String expect) {
        try {
            String s = getPocoText(handleContext, des, selector, pathValue);
            if (handleContext.getE() != null) {
                return;
            }
            handleContext.setStepDes("验证" + des + "文本");
            handleContext.setDetail("验证" + selector + ":" + pathValue + "文本");
            try {
                expect = TextHandler.replaceTrans(expect, globalParams);
                assertEquals(s, expect);
                log.sendStepLog(StepType.INFO, "验证文本", "真实值： " + s + " 期望值： " + expect);
            } catch (AssertionError e) {
                handleContext.setE(e);
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void setTheRealPositionOfTheWindow(HandleContext handleContext, String text) {
        JSONObject offsetValue = JSONObject.parseObject(text);
        handleContext.setStepDes("设置偏移量");
        handleContext.setDetail(String.format("offsetWidth: %d, offsetHeight: %d, windowWidth: %d, windowHeight: %d",
                offsetValue.getInteger("offsetWidth"),
                offsetValue.getInteger("offsetHeight"),
                offsetValue.getInteger("windowWidth"),
                offsetValue.getInteger("windowHeight")
        ));
        this.screenWindowPosition[0] = offsetValue.getInteger("offsetWidth");
        this.screenWindowPosition[1] = offsetValue.getInteger("offsetHeight");
        this.screenWindowPosition[2] = offsetValue.getInteger("windowWidth");
        this.screenWindowPosition[3] = offsetValue.getInteger("windowHeight");
    }

    public int[] getTheRealCoordinatesOfPoco(double pocoX, double pocoY) {
        int[] pos = new int[2];
        Integer screenOrientation = AndroidDeviceManagerMap.getRotationMap().get(iDevice.getSerialNumber());
        if (screenOrientation == null) {
            screenOrientation = AndroidDeviceBridgeTool.getOrientation(iDevice);
        }

        int width = screenWindowPosition[2], height = screenWindowPosition[3];

        if (width == 0 || height == 0) {
            String size = AndroidDeviceBridgeTool.getScreenSize(iDevice);
            String[] winSize = size.split("x");
            width = BytesTool.getInt(winSize[0]);
            height = BytesTool.getInt(winSize[1]);
            // Forget it, let's follow poco's window method.
            screenWindowPosition = AndroidDeviceBridgeTool.getDisplayOfAllScreen(iDevice, width, height, screenOrientation);
        }

        if (screenOrientation == 1 || screenOrientation == 3) {
            // x
            pos[0] = this.screenWindowPosition[1] + (int) (height * pocoX);
            // y
            pos[1] = this.screenWindowPosition[0] + (int) (width * pocoY);
        } else {
            // x = offsetX + width*pocoPosX
            pos[0] = this.screenWindowPosition[0] + (int) (width * pocoX);
            // y = offsetY + height*pocoPosY
            pos[1] = this.screenWindowPosition[1] + (int) (height * pocoY);
        }
        return pos;
    }

    public void freezeSource(HandleContext handleContext) {
        handleContext.setStepDes("冻结控件树");
        handleContext.setDetail("");
        try {
            pocoDriver.getPageSourceForJsonString();
            pocoDriver.freezeSource();
        } catch (Throwable e) {
            handleContext.setE(e);
        }
    }

    public void thawSource(HandleContext handleContext) {
        handleContext.setStepDes("解冻控件树");
        handleContext.setDetail("");
        pocoDriver.thawSource();
    }

    public void closePocoDriver(HandleContext handleContext) {
        handleContext.setStepDes("关闭PocoDriver");
        handleContext.setDetail("");
        if (pocoDriver != null) {
            pocoDriver.closeDriver();
            AndroidDeviceBridgeTool.removeForward(iDevice, pocoPort, targetPort);
            pocoDriver = null;
        }
    }

    public PocoDriver getPocoDriver() {
        return pocoDriver;
    }

    private int intervalWebInit = 3000;
    private int retryWebInit = 3;

    public void setDefaultFindWebViewElementInterval(HandleContext handleContext, Integer retry, Integer interval) {
        handleContext.setStepDes("设置查找WebView控件策略");
        handleContext.setDetail(String.format("Retry count: %d, retry interval: %d ms", retry, interval));
        if (retry != null) {
            retryWebInit = retry;
        }
        if (interval != null) {
            intervalWebInit = interval;
        }
    }

    public WebElement findWebEle(String selector, String pathValue) throws SonicRespException {
        WebElement we = null;
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);
        int wait = 0;
        String errMsg = "";
        while (wait < retryWebInit) {
            wait++;
            try {
                switch (selector) {
                    case "id" -> we = chromeDriver.findElement(By.id(pathValue));
                    case "name" -> we = chromeDriver.findElement(By.name(pathValue));
                    case "xpath" -> we = chromeDriver.findElement(By.xpath(pathValue));
                    case "cssSelector" -> we = chromeDriver.findElement(By.cssSelector(pathValue));
                    case "className" -> we = chromeDriver.findElement(By.className(pathValue));
                    case "tagName" -> we = chromeDriver.findElement(By.tagName(pathValue));
                    case "linkText" -> we = chromeDriver.findElement(By.linkText(pathValue));
                    case "partialLinkText" -> we = chromeDriver.findElement(By.partialLinkText(pathValue));
                    case "cssSelectorAndText" -> we = getWebElementByCssAndText(pathValue);
                    default ->
                            log.sendStepLog(StepType.ERROR, "查找控件元素失败", "这个控件元素类型: " + selector + " 不存在!!!");
                }
                if (we != null) {
                    break;
                }
            } catch (Throwable e) {
                errMsg = e.getMessage();
            }
            if (wait < retryWebInit) {
                try {
                    Thread.sleep(intervalWebInit);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        if (we == null) {
            throw new SonicRespException(errMsg);
        }
        return we;
    }

    public List<WebElement> findWebEleList(String selector, String pathValue) throws SonicRespException {
        List<WebElement> we = new ArrayList<>();
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);
        int wait = 0;
        String errMsg = "";
        while (wait < retryWebInit) {
            wait++;
            try {
                switch (selector) {
                    case "id" -> we = chromeDriver.findElements(By.id(pathValue));
                    case "name" -> we = chromeDriver.findElements(By.name(pathValue));
                    case "xpath" -> we = chromeDriver.findElements(By.xpath(pathValue));
                    case "cssSelector" -> we = chromeDriver.findElements(By.cssSelector(pathValue));
                    case "className" -> we = chromeDriver.findElements(By.className(pathValue));
                    case "tagName" -> we = chromeDriver.findElements(By.tagName(pathValue));
                    case "linkText" -> we = chromeDriver.findElements(By.linkText(pathValue));
                    case "partialLinkText" -> we = chromeDriver.findElements(By.partialLinkText(pathValue));
                    default ->
                            log.sendStepLog(StepType.ERROR, "查找控件元素失败", "这个控件元素类型: " + selector + " 不存在!!!");
                }
                if (we != null) {
                    break;
                }
            } catch (Throwable e) {
                errMsg = e.getMessage();
            }
            if (wait < retryWebInit) {
                try {
                    Thread.sleep(intervalWebInit);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        if (we == null) {
            throw new SonicRespException(errMsg);
        }
        return we;
    }

    public AndroidElement findEle(String selector, String pathValue) throws SonicRespException {
        return findEle(selector, pathValue, null);
    }

    public AndroidElement findEle(String selector, String pathValue, Integer retryTime) throws SonicRespException {
        AndroidElement we = null;
        String originalPathValue = pathValue;
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);
        // 调试日志：检查变量替换是否生效
        if (!originalPathValue.equals(pathValue)) {
            // 输出详细信息，包括字符串长度，用于排查隐藏字符问题
            log.sendStepLog(StepType.INFO, "变量替换成功",
                "原始值[长度=" + originalPathValue.length() + "]: " + originalPathValue +
                " -> 替换后[长度=" + pathValue.length() + "]: " + pathValue);
            // 输出实际传给驱动的 xpath
            log.sendStepLog(StepType.INFO, "实际查找xpath", "selector=" + selector + ", pathValue=" + pathValue);
        } else if (originalPathValue.contains("{{")) {
            log.sendStepLog(StepType.WARN, "变量替换失败", "原始值: " + originalPathValue + ", globalParams: " + globalParams.toJSONString());
        }

        // Android 16+ 使用 XPath1，需要将 matches() 转换为 contains()
        if ("xpath".equals(selector) && enforceXPath1Enabled && pathValue != null && pathValue.contains("matches(")) {
            String converted = convertMatchesToContains(pathValue);
            if (!converted.equals(pathValue)) {
                log.sendStepLog(StepType.INFO, "XPath1 兼容转换",
                    "matches() -> contains(): " + converted);
                pathValue = converted;
            }
        }

        // Android 16 + WiFi ADB: 操作前小延迟，减轻 UIA server 压力
        if (currentApiLevel >= 36 && isWifiAdbConnection) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        // One extra attempt for connection-style failures (screen off / uia server stalled)
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                switch (selector) {
                    case "androidIterator" -> we = androidDriver.findElement(pathValue);
                    case "id" -> we = androidDriver.findElement(AndroidSelector.Id, pathValue, retryTime);
                    case "accessibilityId" -> we = androidDriver.findElement(AndroidSelector.ACCESSIBILITY_ID, pathValue, retryTime);
                    case "xpath" -> we = androidDriver.findElement(AndroidSelector.XPATH, pathValue, retryTime);
                    case "className" -> we = androidDriver.findElement(AndroidSelector.CLASS_NAME, pathValue, retryTime);
                    case "androidUIAutomator" -> we = androidDriver.findElement(AndroidSelector.UIAUTOMATOR, pathValue, retryTime);
                    default ->
                            log.sendStepLog(StepType.ERROR, "查找控件元素失败", "这个控件元素类型: " + selector + " 不存在!!!");
                }
                return we;
            } catch (SonicRespException e) {
                // Some devices/Android versions can't use XPath2 due to hidden API restrictions.
                // Auto fallback to XPath1 once to improve stability.
                if (attempt == 0 && tryEnableEnforceXPath1(selector, e)) {
                    continue;
                }
                // 检查 SonicRespException 是否也是连接/session 错误，需要自愈
                if (attempt == 0 && isUiaConnectionError(e)) {
                    String pv = pathValue == null ? "" : pathValue;
                    String pvShort = pv.length() > 120 ? pv.substring(0, 120) + "..." : pv;
                    LOGGER.warn("findEle SonicRespException connection error -> recover: device={}, selector={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, pvShort, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue;
                }
                throw e;
            } catch (Throwable e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    String pv = pathValue == null ? "" : pathValue;
                    String pvShort = pv.length() > 120 ? pv.substring(0, 120) + "..." : pv;
                    LOGGER.warn("findEle connection error -> recover: device={}, selector={}, retryTime={}, pathLen={}, path={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(),
                            selector, retryTime, pv.length(), pvShort, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue;
                }
                // keep original exception style
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new SonicRespException(compactErrorMessage(e));
            }
        }
        return we;
    }

    public void iteratorAndroidElement(HandleContext handleContext, String des, String selector, String value) {

        List<AndroidElement> androidElements;

        if (handleContext.iteratorElement == null) {
            handleContext.setStepDes("迭代控件列表 " + des);
            try {
                androidElements = findEleList(selector, value);
                List<BaseElement> res = new ArrayList<>(androidElements);
                handleContext.iteratorElement = res.iterator();
            } catch (Throwable e) {
                handleContext.setE(e);
                return;
            }
            log.sendStepLog(StepType.INFO, "", "迭代控件列表长度：" + androidElements.size());
        }

        if (handleContext.iteratorElement.hasNext()) {
            handleContext.currentIteratorElement = handleContext.iteratorElement.next();
            AndroidElement a = (AndroidElement) handleContext.currentIteratorElement;
            try {
                handleContext.setStepDes("当前迭代控件：" + a.getUniquelyIdentifies());
                handleContext.setDetail("");
            } catch (Exception e) {
                handleContext.setE(e);
            }

        } else {
            handleContext.iteratorElement = null;
            handleContext.currentIteratorElement = null;
            log.sendStepLog(StepType.INFO, "", "迭代控件列表完毕...");
            handleContext.setStepDes("迭代控件列表 " + des);
            handleContext.setE(new Exception("exit while"));
        }
    }

    public List<AndroidElement> findEleList(String selector, String pathValue) throws SonicRespException {
        List<AndroidElement> androidElements = null;
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);

        // Android 16+ 使用 XPath1，需要将 matches() 转换为 contains()
        if ("xpath".equals(selector) && enforceXPath1Enabled && pathValue != null && pathValue.contains("matches(")) {
            String converted = convertMatchesToContains(pathValue);
            if (!converted.equals(pathValue)) {
                log.sendStepLog(StepType.INFO, "XPath1 兼容转换",
                    "matches() -> contains(): " + converted);
                pathValue = converted;
            }
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                switch (selector) {
                    case "id" -> androidElements = androidDriver.findElementList(AndroidSelector.Id, pathValue);
                    case "accessibilityId" ->
                            androidElements = androidDriver.findElementList(AndroidSelector.ACCESSIBILITY_ID, pathValue);
                    case "xpath" -> androidElements = androidDriver.findElementList(AndroidSelector.XPATH, pathValue);
                    case "className" -> androidElements = androidDriver.findElementList(AndroidSelector.CLASS_NAME, pathValue);
                    case "androidUIAutomator" ->
                            androidElements = androidDriver.findElementList(AndroidSelector.UIAUTOMATOR, pathValue);
                    default ->
                            log.sendStepLog(StepType.ERROR, "查找控件元素数组失败", "这个控件元素类型: " + selector + " 不存在!!!");
                }
                return androidElements;
            } catch (SonicRespException e) {
                if (attempt == 0 && tryEnableEnforceXPath1(selector, e)) {
                    continue;
                }
                // 检查 SonicRespException 是否是连接/session 错误
                if (attempt == 0 && isUiaConnectionError(e)) {
                    LOGGER.warn("findEleList SonicRespException connection error -> recover: device={}, selector={}, err={}",
                            iDevice == null ? "" : iDevice.getSerialNumber(), selector, compactErrorMessage(e));
                    recoverUiaIfNeeded(e);
                    continue;
                }
                throw e;
            } catch (Throwable e) {
                if (attempt == 0 && isUiaConnectionError(e)) {
                    recoverUiaIfNeeded(e);
                    continue;
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new SonicRespException(compactErrorMessage(e));
            }
        }
        return androidElements;
    }

    public void setFindElementInterval(HandleContext handleContext, int retry, int interval) {
        handleContext.setStepDes("设置查找原生控件策略");
        handleContext.setDetail(String.format("Retry count: %d, retry interval: %d ms", retry, interval));
        androidDriver.setDefaultFindElementInterval(retry, interval);
    }

    private WebElement getWebElementByCssAndText(String pathValue) {
        // 新增H5页面通过className+text定位控件元素
        // value格式：van-button--default,购物车
        WebElement element = null;
        List<String> values = new ArrayList<>(Arrays.asList(pathValue.split(",")));
        if (values.size() >= 2) {
            // findElementsByClassName在高版本的chromedriver有bug，只能用cssSelector才能找到控件元素
            List<WebElement> els = chromeDriver.findElements(By.cssSelector(values.get(0)));
            for (WebElement el : els) {
                if (el.getText().equals(values.get(1))) {
                    element = el;
                    break;
                }
            }
        }
        return element;
    }

    public void webElementScrollToView(HandleContext handleContext, String des, String selector, String pathValue) {
        handleContext.setStepDes("滚动页面元素 " + des + " 至顶部可见");
        WebElement we;
        try {
            we = findWebEle(selector, pathValue);
        } catch (Exception e) {
            handleContext.setE(e);
            return;
        }
        JavascriptExecutor jsExe = chromeDriver;
        jsExe.executeScript("arguments[0].scrollIntoView();", we);
        handleContext.setDetail("控件元素 " + selector + ":" + pathValue + " 滚动至页面顶部");
    }

    public void isExistWebViewEle(HandleContext handleContext, String des, String selector, String pathValue, boolean expect) {
        handleContext.setStepDes("判断控件 " + des + " 是否存在");
        handleContext.setDetail("期望值：" + (expect ? "存在" : "不存在"));
        boolean hasEle = false;
        try {
            WebElement w = findWebEle(selector, pathValue);
            if (w != null) {
                hasEle = true;
            }
        } catch (Exception ignored) {
        }
        try {
            assertEquals(hasEle, expect);
        } catch (AssertionError e) {
            handleContext.setE(e);
        }
    }

    public void getWebViewTextAndAssert(HandleContext handleContext, String des, String selector, String pathValue, String expect) {
        try {
            String s = getWebViewText(handleContext, des, selector, pathValue);
            if (handleContext.getE() != null) {
                return;
            }
            handleContext.setStepDes("验证" + des + "文本");
            handleContext.setDetail("验证" + selector + ":" + pathValue + "文本");
            try {
                expect = TextHandler.replaceTrans(expect, globalParams);
                assertEquals(s, expect);
                log.sendStepLog(StepType.INFO, "验证文本", "真实值： " + s + " 期望值： " + expect);
            } catch (AssertionError e) {
                handleContext.setE(e);
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void webViewClick(HandleContext handleContext, String des, String selector, String pathValue) {
        handleContext.setStepDes("点击" + des);
        pathValue = TextHandler.replaceTrans(pathValue, globalParams);
        handleContext.setDetail("点击" + selector + ": " + pathValue);
        try {
            findWebEle(selector, pathValue).click();
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void webViewSendKeys(HandleContext handleContext, String des, String selector, String pathValue, String keys) {
        keys = TextHandler.replaceTrans(keys, globalParams);
        handleContext.setStepDes("对" + des + "输入内容");
        handleContext.setDetail("对" + selector + ": " + pathValue + " 输入: " + keys);
        try {
            findWebEle(selector, pathValue).sendKeys(keys);
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void webViewSendKeysByActions(HandleContext handleContext, String des, String selector, String pathValue, String keys) {
        keys = TextHandler.replaceTrans(keys, globalParams);
        handleContext.setStepDes("对" + des + "输入内容");
        handleContext.setDetail("对" + selector + ": " + pathValue + " 输入: " + keys);
        try {
            WebElement targetElement = findWebEle(selector, pathValue);
            if (targetElement != null) {
                JavascriptExecutor jsExe = chromeDriver;
                jsExe.executeScript("arguments[0].focus();", targetElement);
                targetElement.sendKeys(keys);
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void webViewClear(HandleContext handleContext, String des, String selector, String pathValue) {
        handleContext.setStepDes("清空" + des);
        handleContext.setDetail("清空" + selector + ": " + pathValue);
        try {
            findWebEle(selector, pathValue).clear();
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void webViewRefresh(HandleContext handleContext) {
        handleContext.setStepDes("刷新页面");
        handleContext.setDetail("");
        try {
            chromeDriver.navigate().refresh();
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void webViewBack(HandleContext handleContext) {
        handleContext.setStepDes("回退页面");
        handleContext.setDetail("");
        try {
            chromeDriver.navigate().back();
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public String getWebViewText(HandleContext handleContext, String des, String selector, String pathValue) {
        String s = "";
        handleContext.setStepDes("获取" + des + "文本");
        handleContext.setDetail("获取" + selector + ":" + pathValue + "文本");
        try {
            s = findWebEle(selector, pathValue).getText();
            log.sendStepLog(StepType.INFO, "", "文本获取结果: " + s);
        } catch (Exception e) {
            handleContext.setE(e);
        }
        return s;
    }

    public void stepHold(HandleContext handleContext, int time) {
        handleContext.setStepDes("设置全局步骤间隔");
        handleContext.setDetail("间隔" + time + " ms");
        holdTime = time;
    }

    public void setClipperByKeyboard(HandleContext handleContext, String text) {
        text = TextHandler.replaceTrans(text, globalParams);
        handleContext.setStepDes("设置文本到剪切板");
        handleContext.setDetail("设置 " + text);
        if (!AndroidDeviceBridgeTool.installSonicApk(iDevice)) {
            handleContext.setE(new SonicRespException("Sonic Apk install failed."));
            return;
        }
        String currentIme = AndroidDeviceBridgeTool.executeCommand(iDevice, "settings get secure default_input_method");
        if (!currentIme.contains("org.cloud.sonic.android/.keyboard.SonicKeyboard")) {
            AndroidDeviceBridgeTool.executeCommand(iDevice, "ime enable org.cloud.sonic.android/.keyboard.SonicKeyboard");
            AndroidDeviceBridgeTool.executeCommand(iDevice, "ime set org.cloud.sonic.android/.keyboard.SonicKeyboard");
        }
        if (!AndroidDeviceBridgeTool.setClipperByKeyboard(iDevice, text)) {
            handleContext.setE(new SonicRespException("Set text to clipper failed. Please confirm that your Sonic ime is active."));
        }
    }

    public String getClipperByKeyboard(HandleContext handleContext) {
        handleContext.setStepDes("获取剪切板文本");
        handleContext.setDetail("");
        return AndroidDeviceBridgeTool.getClipperByKeyboard(iDevice);
    }

    public void sendKeyForce(HandleContext handleContext, String text) {
        text = TextHandler.replaceTrans(text, globalParams);
        handleContext.setStepDes("Sonic输入法输入文本");
        handleContext.setDetail("输入 " + text);
        if (!AndroidDeviceBridgeTool.installSonicApk(iDevice)) {
            handleContext.setE(new SonicRespException("Sonic Apk install failed."));
            return;
        }
        String currentIme = AndroidDeviceBridgeTool.executeCommand(iDevice, "settings get secure default_input_method");
        if (!currentIme.contains("org.cloud.sonic.android/.keyboard.SonicKeyboard")) {
            AndroidDeviceBridgeTool.executeCommand(iDevice, "ime enable org.cloud.sonic.android/.keyboard.SonicKeyboard");
            AndroidDeviceBridgeTool.executeCommand(iDevice, "ime set org.cloud.sonic.android/.keyboard.SonicKeyboard");
        }
        AndroidDeviceBridgeTool.sendKeysByKeyboard(iDevice, text);
    }

    public void closeKeyboard(HandleContext handleContext) {
        handleContext.setStepDes("关闭Sonic输入法");
        handleContext.setDetail("");
        AndroidDeviceBridgeTool.executeCommand(iDevice, "ime disable org.cloud.sonic.android/.keyboard.SonicKeyboard");
    }

    private int holdTime = 0;

    private int[] computedPoint(double x, double y) {
        if (x <= 1 && y <= 1) {
            Integer screenOrientation = AndroidDeviceManagerMap.getRotationMap().get(iDevice.getSerialNumber());
            if (screenOrientation == null) {
                screenOrientation = AndroidDeviceBridgeTool.getOrientation(iDevice);
            }
            String size = AndroidDeviceBridgeTool.getScreenSize(iDevice);
            String[] winSize = size.split("x");
            if (screenOrientation == 1 || screenOrientation == 3) {
                x = BytesTool.getInt(winSize[1]) * x;
                y = BytesTool.getInt(winSize[0]) * y;
            } else {
                x = BytesTool.getInt(winSize[0]) * x;
                y = BytesTool.getInt(winSize[1]) * y;
            }
        }
        return new int[]{(int) x, (int) y};
    }

    public void runScript(HandleContext handleContext, String script, String type) {
        handleContext.setStepDes("Run Custom Scripts");
        handleContext.setDetail("Script: <br>" + script);
        try {
            switch (type) {
                case "Groovy" -> {
                    ScriptRunner groovyScript = new GroovyScriptImpl();
                    groovyScript.runAndroid(this, script);
                }
                case "Python" -> {
                    ScriptRunner pythonScript = new PythonScriptImpl();
                    pythonScript.runAndroid(this, script);
                }
            }
        } catch (Throwable e) {
            handleContext.setE(e);
        }
    }

    public void switchTouchMode(HandleContext handleContext, String mode) {
        handleContext.setStepDes("设置触控模式");
        handleContext.setDetail("切换为 " + mode + " 模式");
        AndroidTouchHandler.switchTouchMode(iDevice, AndroidTouchHandler.TouchMode.valueOf(mode));
    }

    /**
     * >2.5.0版本，增强型的文本断言能力，支持指定断言的方式
     *
     * @param handleContext HandleContext
     * @param des           元素名
     * @param selector      元素定位方式
     * @param pathValue     定位方式值
     * @param operation     断言类型(equal,notEqual,contain,notContain)
     * @param expect        期望值
     * @param elementType   元素类型(原生，web，poco)
     */
    public void getElementTextAndAssertWithOperation(HandleContext handleContext, String des, String selector,
                                                     String pathValue, String operation, String expect, int elementType) {
        try {
            String realValue = switch (elementType) {
                case ANDROID_ELEMENT_TYPE -> getText(handleContext, des, selector, pathValue);
                case WEB_ELEMENT_TYPE -> getWebViewText(handleContext, des, selector, pathValue);
                case POCO_ELEMENT_TYPE -> getPocoText(handleContext, des, selector, pathValue);
                default -> throw new SonicRespException("未支持的元素类型" + elementType + "，无法进行文本断言");
            };
            if (handleContext.getE() != null) {
                return;
            }
            handleContext.setStepDes("断言" + des + "文本");
            try {
                expect = TextHandler.replaceTrans(expect, globalParams);
                AssertUtil assertUtil = new AssertUtil();
                StringBuilder sbLog = new StringBuilder("真实值：");
                sbLog.append(realValue);
                sbLog.append("，期望 ");
                sbLog.append(assertUtil.getAssertDesc(operation));
                sbLog.append(" ");
                sbLog.append(expect);
                handleContext.setDetail(sbLog.toString());
                switch (operation) {
                    case "equal" -> assertEquals(realValue, expect);
                    case "notEqual" -> assertNotEquals(realValue, expect);
                    case "contain" -> assertTrue(realValue.contains(expect));
                    case "notContain" -> assertFalse(realValue.contains(expect));
                    default ->
                            throw new SonicRespException("未支持的文本断言操作类型" + operation + "，无法进行文本断言");
                }
            } catch (AssertionError e) {
                handleContext.setE(e);
            }
        } catch (Exception e) {
            handleContext.setE(e);
        }
    }

    public void runStep(JSONObject stepJSON, HandleContext handleContext) throws Throwable {
        JSONObject step = stepJSON.getJSONObject("step");
        // 兼容childSteps
        if (CollectionUtils.isEmpty(step)) {
            step = stepJSON;
        }
        JSONArray eleList = step.getJSONArray("elements");
        Thread.sleep(holdTime);
        switch (step.getString("stepType")) {
            case "switchTouchMode" -> switchTouchMode(handleContext, step.getString("content"));
            case "appReset" -> appReset(handleContext, step.getString("text"));
            case "appAutoGrantPermissions" -> appAutoGrantPermissions(handleContext, step.getString("text"));
            case "stepHold" -> stepHold(handleContext, step.getInteger("content"));
            case "toWebView" -> toWebView(handleContext, step.getString("content"), step.getString("text"));
            case "toHandle" -> toHandle(handleContext, step.getString("content"));
            case "readText" -> readText(handleContext, step.getString("content"), step.getString("text"));
            case "clickByImg" -> clickByImg(handleContext, eleList.getJSONObject(0).getString("eleName")
                    , eleList.getJSONObject(0).getString("eleValue"));
            case "click" ->
                    click(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"));
            case "getTitle" -> getTitle(handleContext, step.getString("content"));
            case "getUrl" -> getUrl(handleContext, step.getString("content"));
            case "getActivity" -> getActivity(handleContext, step.getString("content"));
            case "getElementAttr" ->
                    getElementAttr(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("text"), step.getString("content"));
            case "obtainElementAttr" ->
                    obtainElementAttr(handleContext, eleList.getJSONObject(0).getString("eleName"),
                            eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue"),
                            step.getString("text"), step.getString("content"));
            case "logElementAttr" ->
                    logElementAttr(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("text"));
            case "sendKeys" ->
                    sendKeys(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
            case "sendKeysByActions" ->
                    sendKeysByActions(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
            case "isExistEle" ->
                    isExistEle(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getBoolean("content"));
            case "scrollToEle" ->
                    scrollToEle(handleContext, eleList.getJSONObject(0).getString("eleName"),
                            eleList.getJSONObject(0).getString("eleType"),
                            eleList.getJSONObject(0).getString("eleValue"),
                            step.getInteger("content"),
                            step.getString("text"));
            case "isExistEleNum" -> isExistEleNum(handleContext, eleList.getJSONObject(0).getString("eleName"),
                    eleList.getJSONObject(0).getString("eleType"),
                    eleList.getJSONObject(0).getString("eleValue"),
                    step.getString("content"),
                    step.getInteger("text"), ANDROID_ELEMENT_TYPE);
            case "clear" ->
                    clear(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"));
            case "longPress" ->
                    longPress(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getInteger("content"));
            case "swipe" ->
                    swipePoint(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue")
                            , eleList.getJSONObject(1).getString("eleName"), eleList.getJSONObject(1).getString("eleValue"));
            case "swipe2" ->
                    swipe(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")
                            , eleList.getJSONObject(1).getString("eleName"), eleList.getJSONObject(1).getString("eleType"), eleList.getJSONObject(1).getString("eleValue"));
            case "drag" ->
                    dragByPoint(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue")
                            , eleList.getJSONObject(1).getString("eleName"), eleList.getJSONObject(1).getString("eleValue"));
            case "drag2" ->
                    dragByEle(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")
                            , eleList.getJSONObject(1).getString("eleName"), eleList.getJSONObject(1).getString("eleType"), eleList.getJSONObject(1).getString("eleValue"));
            case "motionEvent" -> motionEventByEle(handleContext, eleList.getJSONObject(0).getString("eleName"),
                    eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue"), step.getString("text"));
            case "motionEventByPoint" ->
                    motionEventByPoint(handleContext, eleList.getJSONObject(0).getString("eleName"),
                            eleList.getJSONObject(0).getString("eleValue"),
                            step.getString("text"));
            case "tap" ->
                    tap(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue"));
            case "longPressPoint" ->
                    longPressPoint(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue")
                            , step.getInteger("content"));
            case "pause" -> pause(handleContext, step.getInteger("content"));
            case "swipeByDefinedDirection" ->
                    swipeByDefinedDirection(handleContext, step.getString("text"), step.getInteger("content"));
            case "checkImage" ->
                    checkImage(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleValue")
                            , step.getDouble("content"));
            case "stepScreen" -> stepScreen(handleContext);
            case "openApp" -> openApp(handleContext, step.getString("text"));
            case "terminate" -> terminate(handleContext, step.getString("text"));
            case "install" -> install(handleContext, step.getString("text"));
            case "uninstall" -> uninstall(handleContext, step.getString("text"));
            case "screenSub", "screenAdd", "screenAbort" -> rotateDevice(handleContext, step.getString("stepType"));
            case "lock" -> lock(handleContext);
            case "unLock" -> unLock(handleContext);
            case "airPlaneMode" -> airPlaneMode(handleContext, step.getBoolean("content"));
            case "wifiMode" -> wifiMode(handleContext, step.getBoolean("content"));
            case "locationMode" -> locationMode(handleContext, step.getBoolean("content"));
            case "keyCode" -> keyCode(handleContext, step.getString("content"));
            case "keyCodeSelf" -> keyCode(handleContext, step.getInteger("content"));
            case "assertEquals", "assertNotEquals", "assertTrue", "assertNotTrue" -> {
                String actual = TextHandler.replaceTrans(step.getString("text"), globalParams);
                String expect = TextHandler.replaceTrans(step.getString("content"), globalParams);
                asserts(handleContext, actual, expect, step.getString("stepType"));
            }
            case "getTextValue" ->
                    globalParams.put(step.getString("content"), getText(handleContext, eleList.getJSONObject(0).getString("eleName")
                            , eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")));
            case "sendKeyForce" -> sendKeyForce(handleContext, step.getString("content"));
            case "monkey" ->
                    runMonkey(handleContext, step.getJSONObject("content"), step.getJSONArray("text").toJavaList(JSONObject.class));
            case "publicStep" -> publicStep(handleContext, step.getString("content"), step.getJSONArray("pubSteps"));
            case "setDefaultFindWebViewElementInterval" ->
                    setDefaultFindWebViewElementInterval(handleContext, step.getInteger("content"), step.getInteger("text"));
            case "webElementScrollToView" ->
                    webElementScrollToView(handleContext, eleList.getJSONObject(0).getString("eleName"),
                            eleList.getJSONObject(0).getString("eleType"),
                            eleList.getJSONObject(0).getString("eleValue"));
            case "isExistWebViewEle" ->
                    isExistWebViewEle(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getBoolean("content"));
            case "isExistWebViewEleNum" -> isExistEleNum(handleContext,
                    eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                    , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"),
                    step.getInteger("text"), WEB_ELEMENT_TYPE);
            case "webViewClear" ->
                    webViewClear(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"));
            case "webViewSendKeys" ->
                    webViewSendKeys(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
            case "webViewSendKeysByActions" ->
                    webViewSendKeysByActions(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
            case "webViewClick" ->
                    webViewClick(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"));
            case "webViewRefresh" -> webViewRefresh(handleContext);
            case "webViewBack" -> webViewBack(handleContext);
            case "getWebViewTextValue" ->
                    globalParams.put(step.getString("content"), getWebViewText(handleContext, eleList.getJSONObject(0).getString("eleName")
                            , eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")));
            case "findElementInterval" ->
                    setFindElementInterval(handleContext, step.getInteger("content"), step.getInteger("text"));
            case "runScript" -> runScript(handleContext, step.getString("content"), step.getString("text"));
            case "setDefaultFindPocoElementInterval" ->
                    setDefaultFindPocoElementInterval(handleContext, step.getInteger("content"), step.getInteger("text"));
            case "startPocoDriver" ->
                    startPocoDriver(handleContext, step.getString("content"), step.getInteger("text"));
            case "isExistPocoEle" ->
                    isExistPocoEle(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getBoolean("content"));
            case "isExistPocoEleNum" -> isExistEleNum(handleContext,
                    eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                    , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"),
                    step.getInteger("text"), POCO_ELEMENT_TYPE);
            case "pocoClick" ->
                    pocoClick(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"));
            case "pocoLongPress" ->
                    pocoLongPress(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue")
                            , step.getInteger("content"));
            case "pocoSwipe" ->
                    pocoSwipe(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")
                            , eleList.getJSONObject(1).getString("eleName"), eleList.getJSONObject(1).getString("eleType"), eleList.getJSONObject(1).getString("eleValue"));
            case "setTheRealPositionOfTheWindow" ->
                    setTheRealPositionOfTheWindow(handleContext, step.getString("content"));
            case "getPocoElementAttr" ->
                    getPocoElementAttr(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("text"), step.getString("content"));
            case "obtainPocoElementAttr" ->
                    obtainPocoElementAttr(handleContext, eleList.getJSONObject(0).getString("eleName"),
                            eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue"),
                            step.getString("text"), step.getString("content"));
            case "logPocoElementAttr" ->
                    logPocoElementAttr(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("text"));
            case "getPocoTextValue" ->
                    globalParams.put(step.getString("content"), getPocoText(handleContext, eleList.getJSONObject(0).getString("eleName")
                            , eleList.getJSONObject(0).getString("eleType"), eleList.getJSONObject(0).getString("eleValue")));
            case "freezeSource" -> freezeSource(handleContext);
            case "thawSource" -> thawSource(handleContext);
            case "closePocoDriver" -> closePocoDriver(handleContext);
            case "switchWindowMode" -> switchWindowMode(handleContext, step.getBoolean("content"));
            case "switchIgnoreMode" -> switchIgnoreMode(handleContext, step.getBoolean("content"));
            case "switchVisibleMode" -> switchVisibleMode(handleContext, step.getBoolean("content"));
            case "closeKeyboard" -> closeKeyboard(handleContext);
            case "iteratorPocoElement" ->
                    iteratorPocoElement(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"));
            case "iteratorAndroidElement" ->
                    iteratorAndroidElement(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"));
            case "getClipperByKeyboard" ->
                    globalParams.put(step.getString("content"), getClipperByKeyboard(handleContext));
            case "setClipperByKeyboard" -> setClipperByKeyboard(handleContext, step.getString("content"));
            // <= 2.5版本的文本断言语法(包括原生，webView，Poco三类)，保留做兼容，老版本升级上来的存量用例继续可用
            case "getText" ->
                    getTextAndAssert(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
            case "getWebViewText" ->
                    getWebViewTextAndAssert(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
            case "getPocoText" ->
                    getPocoTextAndAssert(handleContext, eleList.getJSONObject(0).getString("eleName"), eleList.getJSONObject(0).getString("eleType")
                            , eleList.getJSONObject(0).getString("eleValue"), step.getString("content"));
            // > 2.5版本的文本断言语法，支持指定断言的方式
            case "assertText" -> getElementTextAndAssertWithOperation(handleContext,
                    eleList.getJSONObject(0).getString("eleName"),
                    eleList.getJSONObject(0).getString("eleType"),
                    eleList.getJSONObject(0).getString("eleValue"),
                    step.getString("content"), step.getString("text"),
                    ANDROID_ELEMENT_TYPE);
            case "assertWebViewText" -> getElementTextAndAssertWithOperation(handleContext,
                    eleList.getJSONObject(0).getString("eleName"),
                    eleList.getJSONObject(0).getString("eleType"),
                    eleList.getJSONObject(0).getString("eleValue"),
                    step.getString("content"), step.getString("text"),
                    WEB_ELEMENT_TYPE);
            case "assertPocoText" -> getElementTextAndAssertWithOperation(handleContext,
                    eleList.getJSONObject(0).getString("eleName"),
                    eleList.getJSONObject(0).getString("eleType"),
                    eleList.getJSONObject(0).getString("eleValue"),
                    step.getString("content"), step.getString("text"),
                    POCO_ELEMENT_TYPE);
        }
        switchType(step, handleContext);
    }

    public void switchType(JSONObject stepJson, HandleContext handleContext) throws Throwable {
        Integer error = stepJson.getInteger("error");
        String stepDes = handleContext.getStepDes();
        String detail = handleContext.getDetail();
        Throwable e = handleContext.getE();
        if (e != null && !"exit while".equals(e.getMessage()) && !e.getMessage().startsWith("IGNORE:")) {
            switch (error) {
                case ErrorType.IGNORE:
                    if (stepJson.getInteger("conditionType").equals(ConditionEnum.NONE.getValue())) {
                        log.sendStepLog(StepType.PASS, stepDes + "异常！已忽略...", detail);
                        handleContext.clear();
                    } else {
                        ConditionEnum conditionType =
                                SonicEnum.valueToEnum(ConditionEnum.class, stepJson.getInteger("conditionType"));
                        String des = "「%s」步骤「%s」异常".formatted(conditionType.getName(), stepDes);
                        log.sendStepLog(StepType.ERROR, des, detail);
                        exceptionLog(e);
                    }
                    break;
                case ErrorType.WARNING:
                    log.sendStepLog(StepType.WARN, stepDes + "异常！", detail);
                    setResultDetailStatus(ResultDetailStatus.WARN);
                    errorScreen();
                    exceptionLog(e);
                    break;
                case ErrorType.SHUTDOWN:
                    log.sendStepLog(StepType.ERROR, stepDes + "异常！", detail);
                    setResultDetailStatus(ResultDetailStatus.FAIL);
                    errorScreen();
                    exceptionLog(e);
                    throw e;
            }
        } else if (!"IGNORE".equals(stepDes)) {
            log.sendStepLog(StepType.PASS, stepDes, detail);
        }
    }
}
