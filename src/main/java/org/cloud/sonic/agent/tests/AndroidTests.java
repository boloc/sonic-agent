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
package org.cloud.sonic.agent.tests;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.interfaces.ResultDetailStatus;
import org.cloud.sonic.agent.common.interfaces.StepType;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tests.handlers.AndroidStepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.testng.ITestContext;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.cloud.sonic.agent.tests.SuiteListener.runningTestsMap;

public class AndroidTests {
    private final Logger logger = LoggerFactory.getLogger(AndroidTests.class);
    private static final long DEFAULT_DEVICE_BUSY_WAIT_MINUTES = 20;
    private static final String DEVICE_BUSY_WAIT_PROPERTY = "sonic.test.deviceBusyMaxMinutes";
    private static final String DEVICE_BUSY_WAIT_ENV = "SONIC_TEST_DEVICE_BUSY_MAX_MINUTES";

    @DataProvider(name = "testData", parallel = true)
    public Object[][] getTestData(ITestContext context) {
        JSONObject dataInfo = JSON.parseObject(context.getCurrentXmlTest().getParameter("dataInfo"));
        List<JSONObject> dataProvider = new ArrayList<>();
        for (JSONObject iDevice : dataInfo.getJSONArray("device").toJavaList(JSONObject.class)) {
            String udId = iDevice.getString("udId");
            IDevice onlineDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
            if (onlineDevice == null || onlineDevice.getState() == null
                    || !onlineDevice.getState().toString().equals("ONLINE")) {
                finishUnavailableDevice(dataInfo, iDevice,
                        onlineDevice == null ? "NOT_CONNECTED" : onlineDevice.getState().toString());
                continue;
            }
            JSONObject deviceTestData = new JSONObject();
            deviceTestData.put("steps", dataInfo.getJSONArray("steps"));
            deviceTestData.put("rid", dataInfo.getInteger("rid"));
            deviceTestData.put("cid", dataInfo.getInteger("cid"));
            deviceTestData.put("gp", dataInfo.getJSONObject("gp"));
            deviceTestData.put("perf", dataInfo.getJSONObject("perf"));
            deviceTestData.put("device", iDevice);
            dataProvider.add(deviceTestData);
        }
        Object[][] testDataProvider = new Object[dataProvider.size()][];
        for (int i = 0; i < dataProvider.size(); i++) {
            testDataProvider[i] = new Object[]{dataProvider.get(i)};
        }
        return testDataProvider;
    }

    @Test(dataProvider = "testData")
    public void run(JSONObject jsonObject) throws IOException {
        int rid = jsonObject.getInteger("rid");
        String udId = jsonObject.getJSONObject("device").getString("udId");
        if (TaskManager.ridRunning(rid, udId)) {
            logger.info("Task repeat! Maybe cause by network, ignore...");
            return;
        }
        int cid = jsonObject.getInteger("cid");
        AndroidStepHandler androidStepHandler = new AndroidStepHandler();
        JSONObject gp = jsonObject.getJSONObject("gp");
        androidStepHandler.setGlobalParams(gp);
        androidStepHandler.setTestMode(cid, rid, udId, DeviceStatus.TESTING, "");

        try {
            if (!waitDeviceAvailable(rid, cid, udId, androidStepHandler)) {
                finishBusyDevice(rid, cid, udId, androidStepHandler);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishBusyDevice(rid, cid, udId, androidStepHandler);
            return;
        }

        AndroidTestTaskBootThread bootThread = new AndroidTestTaskBootThread(jsonObject, androidStepHandler);
        if (!runningTestsMap.containsKey(rid + "-" + udId)) {
            logger.info("Task {} interrupted, skip.", bootThread.getName());
            return;
        }
        TaskManager.startBootThread(bootThread);
        try {
            bootThread.waitFinished();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (bootThread.getForceStop()) {
            logger.info("Task {} interrupted, skip.", bootThread.getName());
            return;
        }
        logger.info("Task {} finish.", bootThread.getName());
    }

    private void finishUnavailableDevice(JSONObject dataInfo, JSONObject device, String state) {
        int rid = dataInfo.getInteger("rid") == null ? 0 : dataInfo.getInteger("rid");
        int cid = dataInfo.getInteger("cid") == null ? 0 : dataInfo.getInteger("cid");
        String udId = device.getString("udId");
        AndroidStepHandler androidStepHandler = new AndroidStepHandler();
        androidStepHandler.setGlobalParams(dataInfo.getJSONObject("gp"));
        androidStepHandler.setTestMode(cid, rid, udId, DeviceStatus.TESTING, "");
        androidStepHandler.setResultDetailStatus(ResultDetailStatus.WARN);
        androidStepHandler.sendStatus();
        runningTestsMap.remove(rid + "-" + udId);
        logger.warn("Scheduled test skipped because device is not ONLINE, rid={}, cid={}, udId={}, state={}",
                rid, cid, udId, state);
    }

    private void finishBusyDevice(int rid, int cid, String udId, AndroidStepHandler androidStepHandler) {
        androidStepHandler.getLog().sendStepLog(StepType.ERROR,
                "Device is still occupied, stop this trigger",
                "rid=" + rid + ", cid=" + cid + ", udId=" + udId);
        androidStepHandler.setResultDetailStatus(ResultDetailStatus.FAIL);
        androidStepHandler.sendStatus();
        runningTestsMap.remove(rid + "-" + udId);
        logger.warn("Scheduled test failed because previous run is still active, rid={}, cid={}, udId={}",
                rid, cid, udId);
    }

    private boolean waitDeviceAvailable(int rid, int cid, String udId, AndroidStepHandler androidStepHandler)
            throws InterruptedException {
        long waitMs = getDeviceBusyWaitMs();
        long startMs = System.currentTimeMillis();
        boolean logged = false;
        while (TaskManager.udIdRunning(udId)) {
            if (!logged) {
                androidStepHandler.getLog().sendStepLog(StepType.INFO,
                        "Device is occupied, waiting for previous scheduled test to finish",
                        "rid=" + rid + ", cid=" + cid + ", udId=" + udId
                                + ", waitMaxMinutes=" + TimeUnit.MILLISECONDS.toMinutes(waitMs));
                logged = true;
            }
            if (waitMs > 0 && System.currentTimeMillis() - startMs >= waitMs) {
                TaskManager.clearStaleRunningUdId(udId);
                return !TaskManager.udIdRunning(udId);
            }
            Thread.sleep(5000);
        }
        return true;
    }

    private long getDeviceBusyWaitMs() {
        String configured = System.getProperty(DEVICE_BUSY_WAIT_PROPERTY);
        if (!StringUtils.hasText(configured)) {
            configured = System.getenv(DEVICE_BUSY_WAIT_ENV);
        }
        if (!StringUtils.hasText(configured)) {
            return TimeUnit.MINUTES.toMillis(DEFAULT_DEVICE_BUSY_WAIT_MINUTES);
        }
        try {
            long minutes = Long.parseLong(configured.trim());
            if (minutes <= 0) {
                return 0L;
            }
            return TimeUnit.MINUTES.toMillis(minutes);
        } catch (NumberFormatException e) {
            logger.warn("Invalid device busy wait config {}={}, fallback to {} minutes.",
                    DEVICE_BUSY_WAIT_PROPERTY, configured, DEFAULT_DEVICE_BUSY_WAIT_MINUTES);
            return TimeUnit.MINUTES.toMillis(DEFAULT_DEVICE_BUSY_WAIT_MINUTES);
        }
    }
}
