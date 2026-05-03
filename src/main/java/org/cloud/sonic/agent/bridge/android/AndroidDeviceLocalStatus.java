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
import com.android.ddmlib.IDevice;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.WiFiDeviceIdMap;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AndroidDeviceLocalStatus {
    private static final Logger log = LoggerFactory.getLogger(AndroidDeviceLocalStatus.class);

    public static void send(String udId, String status) {
        JSONObject deviceDetail = new JSONObject();
        deviceDetail.put("msg", "deviceDetail");
        deviceDetail.put("udId", udId);
        deviceDetail.put("status", status);
        TransportWorker.send(deviceDetail);
    }

    public static boolean startTest(String udId) {
        synchronized (AndroidDeviceLocalStatus.class) {
            String status = AndroidDeviceManagerMap.getStatusMap().get(udId);
            if (status == null) {
                markTesting(udId);
                return true;
            }

            if (DeviceStatus.TESTING.equals(status) && !TaskManager.udIdRunning(udId)) {
                log.warn("Clear stale TESTING status before starting test, udId={}", udId);
                clearStatus(udId);
                markTesting(udId);
                return true;
            }
            return false;
        }
    }

    public static void startDebug(String udId) {
        send(udId, DeviceStatus.DEBUGGING);
        AndroidDeviceManagerMap.getStatusMap().put(udId, DeviceStatus.DEBUGGING);
    }

    public static void finish(String udId) {
        if (AndroidDeviceBridgeTool.getIDeviceByUdId(udId) != null) {
            String status = AndroidDeviceManagerMap.getStatusMap().get(udId);
            if (DeviceStatus.DEBUGGING.equals(status) || DeviceStatus.TESTING.equals(status)) {
                send(udId, DeviceStatus.ONLINE);
            }
        }
        clearStatus(udId);
    }

    public static void finishError(String udId) {
        if (AndroidDeviceBridgeTool.getIDeviceByUdId(udId) != null) {
            String status = AndroidDeviceManagerMap.getStatusMap().get(udId);
            if (DeviceStatus.DEBUGGING.equals(status) || DeviceStatus.TESTING.equals(status)) {
                send(udId, DeviceStatus.ERROR);
            }
        }
        clearStatus(udId);
        scheduleDeviceRecovery(udId);
    }

    private static void markTesting(String udId) {
        send(udId, DeviceStatus.TESTING);
        AndroidDeviceManagerMap.getStatusMap().put(udId, DeviceStatus.TESTING);
    }

    private static void clearStatus(String udId) {
        AndroidDeviceManagerMap.getStatusMap().remove(udId);
        String serialNumber = WiFiDeviceIdMap.getSerialNumber(udId);
        if (!udId.equals(serialNumber)) {
            AndroidDeviceManagerMap.getStatusMap().remove(serialNumber);
        }
    }

    private static void scheduleDeviceRecovery(String udId) {
        AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
            try {
                Thread.sleep(5000);

                if (AndroidDeviceManagerMap.getStatusMap().get(udId) == null) {
                    IDevice device = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
                    if (device != null && device.getState() == IDevice.DeviceState.ONLINE) {
                        send(udId, DeviceStatus.ONLINE);
                        log.info("Device {} auto-recovered from ERROR to ONLINE", udId);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
