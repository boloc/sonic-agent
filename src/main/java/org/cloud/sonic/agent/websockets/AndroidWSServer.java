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
package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.*;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceLocalStatus;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceThreadPool;
import org.cloud.sonic.agent.bridge.android.AndroidSupplyTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.enums.AndroidKey;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.maps.AndroidAPKMap;
import org.cloud.sonic.agent.common.maps.DevicesLockMap;
import org.cloud.sonic.agent.common.maps.HandlerMap;
import org.cloud.sonic.agent.common.maps.WebSocketSessionMap;
import org.cloud.sonic.agent.common.models.HandleContext;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.tests.android.AndroidRunStepThread;
import org.cloud.sonic.agent.tests.handlers.AndroidStepHandler;
import org.cloud.sonic.agent.tests.handlers.AndroidTouchHandler;
import org.cloud.sonic.agent.tools.*;
import org.cloud.sonic.agent.tools.file.DownloadTool;
import org.cloud.sonic.agent.tools.file.UploadTools;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.cloud.sonic.driver.common.tool.SonicRespException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.stream.FileImageOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.Calendar;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.cloud.sonic.agent.tools.BytesTool.sendText;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/android/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class AndroidWSServer implements IAndroidWSServer {
    @Value("${sonic.agent.key}")
    private String key;
    @Value("${sonic.agent.port}")
    private int port;

    @OnOpen
    public void onOpen(Session session, @PathParam("key") String secretKey,
                    @PathParam("udId") String udId, @PathParam("token") String token) throws Exception {
        log.info("Android control websocket opening: udId={}, session={}", udId, session.getId());
        if (secretKey.length() == 0 || (!secretKey.equals(key)) || token.length() == 0) {
            log.info("Android control websocket auth failed: udId={}, session={}", udId, session.getId());
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }

        log.info("Android control websocket waiting for device lock: udId={}, session={}", udId, session.getId());
        boolean lockSuccess = DevicesLockMap.lockByUdId(udId, 30L, TimeUnit.SECONDS);
        if (!lockSuccess) {
            log.info("Android control websocket failed to get device lock: udId={}, session={}", udId, session.getId());
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }
        log.info("Android control websocket locked device: udId={}, session={}", udId, session.getId());
        // 获取锁成功后立即写入 udId，确保 onClose 时能正确解锁
        // 这必须在任何可能 return 的代码之前执行！
        session.getUserProperties().put("udId", udId);
        AndroidDeviceLocalStatus.startDebug(udId);

        log.info("Android control websocket locating device: udId={}, session={}", udId, session.getId());
        IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
        if (iDevice == null) {
            log.info("Android control websocket target device is not connecting: udId={}, session={}", udId, session.getId());
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }
        // Proactively wake screen up before starting Sonic services.
        // This helps reduce intermittent UIAutomator2 timeouts on some ROMs when screen is off/locked.
        AndroidDeviceBridgeTool.wakeUpScreen(iDevice);

        session.getUserProperties().put("udId", udId);
        session.getUserProperties().put("id", String.format("%s-%s", this.getClass().getSimpleName(), udId));
        WebSocketSessionMap.addSession(session);
        saveUdIdMapAndSet(session, iDevice);

        // 更新使用用户
        JSONObject jsonDebug = new JSONObject();
        jsonDebug.put("msg", "debugUser");
        jsonDebug.put("token", token);
        jsonDebug.put("udId", udId);
        TransportWorker.send(jsonDebug);

        session.getUserProperties().put("schedule", ScheduleTool.schedule(() -> {
            log.info("time up!");
            if (session.isOpen()) {
                JSONObject errMsg = new JSONObject();
                errMsg.put("msg", "error");
                BytesTool.sendText(session, errMsg.toJSONString());
                exit(session);
                AndroidDeviceBridgeTool.pressKey(iDevice, AndroidKey.HOME);
            }
        }, BytesTool.remoteTimeout));

        saveUdIdMapAndSet(session, iDevice);

        AndroidAPKMap.getMap().put(udId, false);

        log.info("Android control websocket installing Sonic APK: udId={}, serialNumber={}", udId, iDevice.getSerialNumber());
        if (!AndroidDeviceBridgeTool.installSonicApk(iDevice)) {
            log.warn("Android control websocket install Sonic APK failed: udId={}, serialNumber={}", udId, iDevice.getSerialNumber());
            AndroidAPKMap.getMap().remove(udId);
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }

        log.info("Android control websocket starting Sonic service activity: udId={}, serialNumber={}", udId, iDevice.getSerialNumber());
        AndroidDeviceBridgeTool.executeCommand(iDevice, "am start -n org.cloud.sonic.android/.SonicServiceActivity", 5000, TimeUnit.MILLISECONDS);
        AndroidAPKMap.getMap().put(udId, true);
        log.info("Android control websocket Sonic APK ready: udId={}, serialNumber={}", udId, iDevice.getSerialNumber());

        AndroidTouchHandler.startTouch(iDevice);

        AndroidSupplyTool.startShare(udId, session);

        openDriver(iDevice, session);

        String currentIme = AndroidDeviceBridgeTool.executeCommand(iDevice, "settings get secure default_input_method", 5000, TimeUnit.MILLISECONDS);
        if (!currentIme.contains("org.cloud.sonic.android/.keyboard.SonicKeyboard")) {
            AndroidDeviceBridgeTool.executeCommand(iDevice, "ime enable org.cloud.sonic.android/.keyboard.SonicKeyboard", 5000, TimeUnit.MILLISECONDS);
            AndroidDeviceBridgeTool.executeCommand(iDevice, "ime set org.cloud.sonic.android/.keyboard.SonicKeyboard", 5000, TimeUnit.MILLISECONDS);
        }
    }

    @OnClose
    public void onClose(Session session) {
        String udId = (String) session.getUserProperties().get("udId");
        try {
            exit(session);
        } finally {
            if (StringUtils.hasText(udId)) {
                DevicesLockMap.unlockAndRemoveByUdId(udId);
                log.info("android unlock udId：{}", udId);
            } else {
                // 如果 onOpen 在鉴权/锁/设备检查阶段提前 return，可能没有写入 udId，onClose 仍会被触发
                log.warn("android unlock skipped: udId is blank, session={}", session.getId());
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        Object idObj = session == null ? null : session.getUserProperties().get("id");
        log.error("Android control websocket error: session={}",
                idObj == null ? (session == null ? "null" : session.getId()) : idObj.toString(), error);
        JSONObject errMsg = new JSONObject();
        errMsg.put("msg", "error");
        BytesTool.sendText(session, errMsg.toJSONString());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        JSONObject msg = JSON.parseObject(message);
        log.info("{} send: {}", session.getUserProperties().get("id").toString(), msg);
        IDevice iDevice = udIdMap.get(session);
        // 获取稳定的 udId（WiFi 设备只使用 IP 部分）
        String udId = (String) session.getUserProperties().get("udId");
        switch (msg.getString("type")) {
            case "startPerfmon" ->
                    AndroidSupplyTool.startPerfmon(udId, msg.getString("bundleId"), session, null, 1000);
            case "stopPerfmon" -> AndroidSupplyTool.stopPerfmon(udId);
            case "startKeyboard" -> {
                String currentIme = AndroidDeviceBridgeTool.executeCommand(iDevice, "settings get secure default_input_method");
                if (!currentIme.contains("org.cloud.sonic.android/.keyboard.SonicKeyboard")) {
                    AndroidDeviceBridgeTool.executeCommand(iDevice, "ime enable org.cloud.sonic.android/.keyboard.SonicKeyboard");
                    AndroidDeviceBridgeTool.executeCommand(iDevice, "ime set org.cloud.sonic.android/.keyboard.SonicKeyboard");
                }
            }
            case "stopKeyboard" ->
                    AndroidDeviceBridgeTool.executeCommand(iDevice, "ime disable org.cloud.sonic.android/.keyboard.SonicKeyboard");
            case "setPasteboard" -> AndroidDeviceBridgeTool.setClipperByKeyboard(iDevice, msg.getString("detail"));
            case "getPasteboard" -> {
                JSONObject paste = new JSONObject();
                paste.put("msg", "paste");
                paste.put("detail", AndroidDeviceBridgeTool.getClipperByKeyboard(iDevice));
                sendText(session, paste.toJSONString());
            }
            case "clearProxy" -> AndroidDeviceBridgeTool.clearProxy(iDevice);
            case "proxy" -> {
                AndroidDeviceBridgeTool.clearProxy(iDevice);
                Socket portSocket = PortTool.getBindSocket();
                Socket webPortSocket = PortTool.getBindSocket();
                int pPort = PortTool.releaseAndGetPort(portSocket);
                int webPort = PortTool.releaseAndGetPort(webPortSocket);
                SGMTool.startProxy(udId, SGMTool.getCommand(pPort, webPort));
                AndroidDeviceBridgeTool.startProxy(iDevice, getHost(), pPort);
                JSONObject proxy = new JSONObject();
                proxy.put("webPort", webPort);
                proxy.put("port", pPort);
                proxy.put("msg", "proxyResult");
                BytesTool.sendText(session, proxy.toJSONString());
            }
            case "installCert" -> AndroidDeviceBridgeTool.executeCommand(iDevice,
                    String.format("am start -a android.intent.action.VIEW -d http://%s:%d/assets/download", getHost(), port));
            case "forwardView" -> {
                JSONObject forwardView = new JSONObject();
                forwardView.put("msg", "forwardView");
                forwardView.put("detail", AndroidDeviceBridgeTool.getWebView(iDevice));
                BytesTool.sendText(session, forwardView.toJSONString());
            }
            case "find" -> AndroidDeviceBridgeTool.searchDevice(iDevice);
            case "battery" -> AndroidDeviceBridgeTool.controlBattery(iDevice, msg.getInteger("detail"));
            case "uninstallApp" -> {
                JSONObject result = new JSONObject();
                try {
                    String errorMessage = AndroidDeviceBridgeTool.uninstall(iDevice, msg.getString("detail"));
                    if (errorMessage == null) {
                        result.put("detail", "success");
                    } else {
                        result.put("detail", "fail");
                    }
                } catch (InstallException e) {
                    result.put("detail", "fail");
                    e.printStackTrace();
                }
                result.put("msg", "uninstallFinish");
                BytesTool.sendText(session, result.toJSONString());
            }
            case "scan" -> AndroidDeviceBridgeTool.pushToCamera(iDevice, msg.getString("url"));
            case "text" -> AndroidDeviceBridgeTool.sendKeysByKeyboard(iDevice, msg.getString("detail"));
            case "touch" -> AndroidTouchHandler.writeScreenTouchToOutputStream(iDevice, msg.getString("detail"));
            case "keyEvent" -> AndroidDeviceBridgeTool.pressKey(iDevice, msg.getInteger("detail"));
            case "pullFile" -> {
                JSONObject result = new JSONObject();
                result.put("msg", "pullResult");
                String url = AndroidDeviceBridgeTool.pullFile(iDevice, msg.getString("path"));
                if (url != null) {
                    result.put("status", "success");
                    result.put("url", url);
                } else {
                    result.put("status", "fail");
                }
                BytesTool.sendText(session, result.toJSONString());
            }
            case "pushFile" -> {
                JSONObject result = new JSONObject();
                result.put("msg", "pushResult");
                try {
                    File localFile = DownloadTool.download(msg.getString("file"));
                    iDevice.pushFile(localFile.getAbsolutePath()
                            , msg.getString("path"));
                    result.put("status", "success");
                } catch (IOException | AdbCommandRejectedException | SyncException | TimeoutException e) {
                    result.put("status", "fail");
                    e.printStackTrace();
                }
                BytesTool.sendText(session, result.toJSONString());
            }
            case "debug" -> {
                AndroidStepHandler androidStepHandler = HandlerMap.getAndroidMap().get(udId);
                switch (msg.getString("detail")) {
                    case "poco" -> AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
                        androidStepHandler.startPocoDriver(new HandleContext(), msg.getString("engine"), msg.getInteger("port"));
                        JSONObject poco = new JSONObject();
                        try {
                            poco.put("result", androidStepHandler.getPocoDriver().getPageSourceForJsonString());
                        } catch (SonicRespException e) {
                            poco.put("result", "");
                            e.printStackTrace();
                        }
                        poco.put("msg", "poco");
                        BytesTool.sendText(session, poco.toJSONString());
                        androidStepHandler.closePocoDriver(new HandleContext());
                    });
                    case "runStep" -> {
                        JSONObject jsonDebug = new JSONObject();
                        jsonDebug.put("msg", "findSteps");
                        jsonDebug.put("key", key);
                        jsonDebug.put("udId", udId);
                        jsonDebug.put("pwd", msg.getString("pwd"));
                        jsonDebug.put("sessionId", session.getUserProperties().get("id").toString());
                        jsonDebug.put("caseId", msg.getInteger("caseId"));
                        TransportWorker.send(jsonDebug);
                    }
                    case "stopStep" -> TaskManager.forceStopDebugStepThread(
                            AndroidRunStepThread.ANDROID_RUN_STEP_TASK_PRE.formatted(
                                    0, msg.getInteger("caseId"), msg.getString("udId")
                            )
                    );
                    case "openApp" -> AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
                        AndroidDeviceBridgeTool.activateApp(iDevice, msg.getString("pkg"));
                    });
                    case "killApp" -> AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
                        AndroidDeviceBridgeTool.forceStop(iDevice, msg.getString("pkg"));
                    });
                    case "tap" -> {
                        String xy = msg.getString("point");
                        int x = Integer.parseInt(xy.substring(0, xy.indexOf(",")));
                        int y = Integer.parseInt(xy.substring(xy.indexOf(",") + 1));
                        AndroidDeviceBridgeTool.executeCommand(iDevice, "input tap " + x + " " + y);
                    }
                    case "longPress" -> {
                        String xy = msg.getString("point");
                        int x = Integer.parseInt(xy.substring(0, xy.indexOf(",")));
                        int y = Integer.parseInt(xy.substring(xy.indexOf(",") + 1));
                        AndroidDeviceBridgeTool.executeCommand(iDevice, "input swipe " + x + " " + y + " " + x + " " + y + " 1500");
                    }
                    case "swipe" -> {
                        String xy1 = msg.getString("pointA");
                        String xy2 = msg.getString("pointB");
                        int x1 = Integer.parseInt(xy1.substring(0, xy1.indexOf(",")));
                        int y1 = Integer.parseInt(xy1.substring(xy1.indexOf(",") + 1));
                        int x2 = Integer.parseInt(xy2.substring(0, xy2.indexOf(",")));
                        int y2 = Integer.parseInt(xy2.substring(xy2.indexOf(",") + 1));
                        AndroidDeviceBridgeTool.executeCommand(iDevice, "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 200");
                    }
                    case "install" -> AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
                        JSONObject result = new JSONObject();
                        result.put("msg", "installFinish");
                        try {
                            File localFile = new File(msg.getString("apk"));
                            if (msg.getString("apk").contains("http")) {
                                localFile = DownloadTool.download(msg.getString("apk"));
                            }
                            AndroidDeviceBridgeTool.install(iDevice, localFile.getAbsolutePath());
                            result.put("status", "success");
                        } catch (IOException | InstallException e) {
                            result.put("status", "fail");
                            e.printStackTrace();
                        }
                        BytesTool.sendText(session, result.toJSONString());
                    });
                    case "openDriver" -> {
                        if (androidStepHandler == null || androidStepHandler.getAndroidDriver() == null) {
                            openDriver(iDevice, session);
                        }
                    }
                    case "closeDriver" -> {
                        if (androidStepHandler != null && androidStepHandler.getAndroidDriver() != null) {
                            androidStepHandler.closeAndroidDriver();
                            HandlerMap.getAndroidMap().remove(udId);
                        }
                    }
                    case "tree" -> {
                        if (androidStepHandler != null && androidStepHandler.getAndroidDriver() != null) {
                            AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
                                long startTime = System.currentTimeMillis();
                                int maxRetry = 2;
                                for (int retry = 0; retry <= maxRetry; retry++) {
                                    try {
                                        if (retry > 0) {
                                            log.info("Retrying UI tree fetch for {} (attempt {})", iDevice.getSerialNumber(), retry + 1);
                                            // Try to reconnect UIAutomator2 if connection was lost
                                            try {
                                                Integer uiaPort = (Integer) session.getUserProperties().get("uiaPort");
                                                if (uiaPort != null) {
                                                    log.info("Attempting to restart UIAutomator2 connection for {}", iDevice.getSerialNumber());
                                                    androidStepHandler.closeAndroidDriver();
                                                    Thread.sleep(1000);
                                                    AndroidDeviceBridgeTool.startUiaServer(iDevice, uiaPort);
                                                    androidStepHandler.startAndroidDriver(iDevice, uiaPort);
                                                    log.info("UIAutomator2 reconnected successfully for {}", iDevice.getSerialNumber());
                                                }
                                            } catch (Exception reconnectEx) {
                                                log.warn("Failed to reconnect UIAutomator2 for {}: {}", iDevice.getSerialNumber(), reconnectEx.getMessage());
                                            }
                                        }

                                        log.info("Start fetching UI tree for {}", iDevice.getSerialNumber());
                                        JSONObject result = new JSONObject();
                                        JSONObject settings = new JSONObject();
                                        settings.put("enableMultiWindows", msg.getBoolean("isMulti") != null && msg.getBoolean("isMulti"));
                                        settings.put("ignoreUnimportantViews", msg.getBoolean("isIgnore") != null && msg.getBoolean("isIgnore"));
                                        settings.put("allowInvisibleElements", msg.getBoolean("isVisible") != null && msg.getBoolean("isVisible"));
                                        log.info("Setting Appium settings for {}: {}", iDevice.getSerialNumber(), settings);
                                        androidStepHandler.getAndroidDriver().setAppiumSettings(settings);
                                        log.info("Calling getPageSource for {}...", iDevice.getSerialNumber());
                                        result.put("msg", "tree");
                                        result.put("detail", androidStepHandler.getResource());
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        log.info("getPageSource completed for {} in {}ms", iDevice.getSerialNumber(), elapsed);
                                        result.put("webView", androidStepHandler.getWebView());
                                        result.put("activity", AndroidDeviceBridgeTool.getCurrentActivity(iDevice));
                                        BytesTool.sendText(session, result.toJSONString());
                                        return; // Success, exit retry loop
                                    } catch (Throwable e) {
                                        long elapsed = System.currentTimeMillis() - startTime;
                                        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                                        // Check if it's a connection error that might be recoverable
                                        boolean isConnectionError = errorMsg.contains("Unexpected end of file")
                                            || errorMsg.contains("Connection refused")
                                            || errorMsg.contains("Read timed out")
                                            || errorMsg.contains("SocketException");

                                        if (isConnectionError && retry < maxRetry) {
                                            log.warn("getPageSource failed for {} (connection error), will retry: {}", iDevice.getSerialNumber(), errorMsg);
                                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                                            continue; // Retry
                                        }

                                        log.error("getPageSource failed for {} after {}ms: {}", iDevice.getSerialNumber(), elapsed, errorMsg);
                                        JSONObject result = new JSONObject();
                                        result.put("msg", "treeFail");
                                        BytesTool.sendText(session, result.toJSONString());
                                        return;
                                    }
                                }
                            });
                        }
                    }
                    case "eleScreen" -> {
                        if (androidStepHandler != null && androidStepHandler.getAndroidDriver() != null) {
                            AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
                                JSONObject result = new JSONObject();
                                result.put("msg", "eleScreen");
                                try {
                                    File folder = new File("test-output");
                                    if (!folder.exists()) {
                                        folder.mkdirs();
                                    }
                                    File output = new File(folder + File.separator + iDevice.getSerialNumber() + Calendar.getInstance().getTimeInMillis() + ".png");
                                    try {
                                        byte[] bt = androidStepHandler.findEle("xpath", msg.getString("xpath")).screenshot();
                                        FileImageOutputStream imageOutput = new FileImageOutputStream(output);
                                        imageOutput.write(bt, 0, bt.length);
                                        imageOutput.close();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    result.put("img", UploadTools.upload(output, "keepFiles"));
                                } catch (Exception e) {
                                    log.info(e.fillInStackTrace().toString());
                                }
                                BytesTool.sendText(session, result.toJSONString());
                            });
                        }
                    }
                    case "checkLocation" -> {
                        JSONObject jsonCheck = new JSONObject();
                        jsonCheck.put("msg", "generateStep");
                        jsonCheck.put("key", key);
                        jsonCheck.put("udId", udId);
                        jsonCheck.put("pwd", msg.getString("pwd"));
                        jsonCheck.put("sessionId", session.getUserProperties().get("id").toString());
                        jsonCheck.put("element", msg.getString("element"));
                        jsonCheck.put("eleType", msg.getString("eleType"));
                        jsonCheck.put("pf", 1);
                        TransportWorker.send(jsonCheck);
                    }
                }
            }
        }
    }

    private void openDriver(IDevice iDevice, Session session) {
        synchronized (session) {
            // 获取稳定的 udId
            String udId = (String) session.getUserProperties().get("udId");
            AndroidStepHandler androidStepHandler = new AndroidStepHandler();
            androidStepHandler.setTestMode(0, 0, udId, DeviceStatus.DEBUGGING, session.getUserProperties().get("id").toString());
            JSONObject result = new JSONObject();
            AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
                try {
                    AndroidDeviceLocalStatus.startDebug(udId);
                    int port = AndroidDeviceBridgeTool.startUiaServer(iDevice);
                    // record for cleanup
                    session.getUserProperties().put("uiaPort", port);
                    androidStepHandler.startAndroidDriver(iDevice, port);
                    result.put("status", "success");
                    result.put("port", port);
                    HandlerMap.getAndroidMap().put(udId, androidStepHandler);
                } catch (Exception e) {
                    log.error(e.getMessage());
                    result.put("status", "error");
                    androidStepHandler.closeAndroidDriver();
                } finally {
                    result.put("msg", "openDriver");
                    try {
                        if (session != null && session.isOpen()) {
                            BytesTool.sendText(session, result.toJSONString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    private void exit(Session session) {
        synchronized (session) {
            ScheduledFuture<?> future = (ScheduledFuture<?>) session.getUserProperties().get("schedule");
            if (future != null) {
                future.cancel(true);
            }
            // 获取稳定的 udId
            String udId = (String) session.getUserProperties().get("udId");
            AndroidDeviceLocalStatus.finish(udId);
            IDevice iDevice = udIdMap.get(session);
            try {
                if (iDevice != null) {
                    AndroidStepHandler androidStepHandler = HandlerMap.getAndroidMap().get(udId);
                    if (androidStepHandler != null) {
                        androidStepHandler.closeAndroidDriver();
                    }
                }
            } catch (Exception e) {
                log.info("close driver failed.");
            } finally {
                // 无论 iDevice 是否为空，都要清理 HandlerMap，防止内存泄漏
                if (udId != null) {
                    HandlerMap.getAndroidMap().remove(udId);
                }
            }
            if (iDevice != null && udId != null) {
                AndroidDeviceBridgeTool.clearProxy(iDevice);
                AndroidDeviceBridgeTool.clearWebView(iDevice);
                AndroidSupplyTool.stopShare(udId);
                AndroidSupplyTool.stopPerfmon(udId);
                SGMTool.stopProxy(udId);
                AndroidAPKMap.getMap().remove(udId);
                AndroidTouchHandler.stopTouch(iDevice);

                // cleanup UIAutomator2 port forward if we recorded it
                Object uiaPortObj = session.getUserProperties().get("uiaPort");
                if (uiaPortObj instanceof Integer) {
                    AndroidDeviceBridgeTool.removeForward(iDevice, (Integer) uiaPortObj, 6790);
                }
            }
            removeUdIdMapAndSet(session);
            WebSocketSessionMap.removeSession(session);
            try {
                session.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Object id = session.getUserProperties().get("id");
            log.info("{} : quit.", id == null ? session.getId() : id.toString());
            try {
                if (iDevice != null && AndroidDeviceBridgeTool.getOrientation(iDevice) != 0) {
                    AndroidDeviceBridgeTool.pressKey(iDevice, AndroidKey.HOME);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
