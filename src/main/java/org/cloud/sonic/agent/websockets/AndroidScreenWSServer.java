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
import com.android.ddmlib.IDevice;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.AndroidAPKMap;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.ScreenMap;
import org.cloud.sonic.agent.common.maps.WebSocketSessionMap;
import org.cloud.sonic.agent.tests.android.minicap.MiniCapUtil;
import org.cloud.sonic.agent.tests.android.scrcpy.ScrcpyServerUtil;
import org.cloud.sonic.agent.tests.handlers.AndroidMonitorHandler;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/android/screen/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class AndroidScreenWSServer implements IAndroidWSServer {
    @Value("${sonic.agent.key}")
    private String key;
    private Map<String, String> typeMap = new ConcurrentHashMap<>();
    private Map<String, String> picMap = new ConcurrentHashMap<>();

    private AndroidMonitorHandler androidMonitorHandler = new AndroidMonitorHandler();

    @OnOpen
    public void onOpen(Session session, @PathParam("key") String secretKey,
                       @PathParam("udId") String udId, @PathParam("token") String token) throws Exception {
        log.info("Android screen websocket opening: udId={}, session={}", udId, session.getId());
        if (secretKey.length() == 0 || (!secretKey.equals(key)) || token.length() == 0) {
            log.info("Auth Failed!");
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }
        IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
        if (iDevice == null) {
            log.info("Target device is not connecting, please check the connection.");
            try {
                session.close();
            } catch (Exception ignored) {
            }
            return;
        }
        AndroidDeviceBridgeTool.screen(iDevice, "abort");

        session.getUserProperties().put("udId", udId);
        session.getUserProperties().put("id", String.format("%s-%s", this.getClass().getSimpleName(), udId));
        WebSocketSessionMap.addSession(session);
        saveUdIdMapAndSet(session, iDevice);

        int wait = 0;
        boolean isInstall = true;
        while (AndroidAPKMap.getMap().get(udId) == null || (!AndroidAPKMap.getMap().get(udId))) {
            Thread.sleep(500);
            wait++;
            if (wait >= 40) {
                isInstall = false;
                break;
            }
        }
        if (!isInstall) {
            log.info("Waiting for apk install timeout!");
            exit(session);
        }

        session.getUserProperties().put("schedule",ScheduleTool.schedule(() -> {
            log.info("time up!");
            if (session.isOpen()) {
                JSONObject errMsg = new JSONObject();
                errMsg.put("msg", "error");
                BytesTool.sendText(session, errMsg.toJSONString());
                exit(session);
            }
        }, BytesTool.remoteTimeout));

    }

    @OnClose
    public void onClose(Session session) {
        exit(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error(error.getMessage());
        error.printStackTrace();
        JSONObject errMsg = new JSONObject();
        errMsg.put("msg", "error");
        BytesTool.sendText(session, errMsg.toJSONString());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        JSONObject msg = JSON.parseObject(message);
        Object idObj = session.getUserProperties().get("id");
        log.info("{} send: {}", idObj == null ? session.getId() : idObj.toString(), msg);
        Object udIdObj = session.getUserProperties().get("udId");
        if (udIdObj == null) {
            return;
        }
        String udId = udIdObj.toString();
        switch (msg.getString("type")) {
            case "switch" -> {
                IDevice iDevice = udIdMap.get(session);
                if (iDevice == null) {
                    return;
                }
                typeMap.put(udId, msg.getString("detail"));
                typeMap.put(iDevice.getSerialNumber(), msg.getString("detail"));
                if (!androidMonitorHandler.isMonitorRunning(iDevice)) {
                    AtomicBoolean started = new AtomicBoolean(false);
                    androidMonitorHandler.startMonitor(iDevice, res -> {
                        JSONObject rotationJson = new JSONObject();
                        rotationJson.put("msg", "rotation");
                        rotationJson.put("value", Integer.parseInt(res) * 90);
                        BytesTool.sendText(session, rotationJson.toJSONString());
                        if (started.compareAndSet(false, true)) {
                            startScreen(session);
                        }
                    });
                    sendCurrentRotation(iDevice, session);
                    if (started.compareAndSet(false, true)) {
                        startScreen(session);
                    }
                } else {
                    sendCurrentRotation(iDevice, session);
                    startScreen(session);
                }
            }
            case "pic" -> {
                picMap.put(udId, msg.getString("detail"));
                IDevice iDevice = udIdMap.get(session);
                if (iDevice != null) {
                    picMap.put(iDevice.getSerialNumber(), msg.getString("detail"));
                }
                startScreen(session);
            }
        }
    }

    private void startScreen(Session session) {
        IDevice iDevice = udIdMap.get(session);
        if (iDevice != null) {
            String serialNumber = iDevice.getSerialNumber();
            String udId = String.valueOf(session.getUserProperties().get("udId"));
            log.info("Starting Android screen: udId={}, serialNumber={}, type={}",
                    udId, serialNumber, getScreenType(udId, serialNumber));
            Thread old = ScreenMap.getMap().get(session);
            if (old != null) {
                old.interrupt();
                int waitCount = 0;
                do {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    waitCount++;
                    if (waitCount >= 10) {
                        // 超时强制清理，防止无限等待
                        log.warn("Wait for old screen thread exit timeout, force remove from ScreenMap");
                        ScreenMap.getMap().remove(session);
                        break;
                    }
                }
                while (ScreenMap.getMap().get(session) != null);
            }
            typeMap.putIfAbsent(serialNumber, "scrcpy");
            int rotation = getCurrentRotation(iDevice);
            switch (getScreenType(udId, serialNumber)) {
                case "scrcpy" -> {
                    ScrcpyServerUtil scrcpyServerUtil = new ScrcpyServerUtil();
                    Thread scrcpyThread = scrcpyServerUtil.start(serialNumber, rotation, session);
                    if (scrcpyThread == null) {
                        log.warn("scrcpy screen failed to start: udId={}, serialNumber={}", udId, serialNumber);
                        sendSupportMessage(session, "scrcpy service failed to start, please check agent connectivity and scrcpy server jar version.");
                        return;
                    }
                    ScreenMap.getMap().put(session, scrcpyThread);
                }
                case "minicap" -> {
                    MiniCapUtil miniCapUtil = new MiniCapUtil();
                    AtomicReference<String[]> banner = new AtomicReference<>(new String[24]);
                    Thread miniCapThread = miniCapUtil.start(
                            serialNumber, banner, null,
                            picMap.get(serialNumber) == null ? "high" : picMap.get(serialNumber),
                            rotation, session
                    );
                    if (miniCapThread != null) {
                        ScreenMap.getMap().put(session, miniCapThread);
                    }
                }
            }
            JSONObject picFinish = new JSONObject();
            picFinish.put("msg", "picFinish");
            BytesTool.sendText(session, picFinish.toJSONString());
        }
    }

    private String getScreenType(String udId, String serialNumber) {
        String type = typeMap.get(serialNumber);
        if (type == null && udId != null) {
            type = typeMap.get(udId);
        }
        return type == null ? "scrcpy" : type;
    }

    private int getCurrentRotation(IDevice iDevice) {
        Integer rotation = AndroidDeviceManagerMap.getRotationMap().get(iDevice.getSerialNumber());
        if (rotation == null) {
            rotation = AndroidDeviceBridgeTool.getScreen(iDevice);
            AndroidDeviceManagerMap.getRotationMap().put(iDevice.getSerialNumber(), rotation);
        }
        return rotation;
    }

    private void sendCurrentRotation(IDevice iDevice, Session session) {
        int rotation = getCurrentRotation(iDevice);
        JSONObject rotationJson = new JSONObject();
        rotationJson.put("msg", "rotation");
        rotationJson.put("value", rotation * 90);
        BytesTool.sendText(session, rotationJson.toJSONString());
    }

    private void sendSupportMessage(Session session, String text) {
        JSONObject support = new JSONObject();
        support.put("msg", "support");
        support.put("text", text);
        BytesTool.sendText(session, support.toJSONString());
    }

    private void exit(Session session) {
        synchronized (session) {
            ScheduledFuture<?> future = (ScheduledFuture<?>) session.getUserProperties().get("schedule");
            if (future != null) {
                try {
                    future.cancel(true);
                } catch (Exception ignored) {
                }
            }
            Object udIdObj = session.getUserProperties().get("udId");
            String udId = udIdObj == null ? null : udIdObj.toString();
            try {
                androidMonitorHandler.stopMonitor(udIdMap.get(session));
            } catch (Exception ignored) {
            }
            try {
                WebSocketSessionMap.removeSession(session);
            } catch (Exception ignored) {
            }
            removeUdIdMapAndSet(session);
            if (udId != null) {
                AndroidDeviceManagerMap.getRotationMap().remove(udId);
            }
            Thread screenThread = ScreenMap.getMap().remove(session);
            if (screenThread != null) {
                screenThread.interrupt();
            }
            if (udId != null) {
                typeMap.remove(udId);
                picMap.remove(udId);
            }
            try {
                session.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Object idObj = session.getUserProperties().get("id");
            log.info("{} : quit.", idObj == null ? session.getId() : idObj.toString());
        }
    }
}
