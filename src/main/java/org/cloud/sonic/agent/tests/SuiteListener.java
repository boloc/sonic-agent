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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.util.CollectionUtils;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.xml.XmlTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test suite listener.
 */
public class SuiteListener implements ISuiteListener {

    public static ConcurrentHashMap<String, Boolean> runningTestsMap = new ConcurrentHashMap<>();

    @Override
    public void onStart(ISuite suite) {
        for (String runningTestsMapKey : getRunningTestsMapKeys(suite)) {
            runningTestsMap.put(runningTestsMapKey, true);
        }
    }

    @Override
    public void onFinish(ISuite suite) {
        for (String runningTestsMapKey : getRunningTestsMapKeys(suite)) {
            runningTestsMap.remove(runningTestsMapKey);
        }
    }

    private List<String> getRunningTestsMapKeys(ISuite suite) {
        List<String> keys = new ArrayList<>();
        if (suite.getXmlSuite() != null && !CollectionUtils.isEmpty(suite.getXmlSuite().getTests())) {
            for (XmlTest xmlTest : suite.getXmlSuite().getTests()) {
                addRunningTestsMapKeys(keys, xmlTest.getParameter("dataInfo"));
            }
        }
        if (keys.isEmpty()) {
            addRunningTestsMapKeys(keys, suite.getParameter("dataInfo"));
        }
        return keys;
    }

    private void addRunningTestsMapKeys(List<String> keys, String dataInfo) {
        if (dataInfo == null || dataInfo.length() == 0) {
            return;
        }
        JSONObject dataInfoJson = JSON.parseObject(dataInfo);
        String rid = dataInfoJson.getString("rid");
        JSONArray deviceArray = dataInfoJson.getJSONArray("device");
        if (rid == null || rid.length() == 0 || CollectionUtils.isEmpty(deviceArray)) {
            return;
        }
        for (int i = 0; i < deviceArray.size(); i++) {
            JSONObject jsonObject = deviceArray.getJSONObject(i);
            if (jsonObject == null) {
                continue;
            }
            String udId = jsonObject.getString("udId");
            if (udId == null || udId.length() == 0) {
                continue;
            }
            keys.add(rid + "-" + udId);
        }
    }
}
