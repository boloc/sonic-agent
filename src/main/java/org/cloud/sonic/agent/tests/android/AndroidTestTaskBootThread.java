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
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceLocalStatus;
import org.cloud.sonic.agent.common.interfaces.ResultDetailStatus;
import org.cloud.sonic.agent.common.interfaces.StepType;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.tests.handlers.AndroidMonitorHandler;
import org.cloud.sonic.agent.tests.handlers.AndroidStepHandler;
import org.cloud.sonic.agent.tests.handlers.AndroidTouchHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * android启动各个子任务的线程
 *
 * @author Eason(main) JayWenStar(until e1a877b7)
 * @date 2021/12/2 12:33 上午
 */
public class AndroidTestTaskBootThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(AndroidTestTaskBootThread.class);

    /**
     * android-test-task-boot-{resultId}-{caseId}-{udid}
     */
    public final static String ANDROID_TEST_TASK_BOOT_PRE = "android-test-task-boot-%s-%s-%s";

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

    /**
     * Android步骤处理器，包含一些状态信息
     */
    private AndroidStepHandler androidStepHandler;

    /**
     * 测试步骤线程
     */
    private AndroidRunStepThread runStepThread;

    /**
     * 性能数据采集线程
     */
    private AndroidPerfDataThread perfDataThread;

    /**
     * 录像线程
     */
    private AndroidRecordThread recordThread;

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
    public AndroidTestTaskBootThread() {
        this.setName(this.formatThreadName(ANDROID_TEST_TASK_BOOT_PRE));
        this.setDaemon(true);
    }

    /**
     * 任务线程构造
     *
     * @param jsonObject         任务数据
     * @param androidStepHandler android步骤执行器
     */
    public AndroidTestTaskBootThread(JSONObject jsonObject, AndroidStepHandler androidStepHandler) {
        this.androidStepHandler = androidStepHandler;
        this.jsonObject = jsonObject;
        this.resultId = jsonObject.getInteger("rid") == null ? 0 : jsonObject.getInteger("rid");
        this.caseId = jsonObject.getInteger("cid") == null ? 0 : jsonObject.getInteger("cid");
        this.udId = jsonObject.getJSONObject("device") == null ? jsonObject.getString("udId") :
                jsonObject.getJSONObject("device").getString("udId");

        // 比如：test-task-thread-af80d1e4
        this.setName(String.format(ANDROID_TEST_TASK_BOOT_PRE, resultId, caseId, udId));
        this.setDaemon(true);
    }

    public void waitFinished() throws InterruptedException {
        finished.acquire();
    }

    public JSONObject getJsonObject() {
        return jsonObject;
    }

    public AndroidStepHandler getAndroidStepHandler() {
        return androidStepHandler;
    }

    public AndroidRunStepThread getRunStepThread() {
        return runStepThread;
    }

    public AndroidPerfDataThread getPerfDataThread() {
        return perfDataThread;
    }

    public AndroidRecordThread getRecordThread() {
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

    public AndroidTestTaskBootThread setUdId(String udId) {
        this.udId = udId;
        return this;
    }

    public AndroidTestTaskBootThread setResultId(int resultId) {
        this.resultId = resultId;
        return this;
    }

    public AndroidTestTaskBootThread setCaseId(int caseId) {
        this.caseId = caseId;
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
        log.warn("Android task {} after {} minutes, stopping child threads. device={}, rid={}, cid={}",
                reason, timeoutMinutes, udId, resultId, caseId);
        androidStepHandler.setResultDetailStatus(ResultDetailStatus.FAIL);
        androidStepHandler.getLog().sendStepLog(
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
        boolean statusSent = false;
        IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
        AndroidMonitorHandler androidMonitorHandler = new AndroidMonitorHandler();

        try {
            int wait = 0;
            while (!AndroidDeviceLocalStatus.startTest(udId)) {
                wait++;
                androidStepHandler.waitDevice(wait);
                if (wait >= 6 * 10) {
                    androidStepHandler.waitDeviceTimeOut();
                    return;
                } else {
                    Thread.sleep(10000);
                }
            }

            startTestSuccess = true;
            try {
                // 定时任务执行前主动唤醒屏幕，防止高版本 Android 因屏幕关闭导致 UIAutomator2 超时
                AndroidDeviceBridgeTool.wakeUpScreen(iDevice);
                Thread.sleep(1000); // 等待屏幕完全唤醒

                // Android 15/部分 ROM 的锁屏界面可能无法被 UIAutomator2 正常操作（层级不可见/请求卡住）
                // 注意：默认不在这里“抢跑解锁”，避免影响用户在步骤里编排的“解锁流程”。
                // 如需在启动 UIA2 前强制解锁，请在 gp 中设置 autoUnlockBeforeUia=true，并提供 PIN（纯数字）。
                String unlockPin = null;
                boolean autoUnlockBeforeUia = false;
                try {
                    unlockPin = jsonObject.getString("pwd");
                } catch (Exception ignored) {
                }
                if (!StringUtils.hasText(unlockPin)) {
                    try {
                        JSONObject gp = jsonObject.getJSONObject("gp");
                        if (gp != null) {
                            unlockPin = gp.getString("deviceUnlockPin");
                            if (!StringUtils.hasText(unlockPin)) unlockPin = gp.getString("unlockPin");
                            if (!StringUtils.hasText(unlockPin)) unlockPin = gp.getString("pin");
                            autoUnlockBeforeUia = Boolean.TRUE.equals(gp.getBoolean("autoUnlockBeforeUia"));
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (autoUnlockBeforeUia && StringUtils.hasText(unlockPin)) {
                    AndroidDeviceBridgeTool.unlockByPinIfLocked(iDevice, unlockPin);
                }
                
                int port = AndroidDeviceBridgeTool.startUiaServerWithRetry(iDevice);
                if (!AndroidDeviceBridgeTool.installSonicApk(iDevice)) {
                    AndroidTouchHandler.switchTouchMode(iDevice, AndroidTouchHandler.TouchMode.ADB);
                } else {
                    androidMonitorHandler.startMonitor(iDevice, res -> {
                    });
                    AndroidTouchHandler.startTouch(iDevice);
                }
                androidStepHandler.startAndroidDriver(iDevice, port);
            } catch (Exception e) {
                log.error("Android test bootstrap failed, task will finish immediately. device={}, rid={}, cid={}",
                        udId, resultId, caseId, e);
                androidStepHandler.setResultDetailStatus(ResultDetailStatus.FAIL);
                androidStepHandler.sendStatus();
                statusSent = true;
                androidStepHandler.closeAndroidDriver();
                androidMonitorHandler.stopMonitor(iDevice);
                AndroidTouchHandler.stopTouch(iDevice);
                AndroidDeviceLocalStatus.finishError(udId);
                return;
            }

            //电量过低退出测试
            if (androidStepHandler.getBattery()) {
                androidStepHandler.closeAndroidDriver();
                androidMonitorHandler.stopMonitor(iDevice);
                AndroidTouchHandler.stopTouch(iDevice);
                AndroidDeviceLocalStatus.finish(udId);
                return;
            }

            //正常运行步骤的线程
            runStepThread = new AndroidRunStepThread(this);
            //性能数据获取线程
            perfDataThread = new AndroidPerfDataThread(this);
            //录像线程
            recordThread = new AndroidRecordThread(this);
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
                if (taskIdleTimeoutMs > 0 && now - androidStepHandler.getLog().getLastSendTimeMs() >= taskIdleTimeoutMs) {
                    stopChildThreadsForTimeout(taskIdleTimeoutMs, "had no progress");
                    break;
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            log.error("Task error, stopping... cause by: {}", e.getMessage());
            androidStepHandler.setResultDetailStatus(ResultDetailStatus.FAIL);
            forceStop = true;
        } finally {
            if (startTestSuccess) {
                AndroidDeviceLocalStatus.finish(udId);
                androidStepHandler.closeAndroidDriver();
                androidMonitorHandler.stopMonitor(iDevice);
                AndroidTouchHandler.stopTouch(iDevice);
            }
            if (!statusSent) {
                androidStepHandler.sendStatus();
            }
            finished.release();
            TaskManager.clearTerminatedThreadByKey(this.getName());
        }
    }
}
