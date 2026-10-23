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
'use strict';

const API_BASE = '/rest';
const DEVICE_ID = 'efinance-dealer-sample';

const state = {
    token: sessionStorage.getItem('ef_dealer_access_token'),
    dealers: [],
    dealerPartyId: null
};

function el(id) {
    return document.getElementById(id);
}

function escapeHtml(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function showFlash(message, type) {
    const box = el('flash');
    box.textContent = message;
    box.className = 'flash ' + (type || 'ok');
    box.hidden = false;
    window.clearTimeout(showFlash.timer);
    showFlash.timer = window.setTimeout(function () {
        box.hidden = true;
    }, 8000);
}

async function api(method, path, body) {
    const headers = {'Accept': 'application/json'};
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    if (state.token) {
        headers['Authorization'] = 'Bearer ' + state.token;
    }
    const response = await fetch(API_BASE + path, {
        method: method,
        headers: headers,
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    let payload = null;
    try {
        payload = await response.json();
    } catch (ignored) {
        payload = null;
    }
    if (!response.ok) {
        const message = payload && (payload.errorMessage || payload.error || payload.message);
        throw new Error(message || ('HTTP ' + response.status));
    }
    return payload ? payload.data : null;
}

function dealerId() {
    return el('dealerSelect').value;
}

function renderRows(tbodyId, rows, columns, emptyMessage) {
    const tbody = el(tbodyId);
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="' + columns.length + '" class="muted">' +
            escapeHtml(emptyMessage) + '</td></tr>';
        return;
    }
    tbody.innerHTML = rows.map(function (row) {
        return '<tr>' + columns.map(function (column) {
            return '<td>' + escapeHtml(column.value(row)) + '</td>';
        }).join('') + '</tr>';
    }).join('');
}

function renderDealer() {
    const dealer = state.dealers.find(function (item) {
        return item.dealerPartyId === dealerId();
    });
    if (!dealer) {
        el('dealerRoles').textContent = '';
        return;
    }
    const roles = [];
    if (dealer.roleApply === 'Y') roles.push('申込');
    if (dealer.roleView === 'Y') roles.push('照会');
    if (dealer.rolePayment === 'Y') roles.push('支払');
    if (dealer.roleAdmin === 'Y') roles.push('管理');
    el('dealerRoles').textContent = '権限: ' + roles.join(' / ');
}

async function loadProfile() {
    const data = await api('GET', '/ef/dealer/profile');
    state.dealers = data.dealers || [];
    const select = el('dealerSelect');
    select.innerHTML = state.dealers.map(function (dealer) {
        return '<option value="' + escapeHtml(dealer.dealerPartyId) + '">' +
            escapeHtml(dealer.dealerName + ' (' + dealer.dealerCode + ')') + '</option>';
    }).join('');
    if (state.dealers.length === 0) {
        throw new Error('販売店の担当者として登録されていません。');
    }
    renderDealer();
}

async function loadCustomers() {
    const data = await api('GET', '/ef/dealer/' + encodeURIComponent(dealerId()) + '/customers');
    renderRows('customersBody', data.customers || [], [
        {value: function (row) { return row.customerName || row.customerPartyId; }},
        {value: function (row) { return row.statusId; }},
        {value: function (row) { return row.consentDate || ''; }}
    ], '同意済み顧客はありません。');
}

async function loadDrafts() {
    const data = await api('GET', '/ef/dealer/' + encodeURIComponent(dealerId()) + '/drafts');
    renderRows('draftsBody', data.drafts || [], [
        {value: function (row) { return row.draftId; }},
        {value: function (row) { return row.customerName || row.customerPartyId; }},
        {value: function (row) { return row.productId; }},
        {value: function (row) { return row.applicationTypeId; }},
        {value: function (row) { return (row.currentStep || 1) + '/' + (row.totalSteps || 3); }}
    ], '代理下書きはありません。');
}

async function loadApplications() {
    const data = await api('GET', '/ef/dealer/' + encodeURIComponent(dealerId()) + '/applications');
    renderRows('applicationsBody', data.applications || [], [
        {value: function (row) { return row.financeApplicationId; }},
        {value: function (row) { return row.customerName || row.customerPartyId; }},
        {value: function (row) { return row.financeApplicationTypeId; }},
        {value: function (row) { return row.linkedDate || ''; }}
    ], '申込はありません。');
}

async function loadContracts() {
    const data = await api('GET', '/ef/dealer/' + encodeURIComponent(dealerId()) + '/contracts');
    renderRows('contractsBody', data.contracts || [], [
        {value: function (row) { return row.agreementId; }},
        {value: function (row) { return row.customerPartyId; }},
        {value: function (row) { return row.agreementTypeId; }},
        {value: function (row) { return row.statusId; }},
        {value: function (row) { return row.principalAmount; }}
    ], '契約はありません。');
}

async function loadDealerData() {
    renderDealer();
    await Promise.all([loadCustomers(), loadDrafts(), loadApplications(), loadContracts()]);
}

async function login(event) {
    event.preventDefault();
    try {
        const data = await api('POST', '/ef/auth/login', {
            userLoginId: el('userLoginId').value,
            password: el('password').value,
            deviceId: DEVICE_ID,
            ipAddress: '127.0.0.1'
        });
        if (data.mfaRequired) {
            throw new Error('MFA が必要です。顧客ポータルの MFA 完了後に利用してください。');
        }
        state.token = data.accessToken;
        sessionStorage.setItem('ef_dealer_access_token', state.token);
        el('loginSection').classList.add('hidden');
        el('appSection').classList.remove('hidden');
        el('logoutButton').classList.remove('hidden');
        el('sessionLabel').textContent = '販売店セッション';
        await loadProfile();
        await loadDealerData();
        showFlash('ログインしました。', 'ok');
    } catch (error) {
        showFlash(error.message, 'error');
    }
}

async function createCustomer(event) {
    event.preventDefault();
    try {
        await api('POST', '/ef/dealer/' + encodeURIComponent(dealerId()) + '/customers', {
            customerPartyId: el('customerPartyId').value,
            consentReference: el('consentReference').value
        });
        showFlash('顧客同意を登録しました。', 'ok');
        await loadCustomers();
    } catch (error) {
        showFlash(error.message, 'error');
    }
}

async function createDraft(event) {
    event.preventDefault();
    try {
        const data = await api('POST', '/ef/dealer/' + encodeURIComponent(dealerId()) + '/drafts', {
            customerPartyId: el('draftCustomerPartyId').value,
            productId: el('draftProductId').value,
            applicationTypeId: el('draftType').value
        });
        showFlash('代理下書きを作成しました: ' + data.draftId, 'ok');
        await loadDrafts();
    } catch (error) {
        showFlash(error.message, 'error');
    }
}

function logout() {
    state.token = null;
    state.dealers = [];
    sessionStorage.removeItem('ef_dealer_access_token');
    el('appSection').classList.add('hidden');
    el('logoutButton').classList.add('hidden');
    el('loginSection').classList.remove('hidden');
    el('sessionLabel').textContent = '';
}

async function init() {
    el('loginForm').addEventListener('submit', login);
    el('customerForm').addEventListener('submit', createCustomer);
    el('draftForm').addEventListener('submit', createDraft);
    el('logoutButton').addEventListener('click', logout);
    el('dealerSelect').addEventListener('change', function () {
        loadDealerData().catch(function (error) { showFlash(error.message, 'error'); });
    });
    if (state.token) {
        try {
            el('loginSection').classList.add('hidden');
            el('appSection').classList.remove('hidden');
            el('logoutButton').classList.remove('hidden');
            el('sessionLabel').textContent = '販売店セッション';
            await loadProfile();
            await loadDealerData();
        } catch (error) {
            logout();
            showFlash(error.message, 'error');
        }
    }
}

document.addEventListener('DOMContentLoaded', init);
