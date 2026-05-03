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
import jakarta.websocket.Session;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.maps.ScreenMap;
import org.cloud.sonic.agent.tests.android.AndroidTestTaskBootThread;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.PortTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;

/**
 * scrcpy socket线程
 * 通过端口转发，将设备视频流转发到此Socket
 */
public class ScrcpyInputSocketThread extends Thread {

    private final Logger log = LoggerFactory.getLogger(ScrcpyInputSocketThread.class);

    public final static String ANDROID_INPUT_SOCKET_PRE = "android-scrcpy-input-socket-task-%s-%s-%s";

    private IDevice iDevice;

    private BlockingQueue<byte[]> dataQueue;

    private ScrcpyLocalThread scrcpyLocalThread;

    private AndroidTestTaskBootThread androidTestTaskBootThread;

    private Session session;

    public ScrcpyInputSocketThread(IDevice iDevice, BlockingQueue<byte[]> dataQueue, ScrcpyLocalThread scrcpyLocalThread, Session session) {
        this.iDevice = iDevice;
        this.dataQueue = dataQueue;
        this.scrcpyLocalThread = scrcpyLocalThread;
        this.session = session;
        this.androidTestTaskBootThread = scrcpyLocalThread.getAndroidTestTaskBootThread();
        this.setDaemon(true);
        this.setName(androidTestTaskBootThread.formatThreadName(ANDROID_INPUT_SOCKET_PRE));
    }

    public IDevice getiDevice() {
        return iDevice;
    }

    public BlockingQueue<byte[]> getDataQueue() {
        return dataQueue;
    }

    public ScrcpyLocalThread getScrcpyLocalThread() {
        return scrcpyLocalThread;
    }

    public AndroidTestTaskBootThread getAndroidTestTaskBootThread() {
        return androidTestTaskBootThread;
    }

    public Session getSession() {
        return session;
    }

    // 增大缓冲区，减少内存分配和拷贝次数
    private static final int BUFFER_SIZE = 1024 * 1024 * 10;
    private static final int READ_BUFFER_SIZE = 1024 * 64;  // 64KB per read, 减少系统调用

    /**
     * If the queue is full, drop buffered frames until we meet a sync frame (IDR/SPS/PPS),
     * so the decoder can recover quickly and latency won't grow unbounded.
     */
    private volatile boolean needSyncFrame = false;

