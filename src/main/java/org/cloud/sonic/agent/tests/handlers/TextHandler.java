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
package org.cloud.sonic.agent.tests.handlers;

import com.alibaba.fastjson.JSONObject;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Random;

/**
 * @author Eason
 */
public class TextHandler {

    /**
     * 将普通文本转换为正则表达式，在每个字符之间插入 .*
     * 用于忽略不可见字符（如软连字符、零宽空格等）的匹配
     * 例如：91台湾版 -> 9.*1.*台.*湾.*版
     */
    public static String toFuzzyRegex(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 转义正则表达式中的特殊字符
            if ("\\[]{}()^$.|*+?".indexOf(c) >= 0) {
                sb.append("\\").append(c);
            } else {
                sb.append(c);
            }
            // 在每个字符之间插入 .* 以匹配任意不可见字符
            if (i < text.length() - 1) {
                sb.append(".*");
            }
        }
        return sb.toString();
    }

    public static String replaceTrans(String text, JSONObject globalParams) {
        // 防止 globalParams 为 null 导致替换失败
        if (globalParams == null) {
            globalParams = new JSONObject();
        }

        // 处理 {{regex:变量名}} 语法，将变量值转换为模糊匹配的正则表达式
        while (text.contains("{{regex:") && text.contains("}}")) {
            int start = text.indexOf("{{regex:");
            int end = text.indexOf("}}", start);
            if (end > start) {
                String varName = text.substring(start + 8, end);
                String varValue = globalParams.getString(varName);
                String replacement = (varValue != null) ? toFuzzyRegex(varValue) : "";
                text = text.substring(0, start) + replacement + text.substring(end + 2);
            } else {
                break;
            }
        }

        if (text.contains("{{random}}")) {
            String random = (int) (Math.random() * 10 + Math.random() * 10 * 2) + 5 + "";
            text = text.replace("{{random}}", random);
        }
        if (text.contains("{{timestamp}}")) {
            String timeMillis = Calendar.getInstance().getTimeInMillis() + "";
            text = text.replace("{{timestamp}}", timeMillis);
        }
        if (text.contains("{{") && text.contains("}}")) {
            String tail = text.substring(text.indexOf("{{") + 2);
            if (tail.contains("}}")) {
                String child = tail.substring(tail.indexOf("}}") + 2);
                String middle = tail.substring(0, tail.indexOf("}}"));
                text = text.substring(0, text.indexOf("}}") + 2);
                if (globalParams.getString(middle) != null) {
                    text = text.replace("{{" + middle + "}}", globalParams.getString(middle));
                } else {
                    if (middle.matches("random\\[\\d\\]")) {
                        int t = Integer.parseInt(middle.replace("random[", "").replace("]", ""));
                        int digit = (int) Math.pow(10, t - 1);
                        int rs = new Random().nextInt(digit * 10);
                        if (rs < digit) {
                            rs += digit;
                        }
                        text = text.replace("{{" + middle + "}}", rs + "");
                    }
                    if (middle.matches("random\\[\\d-\\d\\]")) {
                        String t = middle.replace("random[", "").replace("]", "");
                        int[] size = Arrays.stream(t.split("-")).mapToInt(Integer::parseInt).toArray();
                        text = text.replace("{{" + middle + "}}", (int) (Math.random() * (size[1] - size[0] + 1)) + size[0] + "");
                    }
                    if (middle.matches("random\\[.+\\|.+\\]")) {
                        String t = middle.replace("random[", "").replace("]", "");
                        String[] size = t.split("\\|");
                        text = text.replace("{{" + middle + "}}", size[new Random().nextInt(size.length)]);
                    }
                }
                text = text + replaceTrans(child, globalParams);
            }
        }
        return text;
    }
}
