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
package org.cloud.sonic.agent.common.maps;

import java.util.concurrent.ConcurrentHashMap;

/**
 * WiFi 设备 ID 映射工具类
 * 用于处理 WiFi 调试设备的稳定标识问题
 * 
 * 问题背景：
 * - USB 设备的 serialNumber 是固定的硬件序列号
 * - WiFi 设备的 serialNumber 是 IP:端口 格式，端口会变化
 * - 端口变化会导致 Server 端认为是新设备
 * 
 * 解决方案：
 * - 对于 WiFi 设备，只使用 IP 作为 udId（发送给 Server 的标识）
 * - 维护 udId → serialNumber 的映射，用于 Agent 内部查找设备
 * 
 * @author Sonic
 * @date 2024
 */
public class WiFiDeviceIdMap {
    
    /**
     * 存储 udId（稳定ID）到 serialNumber（实际网络地址）的映射
     * key: udId (对于 WiFi 设备是 IP，对于 USB 设备是 serialNumber)
     * value: serialNumber (完整的设备标识，WiFi 设备是 IP:端口)
     */
    private static final ConcurrentHashMap<String, String> udIdToSerialMap = new ConcurrentHashMap<>();

    /**
     * 判断是否为 WiFi 调试设备
     * WiFi 设备的 serialNumber 格式为 IP:端口
     */
    public static boolean isWiFiDevice(String serialNumber) {
        return serialNumber != null && serialNumber.contains(":");
    }

    /**
     * 从 serialNumber 提取稳定的 udId
     * - WiFi 设备：返回 IP 部分（去掉端口）
     * - USB 设备：返回原始 serialNumber
     * 
     * @param serialNumber 设备的原始序列号
     * @return 稳定的设备唯一标识
     */
    public static String getStableUdId(String serialNumber) {
        if (isWiFiDevice(serialNumber)) {
            // WiFi 设备：只取 IP 部分
            int colonIndex = serialNumber.lastIndexOf(":");
            if (colonIndex > 0) {
                return serialNumber.substring(0, colonIndex);
            }
        }
        // USB 设备或解析失败：返回原始值
        return serialNumber;
    }

    /**
     * 注册设备映射关系
     * 在设备连接时调用，更新 udId → serialNumber 的映射
     * 
     * @param serialNumber 设备的完整序列号
     */
    public static void register(String serialNumber) {
        String udId = getStableUdId(serialNumber);
        udIdToSerialMap.put(udId, serialNumber);
    }

    /**
     * 移除设备映射关系
     * 在设备断开时调用
     * 
     * @param serialNumber 设备的完整序列号
     */
    public static void unregister(String serialNumber) {
        String udId = getStableUdId(serialNumber);
        // 只有当前映射的 serialNumber 与要移除的一致时才移除
        // 防止新连接的设备被错误移除
        udIdToSerialMap.remove(udId, serialNumber);
    }

    /**
     * 根据 udId 获取实际的 serialNumber
     * 用于 Agent 内部通过 udId 查找设备
     * 
     * @param udId 稳定的设备标识
     * @return 设备的完整 serialNumber，如果不存在则返回 udId 本身
     */
    public static String getSerialNumber(String udId) {
        String serialNumber = udIdToSerialMap.get(udId);
        return serialNumber != null ? serialNumber : udId;
    }

    /**
     * 检查 udId 是否已注册
     */
    public static boolean contains(String udId) {
        return udIdToSerialMap.containsKey(udId);
    }

    /**
     * 清空所有映射（一般不需要调用）
     */
    public static void clear() {
        udIdToSerialMap.clear();
    }
}