    @Override
    public void run() {
        int scrcpyPort = PortTool.getPort();
        AndroidDeviceBridgeTool.forward(iDevice, scrcpyPort, "scrcpy");
        Socket videoSocket = new Socket();
        InputStream inputStream = null;
        try {
            videoSocket.connect(new InetSocketAddress("localhost", scrcpyPort));
            // 优化 Socket 参数
            videoSocket.setSoTimeout(5000);  // 读取超时
            videoSocket.setTcpNoDelay(true);  // 禁用 Nagle 算法，减少延迟
            videoSocket.setReceiveBufferSize(256 * 1024);  // 256KB 接收缓冲区
            inputStream = videoSocket.getInputStream();
            if (videoSocket.isConnected()) {
                String sizeTotal = AndroidDeviceBridgeTool.getScreenSize(iDevice);
                String[] sizeParts = sizeTotal.split("x");
                int width = Integer.parseInt(sizeParts[0].trim());
                int height = Integer.parseInt(sizeParts[1].trim());

                int[] videoSize = getScrcpyVideoSize(width, height);
                width = videoSize[0];
                height = videoSize[1];

                log.info("[scrcpy] display size: {}x{}", width, height);

                JSONObject size = new JSONObject();
                size.put("msg", "size");
                size.put("width", width);
                size.put("height", height);
                BytesTool.sendText(session, size.toJSONString());
            }
            int readLength;
            int naLuIndex;
            int bufferLength = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            while (scrcpyLocalThread.isAlive()) {
                try {
                    readLength = inputStream.read(buffer, bufferLength, READ_BUFFER_SIZE);
                    if (readLength > 0) {
                        bufferLength += readLength;
                        for (int i = 5; i < bufferLength - 4; i++) {
                            if (buffer[i] == 0x00 &&
                                    buffer[i + 1] == 0x00 &&
                                    buffer[i + 2] == 0x00 &&
                                    buffer[i + 3] == 0x01
                            ) {
                                naLuIndex = i;
                                byte[] naluBuffer = new byte[naLuIndex];
                                System.arraycopy(buffer, 0, naluBuffer, 0, naLuIndex);
                                offerVideo(naluBuffer);
                                bufferLength -= naLuIndex;
                                System.arraycopy(buffer, naLuIndex, buffer, 0, bufferLength);
                                i = 5;
                            }
                        }
                    } else if (readLength < 0) {
                        // 流结束
                        log.info("scrcpy video stream ended.");
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    // 读取超时，继续循环检查线程状态，避免投屏卡住
                    log.debug("scrcpy socket read timeout, retrying...");
                }
            }
        } catch (IOException e) {
            log.error("scrcpy socket error: {}", e.getMessage());
        } finally {
            // ScreenMap 清理必须在 finally 内，确保任何异常都不会阻止清理
            // 否则 startScreen() 中的等待循环会死锁
            if (session != null) {
                ScreenMap.getMap().remove(session);
            }
            if (scrcpyLocalThread.isAlive()) {
                scrcpyLocalThread.interrupt();
                log.info("scrcpy thread closed.");
            }
            if (videoSocket.isConnected()) {
                try {
                    videoSocket.close();
                    log.info("scrcpy video socket closed.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                    log.info("scrcpy input stream closed.");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        AndroidDeviceBridgeTool.removeForward(iDevice, scrcpyPort, "scrcpy");
    }

    private int[] getScrcpyVideoSize(int deviceWidth, int deviceHeight) {
        int maxSize = scrcpyLocalThread.getMaxSize();
        if (maxSize <= 0) {
            return new int[]{deviceWidth, deviceHeight};
        }
        int longerSide = Math.max(deviceWidth, deviceHeight);
        if (longerSide <= maxSize) {
            return new int[]{deviceWidth, deviceHeight};
        }

        double scale = (double) maxSize / longerSide;
        int width = toEvenSize(deviceWidth * scale);
        int height = toEvenSize(deviceHeight * scale);
        return new int[]{Math.max(2, width), Math.max(2, height)};
    }

    private int toEvenSize(double value) {
        int size = (int) Math.round(value);
        return size % 2 == 0 ? size : size - 1;
    }

    /**
     * 最近一组完整的同步帧（SPS + PPS + IDR），用于丢帧后快速恢复解码器
     */
    private volatile byte[] lastSps = null;
    private volatile byte[] lastPps = null;
    private volatile byte[] lastIdr = null;
    
    private void offerVideo(byte[] naluBuffer) {
        // 缓存同步帧，用于丢帧后恢复
        cacheSyncFrameIfNeeded(naluBuffer);
        
        // When we previously dropped frames due to overload, wait for a sync frame.
        if (needSyncFrame && !isSyncFrame(naluBuffer)) {
            return;
        }

        // Fast path.
        if (dataQueue.offer(naluBuffer)) {
            needSyncFrame = false;
            return;
        }

        // Queue is full: smart drop strategy
        // 1. First try dropping only P-frames (non-sync frames) from queue head
        int dropped = 0;
        int targetDrop = Math.max(1, dataQueue.size() / 3);
        while (dropped < targetDrop && !dataQueue.isEmpty()) {
            byte[] head = dataQueue.peek();
            if (head != null && !isSyncFrame(head)) {
                dataQueue.poll();
                dropped++;
            } else {
                // Hit a sync frame, stop dropping to preserve decoder state
                break;
            }
        }
        
        // Try to offer again after dropping P-frames
        if (dataQueue.offer(naluBuffer)) {
            needSyncFrame = false;
            return;
        }

        // 2. Still full, clear queue but immediately inject cached sync frames
        dataQueue.clear();
        
        // Inject cached SPS/PPS/IDR to help decoder recover immediately
        if (lastSps != null && lastPps != null && lastIdr != null) {
            dataQueue.offer(lastSps);
            dataQueue.offer(lastPps);
            dataQueue.offer(lastIdr);
            needSyncFrame = false;
            log.debug("Queue overflow: cleared and injected cached sync frames for recovery");
        } else {
            needSyncFrame = true;
            log.debug("Queue overflow: cleared, waiting for next sync frame");
        }

        // Offer current frame if it's a sync frame
        if (isSyncFrame(naluBuffer)) {
            dataQueue.offer(naluBuffer);
            needSyncFrame = false;
        }
    }
    
    /**
     * 缓存SPS/PPS/IDR帧，用于丢帧后快速恢复
     */
    private void cacheSyncFrameIfNeeded(byte[] data) {
        String codec = scrcpyLocalThread.getVideoCodec();
        if (codec == null || codec.isBlank() || codec.equalsIgnoreCase("h264")) {
            int type = parseH264NaluType(data);
            switch (type) {
                case 7 -> lastSps = data.clone();  // SPS
                case 8 -> lastPps = data.clone();  // PPS
                case 5 -> lastIdr = data.clone();  // IDR
            }
        } else if (codec.equalsIgnoreCase("h265")) {
            int type = parseH265NaluType(data);
            switch (type) {
                case 33 -> lastSps = data.clone();  // SPS
                case 34 -> lastPps = data.clone();  // PPS
                case 19, 20 -> lastIdr = data.clone();  // IDR
            }
        }
    }

    private boolean isSyncFrame(byte[] data) {
        String codec = scrcpyLocalThread.getVideoCodec();
        // Default to h264 for safety.
        if (codec == null || codec.isBlank() || codec.equalsIgnoreCase("h264")) {
            int type = parseH264NaluType(data);
            // IDR=5, SPS=7, PPS=8
            return type == 5 || type == 7 || type == 8;
        }
        if (codec.equalsIgnoreCase("h265")) {
            int type = parseH265NaluType(data);
            // VPS=32, SPS=33, PPS=34, IDR_W_RADL=19, IDR_N_LP=20
            return type == 32 || type == 33 || type == 34 || type == 19 || type == 20;
        }
        return true;
    }

    private int parseH264NaluType(byte[] data) {
        int idx = findNaluHeaderIndex(data);
        if (idx < 0 || idx >= data.length) {
            return -1;
        }
        return data[idx] & 0x1F;
    }

    private int parseH265NaluType(byte[] data) {
        int idx = findNaluHeaderIndex(data);
        if (idx < 0 || idx >= data.length) {
            return -1;
        }
        return (data[idx] >> 1) & 0x3F;
    }

    /**
     * Find the first byte after Annex-B start code (0x000001 or 0x00000001).
     */
    private int findNaluHeaderIndex(byte[] data) {
        if (data == null || data.length < 5) {
            return -1;
        }
        for (int i = 0; i < data.length - 4; i++) {
            // 00 00 00 01
            if (data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x00 && data[i + 3] == 0x01) {
                return i + 4;
            }
            // 00 00 01
            if (data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x01) {
                return i + 3;
            }
        }
        return -1;
    }
}
