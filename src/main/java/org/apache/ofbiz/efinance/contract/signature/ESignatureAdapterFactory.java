/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ofbiz.efinance.contract.signature;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilProperties;

public final class ESignatureAdapterFactory {

    private static final String MODULE = ESignatureAdapterFactory.class.getName();
    private static final String CONFIG_RESOURCE = "efinance";
    private static final String CONFIG_KEY = "esignature.adapter";

    private ESignatureAdapterFactory() {
    }

    public static ESignatureAdapter getAdapter() {
        String className = UtilProperties.getPropertyValue(CONFIG_RESOURCE, CONFIG_KEY,
                ClickWrapSignatureAdapter.class.getName());
        try {
            Class<?> clazz = Class.forName(className);
            return (ESignatureAdapter) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            Debug.logError(e, "Falling back to the default e-signature adapter", MODULE);
            return new ClickWrapSignatureAdapter();
        }
    }
}
