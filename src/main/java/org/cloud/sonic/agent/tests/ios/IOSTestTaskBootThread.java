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
package org.cloud.sonic.agent.tests.ios;

import com.alibaba.fastjson.JSONObject;
import org.cloud.sonic.agent.bridge.ios.IOSDeviceLocalStatus;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.interfaces.ResultDetailStatus;
import org.cloud.sonic.agent.common.interfaces.StepType;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.tests.handlers.IOSStepHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class IOSTestTaskBootThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(IOSTestTaskBootThread.class);

    /**
     * ios-test-task-boot-{resultId}-{caseId}-{udid}
     */
    public final static String IOS_TEST_TASK_BOOT_PRE = "ios-test-task-boot-%s-%s-%s";

    private static final long DEFAULT_TASK_TIMEOUT_MINUTES = 60;
    private static final long DEFAULT_TASK_IDLE_TIMEOUT_MINUTES = 15;
    private static final String TASK_TIMEOUT_PROPERTY = "sonic.test.task.maxMinutes";
    private static final String TASK_TIMEOUT_ENV = "SONIC_TEST_TASK_MAX_MINUTES";
    private static final String TASK_IDLE_TIMEOUT_PROPERTY = "sonic.test.task.idleMaxMinutes";
    private static final String TASK_IDLE_TIMEOUT_ENV = "SONIC_TEST_TASK_IDLE_MAX_MINUTES";

    /**
     * 判断线程是否结束
     */
    private Semaphore finished = new Semaphore(0);

    private Boolean forceStop = false;


    /**
     * 一些任务信息
     */
    private JSONObject jsonObject;

    private IOSStepHandler iosStepHandler;

    /**
     * 测试步骤线程
     */
    private IOSRunStepThread runStepThread;

    /**
     * 性能数据采集线程
     */
    private IOSPerfDataThread perfDataThread;

    /**
     * 录像线程
     */
    private IOSRecordThread recordThread;

    /**
     * 测试结果id 0表示debug线程
     */
    private int resultId = 0;

    /**
     * 测试用例id 0表示debug线程
     */
    private int caseId = 0;

    /**
     * 设备序列号
     */
    private String udId;

    public String formatThreadName(String baseFormat) {
        return String.format(baseFormat, this.resultId, this.caseId, this.udId);
    }

    /**
     * debug线程构造
     */
    public IOSTestTaskBootThread() {
        this.setName(this.formatThreadName(IOS_TEST_TASK_BOOT_PRE));
        this.setDaemon(true);
    }

    /**
     * 任务线程构造
     *
     * @param jsonObject     任务数据
     * @param iosStepHandler ios步骤执行器
     */
    public IOSTestTaskBootThread(JSONObject jsonObject, IOSStepHandler iosStepHandler) {
        this.iosStepHandler = iosStepHandler;
        this.jsonObject = jsonObject;
        this.resultId = jsonObject.getInteger("rid") == null ? 0 : jsonObject.getInteger("rid");
        this.caseId = jsonObject.getInteger("cid") == null ? 0 : jsonObject.getInteger("cid");
        this.udId = jsonObject.getJSONObject("device") == null ? jsonObject.getString("udId")
                : jsonObject.getJSONObject("device").getString("udId");

        // 比如：test-task-thread-af80d1e4
        this.setName(String.format(IOS_TEST_TASK_BOOT_PRE, resultId, caseId, udId));
        this.setDaemon(true);
    }

    public void waitFinished() throws InterruptedException {
        finished.acquire();
    }

    public JSONObject getJsonObject() {
        return jsonObject;
    }

    public IOSStepHandler getIosStepHandler() {
        return iosStepHandler;
    }

    public IOSRunStepThread getRunStepThread() {
        return runStepThread;
    }

    public IOSPerfDataThread getPerfDataThread() {
        return perfDataThread;
    }

    public IOSRecordThread getRecordThread() {
        return recordThread;
    }

    public int getResultId() {
        return resultId;
    }

    public int getCaseId() {
        return caseId;
    }

    public String getUdId() {
        return udId;
    }

    public IOSTestTaskBootThread setUdId(String udId) {
        this.udId = udId;
        return this;
    }

    public Boolean getForceStop() {
        return forceStop;
    }

    private long getTaskTimeoutMs() {
        return getConfiguredTimeoutMs(TASK_TIMEOUT_PROPERTY, TASK_TIMEOUT_ENV, DEFAULT_TASK_TIMEOUT_MINUTES);
    }

    private long getTaskIdleTimeoutMs() {
        return getConfiguredTimeoutMs(TASK_IDLE_TIMEOUT_PROPERTY, TASK_IDLE_TIMEOUT_ENV, DEFAULT_TASK_IDLE_TIMEOUT_MINUTES);
    }

    private long getConfiguredTimeoutMs(String property, String env, long defaultMinutes) {
        String configured = System.getProperty(property);
        if (!StringUtils.hasText(configured)) {
            configured = System.getenv(env);
        }
        if (!StringUtils.hasText(configured)) {
            return TimeUnit.MINUTES.toMillis(defaultMinutes);
        }
        try {
            long minutes = Long.parseLong(configured.trim());
            if (minutes <= 0) {
                return 0L;
            }
            return TimeUnit.MINUTES.toMillis(minutes);
        } catch (NumberFormatException e) {
            log.warn("Invalid task timeout config {}={}, fallback to {} minutes.",
                    property, configured, defaultMinutes);
            return TimeUnit.MINUTES.toMillis(defaultMinutes);
        }
    }

    private void stopChildThreadsForTimeout(long timeoutMs, String reason) {
        long timeoutMinutes = TimeUnit.MILLISECONDS.toMinutes(timeoutMs);
        log.warn("iOS task {} after {} minutes, stopping child threads. device={}, rid={}, cid={}",
                reason, timeoutMinutes, udId, resultId, caseId);
        iosStepHandler.setResultDetailStatus(ResultDetailStatus.FAIL);
        iosStepHandler.getLog().sendStepLog(
                StepType.ERROR,
                "Task timeout",
                "Task " + reason + " for " + timeoutMinutes + " minutes and was stopped by agent watchdog."
        );
        if (runStepThread != null) {
            runStepThread.setStopped(true);
            runStepThread.interrupt();
        }
        if (recordThread != null) {
            recordThread.interrupt();
        }
        if (perfDataThread != null) {
            perfDataThread.interrupt();
        }
    }

    @Override
    public void run() {

        boolean startTestSuccess = false;

        try {
            int wait = 0;
            while (!IOSDeviceLocalStatus.startTest(udId)) {
                wait++;
                iosStepHandler.waitDevice(wait);
                if (wait >= 6 * 10) {
                    iosStepHandler.waitDeviceTimeOut();
                    return;
                } else {
                    Thread.sleep(10000);
                }
            }

            startTestSuccess = true;
            //启动测试
            try {
                int wdaPort = SibTool.startWda(udId)[0];
                iosStepHandler.startIOSDriver(udId, wdaPort);
            } catch (Exception e) {
                log.error(e.getMessage());
                iosStepHandler.closeIOSDriver();
                IOSDeviceLocalStatus.finishError(udId);
                return;
            }

            //电量过低退出测试
            if (iosStepHandler.getBattery()) {
                iosStepHandler.closeIOSDriver();
                IOSDeviceLocalStatus.finish(udId);
                return;
            }

            //正常运行步骤的线程
            runStepThread = new IOSRunStepThread(this);
            //性能数据获取线程
            perfDataThread = new IOSPerfDataThread(this);
            //录像线程
            recordThread = new IOSRecordThread(this);
            TaskManager.startChildThread(this.getName(), runStepThread, perfDataThread, recordThread);


            //等待两个线程结束了才结束方法
            long taskTimeoutMs = getTaskTimeoutMs();
            long taskIdleTimeoutMs = getTaskIdleTimeoutMs();
            long taskStartMs = System.currentTimeMillis();
            while ((recordThread.isAlive()) || (runStepThread.isAlive())) {
                long now = System.currentTimeMillis();
                if (taskTimeoutMs > 0 && now - taskStartMs >= taskTimeoutMs) {
                    stopChildThreadsForTimeout(taskTimeoutMs, "exceeded total timeout");
                    break;
                }
                if (taskIdleTimeoutMs > 0 && now - iosStepHandler.getLog().getLastSendTimeMs() >= taskIdleTimeoutMs) {
                    stopChildThreadsForTimeout(taskIdleTimeoutMs, "had no progress");
                    break;
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            log.error("Task error, stopping... cause by: {}", e.getMessage());
            iosStepHandler.setResultDetailStatus(ResultDetailStatus.FAIL);
            forceStop = true;
        } finally {
            if (startTestSuccess) {
                IOSDeviceLocalStatus.finish(udId);
                iosStepHandler.closeIOSDriver();
            }
            iosStepHandler.sendStatus();
            finished.release();
            TaskManager.clearTerminatedThreadByKey(this.getName());
        }
    }
}
