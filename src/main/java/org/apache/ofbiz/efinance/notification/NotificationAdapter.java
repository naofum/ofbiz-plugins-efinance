/*******************************************************************************
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
 *******************************************************************************/
package org.apache.ofbiz.efinance.notification;

import java.util.Locale;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;

/**
 * Adapter used to notify a customer that an application stage changed.
 *
 * <p>The default implementation stores a {@code CommunicationEvent}. A
 * deployment can replace it with an email or SMS adapter without changing the
 * stage-event logic.</p>
 */
public interface NotificationAdapter {

    /**
     * Send one stage-change notification.
     *
     * @return {@code true} when the notification was accepted by the adapter
     */
    boolean sendStageChange(Delegator delegator, String memberPartyId, String draftId,
            String stageEnumId, String stageDescription, Locale locale)
            throws GenericEntityException;
}
