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
package org.cloud.sonic.agent.tests.android.scrcpy;

import com.android.ddmlib.IDevice;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.tests.TaskManager;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tools.SpringTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;

import static org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread.ANDROID_TEST_TASK_BOOT_PRE;

public class ScrcpyServerUtil {
    private final Logger logger = LoggerFactory.getLogger(ScrcpyServerUtil.class);

    private static final int DEFAULT_QUEUE_SIZE = 120;

    public Thread start(
            String udId,
            int tor,
            Session session
    ) {
        return start(udId, tor, session, new AndroidTestTaskBootThread().setUdId(udId));
    }

    public Thread start(
            String udId,
            int tor,
            Session session,
            AndroidTestTaskBootThread androidTestTaskBootThread
    ) {
        IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
        String key = androidTestTaskBootThread.formatThreadName(ANDROID_TEST_TASK_BOOT_PRE);
        int screenRotation = tor == -1
                ? AndroidDeviceBridgeTool.getScreen(AndroidDeviceBridgeTool.getIDeviceByUdId(udId))
                : tor;

        ScrcpyLocalThread scrcpyThread = new ScrcpyLocalThread(iDevice, screenRotation, session, androidTestTaskBootThread);
        TaskManager.startChildThread(key, scrcpyThread);

        int wait = 0;
        boolean ready = false;
        while (!(ready = scrcpyThread.getIsFinish().tryAcquire())) {
            wait++;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (wait > 8) {
                break;
            }
        }

        if (!ready) {
            logger.warn("scrcpy server did not become ready for {} within {} ms, skip socket startup.",
                    udId, wait * 500);
            if (scrcpyThread.isAlive()) {
                scrcpyThread.interrupt();
            }
            return null;
        }

        ScrcpyInputSocketThread scrcpyInputSocketThread = new ScrcpyInputSocketThread(
                iDevice,
                new LinkedBlockingQueue<>(getQueueSize()),
                scrcpyThread,
                session
        );
        ScrcpyOutputSocketThread scrcpyOutputSocketThread = new ScrcpyOutputSocketThread(scrcpyInputSocketThread, session);
        TaskManager.startChildThread(key, scrcpyInputSocketThread, scrcpyOutputSocketThread);
        return scrcpyThread;
    }

    private int getQueueSize() {
        String raw = SpringTool.getPropertiesValue("modules.android.scrcpy.queue-size");
        if (raw == null || raw.isBlank()) {
            raw = SpringTool.getPropertiesValue("modules.android.scrcpy.queueSize");
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_QUEUE_SIZE;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 20) {
                return 20;
            }
            if (v > 1000) {
                return 1000;
            }
            return v;
        } catch (Exception ignored) {
            return DEFAULT_QUEUE_SIZE;
        }
    }
}
