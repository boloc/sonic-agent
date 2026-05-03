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
package org.cloud.sonic.agent.tests.android;

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceLocalStatus;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.interfaces.PlatformType;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.DevicesBatteryMap;
import org.cloud.sonic.agent.common.maps.WiFiDeviceIdMap;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Eason
 * @date 2022/4/24 20:45
 */
@Slf4j
public class AndroidBatteryThread implements Runnable {
    /**
     * second
     */
    public static final long DELAY = 30;

    public static final String THREAD_NAME = "android-battery-thread";

    public static final TimeUnit TIME_UNIT = TimeUnit.SECONDS;

    @Override
    public void run() {
        Thread.currentThread().setName(THREAD_NAME);
        if (TransportWorker.client == null) {
            return;
        }

        IDevice[] deviceList = AndroidDeviceBridgeTool.getRealOnLineDevices();
        if (deviceList == null || deviceList.length == 0) {
            return;
        }
        
        // 定期同步设备状态，确保在线设备不会卡在 ERROR 状态
        syncDeviceStatus(deviceList);
        
        List<JSONObject> detail = new ArrayList<>();
        for (IDevice iDevice : deviceList) {
            if (iDevice == null || iDevice.getState() != IDevice.DeviceState.ONLINE) {
                continue;
            }
            JSONObject jsonObject = new JSONObject();
            String serialNumber = iDevice.getSerialNumber();
            // 使用稳定的 udId（WiFi 设备只用 IP）
            String udId = WiFiDeviceIdMap.getStableUdId(serialNumber);
            String battery = AndroidDeviceBridgeTool
                    .executeCommand(iDevice, "dumpsys battery", 5000, java.util.concurrent.TimeUnit.MILLISECONDS).replace("Max charging voltage", "");
            if (StringUtils.hasText(battery)) {
                String realTem = battery.substring(battery.indexOf("temperature")).trim();
                int tem = BytesTool.getInt(realTem.substring(13, realTem.indexOf("\n")));
                String realLevel = battery.substring(battery.indexOf("level")).trim();
                int level = BytesTool.getInt(realLevel.substring(7, realLevel.indexOf("\n")));
                String realVol = battery.substring(battery.indexOf("voltage")).trim();
                int vol = BytesTool.getInt(realVol.substring(9, realVol.indexOf("\n")));
                jsonObject.put("udId", udId);
                jsonObject.put("tem", tem);
                jsonObject.put("level", level);
                jsonObject.put("vol", vol);
                detail.add(jsonObject);
                //control
                if (tem >= BytesTool.highTemp * 10) {
                    Integer times = DevicesBatteryMap.getTempMap().get(serialNumber);
                    if (times == null) {
                        //Send Error Msg
                        JSONObject errCall = new JSONObject();
                        errCall.put("msg", "errCall");
                        errCall.put("udId", udId);
                        errCall.put("tem", tem);
                        errCall.put("type", 1);
                        TransportWorker.send(errCall);
                        DevicesBatteryMap.getTempMap().put(serialNumber, 1);
                    } else {
                        DevicesBatteryMap.getTempMap().put(serialNumber, times + 1);
                    }
                    times = DevicesBatteryMap.getTempMap().get(serialNumber);
                    if (times >= (BytesTool.highTempTime * 2)) {
                        //Send shutdown Msg
                        JSONObject errCall = new JSONObject();
                        errCall.put("msg", "errCall");
                        errCall.put("udId", udId);
                        errCall.put("tem", tem);
                        errCall.put("type", 2);
                        TransportWorker.send(errCall);
                        AndroidDeviceBridgeTool.shutdown(iDevice);
                        DevicesBatteryMap.getTempMap().remove(serialNumber);
                    }
                } else {
                    DevicesBatteryMap.getTempMap().remove(serialNumber);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("msg", "battery");
        result.put("detail", detail);
        try {
            TransportWorker.send(result);
        } catch (Exception e) {
            log.error("Send battery msg failed, cause: ", e);
        }
    }
    
    /**
     * 同步设备状态，确保 ADB 在线的设备不会卡在 ERROR 状态
     * 此方法会检查所有在线设备，如果设备当前没有被使用（不在 statusMap 中），
     * 则主动发送 ONLINE 状态到服务器，以修复因异常导致的状态不同步问题
     */
    private void syncDeviceStatus(IDevice[] deviceList) {
        for (IDevice iDevice : deviceList) {
            if (iDevice == null || iDevice.getState() != IDevice.DeviceState.ONLINE) {
                continue;
            }
            String serialNumber = iDevice.getSerialNumber();
            String udId = WiFiDeviceIdMap.getStableUdId(serialNumber);
            
            // 如果设备当前没有被使用（不在 statusMap 中），则发送 ONLINE 状态
            // 这可以确保因异常而标记为 ERROR 的设备能够自动恢复
            if (AndroidDeviceManagerMap.getStatusMap().get(udId) == null) {
                try {
                    JSONObject deviceDetail = new JSONObject();
                    deviceDetail.put("msg", "deviceDetail");
                    deviceDetail.put("udId", udId);
                    deviceDetail.put("status", DeviceStatus.ONLINE);
                    deviceDetail.put("platform", PlatformType.ANDROID);
                    TransportWorker.send(deviceDetail);
                } catch (Exception e) {
                    log.debug("Failed to sync device status for {}: {}", udId, e.getMessage());
                }
            }
        }
    }
}
