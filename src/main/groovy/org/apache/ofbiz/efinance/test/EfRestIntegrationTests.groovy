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
package org.apache.ofbiz.efinance.test

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import groovy.json.JsonSlurper

import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * REST integration tests for the public and authenticated efinance API.
 *
 * <p>The tests run against the test container started by the OFBiz test task.
 * The test server uses a self-signed certificate, so the test client trusts it
 * locally. No production code is affected.</p>
 */
class EfRestIntegrationTests extends OFBizTestCase {

    private static final String BASE_URL = System.getProperty('efinance.test.baseUrl', 'https://localhost:8443')

    EfRestIntegrationTests(String name) {
        super(name)
    }

    private Map request(String method, String path, String body, String token, String origin = null) {
        TrustManager trustManager = [
                checkClientTrusted: { X509Certificate[] chain, String authType -> },
                checkServerTrusted: { X509Certificate[] chain, String authType -> },
                getAcceptedIssuers: { new X509Certificate[0] }
        ] as X509TrustManager
        SSLContext sslContext = SSLContext.getInstance('TLS')
        sslContext.init(null, [trustManager] as TrustManager[], new SecureRandom())

        HttpsURLConnection connection = (HttpsURLConnection) new URL(BASE_URL + path).openConnection()
        connection.setSSLSocketFactory(sslContext.getSocketFactory())
        connection.setHostnameVerifier(new HostnameVerifier() {
            boolean verify(String hostname, SSLSession session) {
                return true
            }
        })
        connection.setConnectTimeout(10000)
        connection.setReadTimeout(30000)
        connection.setRequestMethod(method)
        connection.setRequestProperty('Accept', 'application/json')
        if (token != null) {
            connection.setRequestProperty('Authorization', 'Bearer ' + token)
        }
        if (origin != null) {
            connection.setRequestProperty('Origin', origin)
        }
        if (body != null) {
            connection.setDoOutput(true)
            connection.setRequestProperty('Content-Type', 'application/json')
            connection.getOutputStream().withWriter('UTF-8') { writer -> writer.write(body) }
        }

        int status = connection.responseCode
        Map<String, List<String>> responseHeaders = connection.getHeaderFields()
        InputStream stream = status >= 400 ? connection.errorStream : connection.inputStream
        String responseBody = stream == null ? '' : stream.getText('UTF-8')
        connection.disconnect()
        return [status: status, body: responseBody, headers: responseHeaders]
    }

    private boolean hasHeader(Map<String, List<String>> headers, String name) {
        return headers.any { key, value -> key != null && key.equalsIgnoreCase(name) && value != null }
    }

    private Map json(Map response) {
        return new JsonSlurper().parseText(response.body as String) as Map
    }

    void testPublicProductsAndLoginFlow() {
        Map products = request('GET', '/rest/ef/products', null, null)
        assert products.status == 200
        assert json(products).data.products.size() >= 1

        Map login = request('POST', '/rest/ef/auth/login',
                '{"userLoginId":"test-ef-customer","password":"ofbiz","deviceId":"rest-test","ipAddress":"127.0.0.1"}',
                null)
        assert login.status == 200
        Map loginData = json(login).data as Map
        assert loginData.accessToken

        Map profile = request('GET', '/rest/ef/me', null, loginData.accessToken as String)
        assert profile.status == 200
        assert json(profile).data.member.partyId == 'TEST_EF_CUST'

        Map directDebit = request('GET', '/rest/ef/me/direct-debit', null,
                loginData.accessToken as String)
        assert directDebit.status == 200
        assert json(directDebit).data.mandates != null

        Map invalid = request('GET', '/rest/ef/me', null, 'invalid-token')
        assert invalid.status == 401
    }

    void testErrorChainAndCorsHeaders() {
        Map noToken = request('GET', '/rest/ef/me', null, null)
        assert noToken.status == 401
        assert json(noToken).statusCode == 401

        Map noTokenDirectDebit = request('GET', '/rest/ef/me/direct-debit', null, null)
        assert noTokenDirectDebit.status == 401

        Map noTokenPaymentInstructions = request('GET',
                '/rest/ef/me/payment-instructions', null, null)
        assert noTokenPaymentInstructions.status == 401

        Map malformed = request('POST', '/rest/ef/auth/login', '{', null)
        assert malformed.status in [400, 422]

        Map missingFields = request('POST', '/rest/ef/auth/login', '{}', null)
        assert missingFields.status in [400, 422]

        Map notFound = request('GET', '/rest/ef/no-such-endpoint', null, null)
        assert notFound.status in [404, 405]

        Map cors = request('GET', '/rest/ef/products', null, null, 'https://portal.example.com')
        assert cors.status == 200
        assert hasHeader(cors.headers, 'Access-Control-Allow-Credentials')
        assert hasHeader(cors.headers, 'Access-Control-Allow-Headers')
        assert hasHeader(cors.headers, 'Access-Control-Allow-Methods')
    }
}
