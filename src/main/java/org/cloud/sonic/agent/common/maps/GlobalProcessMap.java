package org.cloud.sonic.agent.common.maps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GlobalProcessMap {
    private static Map<String, Process> processMap = new ConcurrentHashMap<>();

    public static Map<String, Process> getMap() {
        return processMap;
    }

    /**
     * 安全地终止并移除进程，防止内存泄漏
     * @param processName 进程名称
     * @return true 如果进程存在并被终止
     */
    public static boolean terminateAndRemove(String processName) {
        Process ps = processMap.remove(processName);
        if (ps != null) {
            try {
                ps.children().forEach(ProcessHandle::destroy);
                ps.destroy();
                // 等待进程完全终止
                ps.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    /**
     * 添加进程到 Map，如果已存在同名进程则先终止旧进程
     * @param processName 进程名称
     * @param process 新进程
     */
    public static void putAndTerminateOld(String processName, Process process) {
        terminateAndRemove(processName);
        if (process != null) {
            processMap.put(processName, process);
        }
    }
}
