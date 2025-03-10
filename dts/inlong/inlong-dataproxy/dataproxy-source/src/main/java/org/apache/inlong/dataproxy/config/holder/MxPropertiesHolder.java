/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.dataproxy.config.holder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * value is map
 */
public class MxPropertiesHolder extends PropertiesConfigHolder {

    private static final Logger LOG = LoggerFactory.getLogger(MxPropertiesHolder.class);
    private final Map<String, Map<String, String>> mxPropertiesMaps =
            new HashMap<String, Map<String, String>>();

    public MxPropertiesHolder(String fileName) {
        super(fileName);
    }

    /**
     * load m from file
     */
    @Override
    public boolean loadFromFileToHolder() {
        super.loadFromFileToHolder();
        try {
            for (Map.Entry<String, String> entry : getHolder().entrySet()) {
                mxPropertiesMaps.put(entry.getKey(), MAP_SPLITTER.split(entry.getValue()));
            }
        } catch (Exception e) {
            LOG.error("loadConfig error :", e);
        }
        return true;
    }

    public Map<String, Map<String, String>> getMxPropertiesMaps() {
        return mxPropertiesMaps;
    }
}
