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

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import jakarta.websocket.Session;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tools.SpringTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.cloud.sonic.agent.tools.BytesTool.sendText;

public class ScrcpyLocalThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(ScrcpyLocalThread.class);

    public static final String ANDROID_START_MINICAP_SERVER_PRE = "android-scrcpy-start-scrcpy-server-task-%s-%s-%s";

    private static final String SCRCPY_SERVER_VERSION = "3.3.4";
    private final IDevice iDevice;
    private final int finalC;
    private final Session session;
    private final String udId;
    private final AndroidTestTaskBootThread androidTestTaskBootThread;
    private final Semaphore isFinish = new Semaphore(0);
    private final String videoCodec;
    private final int maxSize;
    private final int maxFps;
    private final int videoBitRate;

    public ScrcpyLocalThread(IDevice iDevice, int finalC, Session session, AndroidTestTaskBootThread androidTestTaskBootThread) {
        this.iDevice = iDevice;
        this.finalC = finalC;
        this.session = session;
        this.udId = iDevice.getSerialNumber();
        this.androidTestTaskBootThread = androidTestTaskBootThread;
        this.videoCodec = getScrcpyVideoCodec();
        this.maxSize = getScrcpyMaxSize();
        this.maxFps = getScrcpyMaxFps();
        this.videoBitRate = getScrcpyVideoBitRate();

        this.setDaemon(true);
        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_START_MINICAP_SERVER_PRE));
    }

    public IDevice getiDevice() {
        return iDevice;
    }

    public int getFinalC() {
        return finalC;
    }

    public Session getSession() {
        return session;
    }

    public String getUdId() {
        return udId;
    }

    public AndroidTestTaskBootThread getAndroidTestTaskBootThread() {
        return androidTestTaskBootThread;
    }

    public Semaphore getIsFinish() {
        return isFinish;
    }

    public String getVideoCodec() {
        return videoCodec;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public int getMaxFps() {
        return maxFps;
    }

    public int getVideoBitRate() {
        return videoBitRate;
    }

    @Override
    public void run() {
        File scrcpyServerFile = new File("plugins/sonic-android-scrcpy.jar");
        try {
            iDevice.pushFile(scrcpyServerFile.getAbsolutePath(), "/data/local/tmp/sonic-android-scrcpy.jar");
        } catch (Exception e) {
            log.error("push scrcpy server failed: {}", e.getMessage(), e);
            return;
        }

        boolean started = startScrcpyServer(buildModernCommand());

        if (!started && session != null && session.isOpen()) {
            JSONObject support = new JSONObject();
            support.put("msg", "support");
            support.put("text", "scrcpy service failed to start, please check scrcpy server jar version.");
            sendText(session, support.toJSONString());
        }
    }

    private boolean startScrcpyServer(String cmd) {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean reportedFailure = new AtomicBoolean(false);
        try {
            log.info("[scrcpy] start: version={}, codec={}, max_size={}, max_fps={}, video_bit_rate={}",
                    SCRCPY_SERVER_VERSION, videoCodec, maxSize, maxFps, videoBitRate);
            iDevice.executeShellCommand(cmd, new IShellOutputReceiver() {
                @Override
                public void addOutput(byte[] bytes, int offset, int length) {
                    String res = new String(bytes, offset, length, StandardCharsets.UTF_8);
                    log.info("[scrcpy] {}", res.trim());
                    if (!started.get() && isScrcpyStarted(res)) {
                        started.set(true);
                        isFinish.release();
                        return;
                    }
                    if (!started.get() && looksLikeStartupFailure(res) && reportedFailure.compareAndSet(false, true)) {
                        log.warn("[scrcpy] startup failure detected: {}", res.trim());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public boolean isCancelled() {
                    return Thread.currentThread().isInterrupted();
                }
            }, 0, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.info("{} scrcpy service stopped.", iDevice.getSerialNumber());
            log.error("[scrcpy] start error: {}", e.getMessage());
        }
        return started.get();
    }

    private boolean isScrcpyStarted(String output) {
        if (output == null) {
            return false;
        }
        return output.contains("Device") || output.contains("INFO: Device") || output.contains("Renderer");
    }

    private boolean looksLikeStartupFailure(String output) {
        if (output == null) {
            return false;
        }
        String normalized = output.toLowerCase();
        return normalized.contains("unknown option")
                || normalized.contains("unexpected argument")
                || normalized.contains("java.lang")
                || normalized.contains("exception")
                || normalized.contains("error")
                || normalized.contains("unable to")
                || normalized.contains("usage:");
    }

    private String buildModernCommand() {
        String bitRateOption = videoBitRate > 0 ? String.format("video_bit_rate=%d ", videoBitRate) : "";
        return String.format(
                "CLASSPATH=/data/local/tmp/sonic-android-scrcpy.jar app_process / com.genymobile.scrcpy.Server %s " +
                        "log_level=info video_codec=%s audio=false max_size=%d max_fps=%d %s" +
                        "tunnel_forward=true send_device_meta=false send_frame_meta=false send_dummy_byte=false " +
                        "send_codec_meta=false control=false show_touches=false stay_awake=false " +
                        "power_off_on_close=false clipboard_autosync=false",
                SCRCPY_SERVER_VERSION,
                videoCodec,
                maxSize,
                maxFps,
                bitRateOption
        );
    }

    private int getScrcpyMaxSize() {
        Integer v = getIntProperty(
                new String[]{"modules.android.scrcpy.max-size", "modules.android.scrcpy.maxSize"},
                0
        );
        if (v == null || v < 0) {
            return 0;
        }
        return v;
    }

    private int getScrcpyMaxFps() {
        Integer v = getIntProperty(
                new String[]{"modules.android.scrcpy.max-fps", "modules.android.scrcpy.maxFps"},
                60
        );
        if (v == null || v <= 0) {
            return 60;
        }
        return v;
    }

    private String getScrcpyVideoCodec() {
        String raw = getFirstNonBlankProperty(new String[]{
                "modules.android.scrcpy.video-codec",
                "modules.android.scrcpy.videoCodec"
        });
        if (raw == null) {
            return "h264";
        }
        String v = raw.trim().toLowerCase();
        if (!v.equals("h264") && !v.equals("h265")) {
            return "h264";
        }
        return v;
    }

    private int getScrcpyVideoBitRate() {
        Integer v = getIntProperty(
                new String[]{"modules.android.scrcpy.video-bit-rate", "modules.android.scrcpy.videoBitRate"},
                0
        );
        if (v == null || v < 0) {
            return 0;
        }
        return v;
    }

    private Integer getIntProperty(String[] keys, int defaultValue) {
        String raw = getFirstNonBlankProperty(keys);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String getFirstNonBlankProperty(String[] keys) {
        for (String k : keys) {
            String v = SpringTool.getPropertiesValue(k);
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
