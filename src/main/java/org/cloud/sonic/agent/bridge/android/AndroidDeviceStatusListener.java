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

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.interfaces.IsHMStatus;
import org.cloud.sonic.agent.common.interfaces.PlatformType;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.DevicesBatteryMap;
import org.cloud.sonic.agent.common.maps.WiFiDeviceIdMap;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AndroidDeviceStatusListener implements AndroidDebugBridge.IDeviceChangeListener {
    private final Logger logger = LoggerFactory.getLogger(AndroidDeviceStatusListener.class);

    private void send(IDevice device) {
        JSONObject deviceDetail = new JSONObject();
        deviceDetail.put("msg", "deviceDetail");

        String serialNumber = device.getSerialNumber();
        String udId = WiFiDeviceIdMap.getStableUdId(serialNumber);
        logger.info("Sending device info: serialNumber={}, udId={}, state={}", serialNumber, udId, device.getState());

        deviceDetail.put("udId", udId);
        deviceDetail.put("name", device.getProperty("ro.product.name"));
        deviceDetail.put("model", device.getProperty(IDevice.PROP_DEVICE_MODEL));
        deviceDetail.put("status", device.getState() == null ? null : device.getState().toString());
        deviceDetail.put("platform", PlatformType.ANDROID);
        if (device.getProperty("ro.config.ringtone") != null
                && device.getProperty("ro.config.ringtone").contains("Harmony")) {
            deviceDetail.put("version", device.getProperty("hw_sc.build.platform.version"));
            deviceDetail.put("isHm", IsHMStatus.IS_HM);
        } else {
            deviceDetail.put("version", device.getProperty(IDevice.PROP_BUILD_VERSION));
            deviceDetail.put("isHm", IsHMStatus.IS_ANDROID);
        }

        deviceDetail.put("size", AndroidDeviceBridgeTool.getScreenSize(device));
        deviceDetail.put("cpu", device.getProperty(IDevice.PROP_DEVICE_CPU_ABI));
        deviceDetail.put("manufacturer", device.getProperty(IDevice.PROP_DEVICE_MANUFACTURER));
        logger.info("Device detail to send: {}", deviceDetail.toJSONString());
        TransportWorker.send(deviceDetail);
    }

    @Override
    public void deviceConnected(IDevice device) {
        String serialNumber = device.getSerialNumber();
        String udId = WiFiDeviceIdMap.getStableUdId(serialNumber);
        logger.info("=== deviceConnected START ===");
        logger.info("serialNumber: {}, udId: {}, state: {}", serialNumber, udId, device.getState());

        WiFiDeviceIdMap.register(serialNumber);
        logger.info("WiFiDeviceIdMap registered: {}", serialNumber);
        clearLocalDeviceState(serialNumber, udId);
        DevicesBatteryMap.getTempMap().remove(serialNumber);

        IDevice.DeviceState state = device.getState();
        if (state == IDevice.DeviceState.ONLINE) {
            logger.info("Calling send()...");
            send(device);
        } else {
            sendOffline(device);
        }
        logger.info("=== deviceConnected END ===");
    }

    @Override
    public void deviceDisconnected(IDevice device) {
        String serialNumber = device.getSerialNumber();
        String udId = WiFiDeviceIdMap.getStableUdId(serialNumber);
        logger.info("Android device: {} OFFLINE.", serialNumber);

        WiFiDeviceIdMap.unregister(serialNumber);
        clearLocalDeviceState(serialNumber, udId);
        DevicesBatteryMap.getTempMap().remove(serialNumber);
        sendOffline(device);
    }

    @Override
    public void deviceChanged(IDevice device, int changeMask) {
        String serialNumber = device.getSerialNumber();
        String udId = WiFiDeviceIdMap.getStableUdId(serialNumber);
        IDevice.DeviceState state = device.getState();
        logger.info("=== deviceChanged === serialNumber: {}, udId: {}, state: {}, changeMask: {}",
                serialNumber, udId, state, changeMask);

        if (state == IDevice.DeviceState.OFFLINE) {
            WiFiDeviceIdMap.unregister(serialNumber);
            clearLocalDeviceState(serialNumber, udId);
            sendOffline(device);
            return;
        }

        WiFiDeviceIdMap.register(serialNumber);
        send(device);
    }

    private void sendOffline(IDevice device) {
        try {
            JSONObject deviceDetail = new JSONObject();
            deviceDetail.put("msg", "deviceDetail");
            String serialNumber = device.getSerialNumber();
            String udId = WiFiDeviceIdMap.getStableUdId(serialNumber);
            deviceDetail.put("udId", udId);
            deviceDetail.put("status", device.getState() == null ? "OFFLINE" : device.getState().toString());
            deviceDetail.put("platform", PlatformType.ANDROID);
            TransportWorker.send(deviceDetail);
        } catch (Exception e) {
            logger.warn("Send offline status failed: {}", e.getMessage());
        }
    }

    private void clearLocalDeviceState(String serialNumber, String udId) {
        String status = AndroidDeviceManagerMap.getStatusMap().get(udId);
        if (DeviceStatus.TESTING.equals(status) && TaskManager.udIdRunning(udId)) {
            logger.info("Keep local state for running test, udId={}", udId);
            return;
        }
        AndroidDeviceManagerMap.getStatusMap().remove(serialNumber);
        if (!serialNumber.equals(udId)) {
            AndroidDeviceManagerMap.getStatusMap().remove(udId);
        }
    }
}
