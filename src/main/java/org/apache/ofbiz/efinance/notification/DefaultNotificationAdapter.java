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

import java.sql.Timestamp;
import java.util.Locale;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Default notification adapter.
 *
 * <p>It keeps an in-application message in {@code CommunicationEvent}. Actual
 * email and SMS delivery are deployment concerns and can be replaced through
 * the {@code notification.adapter} property.</p>
 */
public class DefaultNotificationAdapter implements NotificationAdapter {

    private static final String MODULE = DefaultNotificationAdapter.class.getName();
    private static final String RESOURCE = "EfinanceUiLabels";
    private static final String NOTIFICATION_TYPE = "EFNOTIF_INFO";

    @Override
    public boolean sendStageChange(Delegator delegator, String memberPartyId, String draftId,
            String stageEnumId, String stageDescription, Locale locale) throws GenericEntityException {
        if (UtilValidate.isEmpty(memberPartyId)) {
            return false;
        }
        GenericValue setting = EntityQuery.use(delegator).from("EfNotificationSetting")
                .where("memberPartyId", memberPartyId, "notificationTypeEnumId", NOTIFICATION_TYPE)
                .queryOne();
        if (setting != null && !"Y".equals(setting.getString("emailEnabled"))) {
            return false;
        }

        Timestamp now = UtilDateTime.nowTimestamp();
        String subject = UtilProperties.getMessage(RESOURCE, "EfinanceStageNotificationSubject", locale);
        String content = UtilProperties.getMessage(RESOURCE, "EfinanceStageNotificationBody",
                new Object[] {UtilValidate.isEmpty(stageDescription) ? stageEnumId : stageDescription},
                locale);

        GenericValue event = delegator.makeValue("CommunicationEvent");
        event.set("communicationEventId", delegator.getNextSeqId("CommunicationEvent"));
        event.set("communicationEventTypeId", "WEB_SITE_COMMUNICATI");
        event.set("statusId", "COM_COMPLETE");
        event.set("partyIdTo", memberPartyId);
        event.set("roleTypeIdTo", "_NA_");
        event.set("entryDate", now);
        event.set("datetimeStarted", now);
        event.set("subject", subject);
        event.set("content", content);
        event.set("contentMimeTypeId", "text/plain");
        event.set("note", "draftId=" + draftId + ", stage=" + stageEnumId);
        delegator.create(event);
        Debug.logInfo("Stage notification stored for member " + memberPartyId + ", draft " + draftId
                + ", stage " + stageEnumId, MODULE);
        return true;
    }
}
