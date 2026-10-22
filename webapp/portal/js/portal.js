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
const REQUIRED_CONSENTS = ['EFCONS_TERMS', 'EFCONS_PRIVACY', 'EFCONS_CREDIT'];
const DEVICE_ID = 'efinance-portal-sample';

const state = {
    token: sessionStorage.getItem('ef_access_token'),
    refreshToken: sessionStorage.getItem('ef_refresh_token'),
    member: null,
    products: [],
    draftId: sessionStorage.getItem('ef_draft_id'),
    kycCheckId: sessionStorage.getItem('ef_kyc_check_id'),
    signingSessionId: sessionStorage.getItem('ef_signing_session_id'),
    offer: null,
    mfaTempToken: null
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

function showView(name) {
    if (!name) {
        name = 'home';
    }
    if (name === 'mypage' && !state.token) {
        showFlash('マイページの利用にはログインが必要です。', 'warn');
        name = 'auth';
    }
    document.querySelectorAll('.view').forEach(function (view) {
        view.hidden = view.id !== 'view-' + name;
    });
    document.querySelectorAll('#mainNav [data-view]').forEach(function (button) {
        button.classList.toggle('active', button.getAttribute('data-view') === name);
    });
    if (window.location.hash !== '#' + name) {
        window.location.hash = name;
    }
}

function setAuthState() {
    const loggedIn = Boolean(state.token);
    el('navAuth').textContent = loggedIn ? 'アカウント' : 'ログイン';
    el('logoutBtn').hidden = !loggedIn;
    document.querySelectorAll('[data-auth-only]').forEach(function (node) {
        node.hidden = !loggedIn;
    });
}

function clearAuth() {
    state.token = null;
    state.refreshToken = null;
    state.member = null;
    sessionStorage.removeItem('ef_access_token');
    sessionStorage.removeItem('ef_refresh_token');
    setAuthState();
}

function saveTokens(data) {
    if (data.accessToken) {
        state.token = data.accessToken;
        sessionStorage.setItem('ef_access_token', data.accessToken);
    }
    if (data.refreshToken) {
        state.refreshToken = data.refreshToken;
        sessionStorage.setItem('ef_refresh_token', data.refreshToken);
    }
    setAuthState();
}

function appendQueryParams(path, params) {
    if (!params) {
        return path;
    }
    const parts = [];
    Object.keys(params).forEach(function (name) {
        const value = params[name];
        if (value === undefined || value === null) {
            return;
        }
        if (Array.isArray(value)) {
            value.forEach(function (item) {
                parts.push(encodeURIComponent(name) + '=' + encodeURIComponent(item));
            });
        } else {
            parts.push(encodeURIComponent(name) + '=' + encodeURIComponent(value));
        }
    });
    if (!parts.length) {
        return path;
    }
    return path + (path.indexOf('?') === -1 ? '?' : '&') + parts.join('&');
}

async function api(path, options) {
    options = options || {};
    const method = options.method || 'GET';
    const auth = options.auth !== false;
    if (auth && !state.token) {
        throw new Error('ログインしてください。');
    }
    let url = API_BASE + path;
    if (method === 'GET') {
        url = appendQueryParams(url, options.params || {});
    }
    const headers = {Accept: 'application/json'};
    if (auth && state.token) {
        headers.Authorization = 'Bearer ' + state.token;
    }
    let body = null;
    if (options.form) {
        body = options.form;
    } else if (options.body !== undefined && options.body !== null) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.body);
    }
    const response = await fetch(url, {method: method, headers: headers, body: body});
    const text = await response.text();
    let payload = {};
    if (text) {
        try {
            payload = JSON.parse(text);
        } catch (e) {
            payload = {errorMessage: text};
        }
    }
    if (response.status === 401) {
        clearAuth();
    }
    if (!response.ok || payload.errorMessage) {
        const message = payload.errorMessage || payload.errorDescription || payload.errorType || ('HTTP ' + response.status);
        throw new Error(message);
    }
    if (payload && Object.prototype.hasOwnProperty.call(payload, 'data')) {
        return payload.data;
    }
    return payload;
}

function get(path, params) {
    return api(path, {method: 'GET', params: params || {}});
}

function post(path, body) {
    return api(path, {method: 'POST', body: body});
}

function put(path, body) {
    return api(path, {method: 'PUT', body: body});
}

function productById(productId) {
    return state.products.find(function (p) {
        return p.productId === productId;
    });
}

function productCard(product, includeApply) {
    const name = product.productName || product.internalName || product.productId;
    const typeLabel = product.isLease ? 'リース' : 'ローン';
    const min = product.minPrincipalAmount !== null && product.minPrincipalAmount !== undefined
        ? Number(product.minPrincipalAmount).toLocaleString('ja-JP') + ' 円' : '—';
    const max = product.maxPrincipalAmount !== null && product.maxPrincipalAmount !== undefined
        ? Number(product.maxPrincipalAmount).toLocaleString('ja-JP') + ' 円' : '—';
    const term = (product.minTermMonths || product.maxTermMonths)
        ? (product.minTermMonths || '—') + '〜' + (product.maxTermMonths || '—') + ' ヶ月' : '—';
    let buttons = '<button type="button" class="btn secondary" data-sim-product="' + escapeHtml(product.productId) + '">シミュレーション</button>';
    if (includeApply) {
        buttons += ' <button type="button" class="btn" data-apply-product="' + escapeHtml(product.productId) + '">申込</button>';
    }
    return '<article class="card">'
        + '<h3>' + escapeHtml(name) + '</h3>'
        + '<p class="meta">' + escapeHtml(product.productId) + ' ・ ' + typeLabel + '</p>'
        + '<dl class="meta">'
        + '<div>借入/取得金額: ' + min + ' 〜 ' + max + '</div>'
        + '<div>期間: ' + term + '</div>'
        + '</dl>'
        + '<div class="actions">' + buttons + '</div>'
        + '</article>';
}

function renderProducts() {
    const home = el('homeProducts');
    const list = el('productList');
    if (!state.products.length) {
        home.innerHTML = '<p class="empty">公開中の商品がありません。</p>';
        list.innerHTML = '<p class="empty">公開中の商品がありません。</p>';
        return;
    }
    home.innerHTML = state.products.slice(0, 2).map(function (p) {
        return productCard(p, true);
    }).join('');
    list.innerHTML = state.products.map(function (p) {
        return productCard(p, true);
    }).join('');
    populateProductSelects();
}

function populateProductSelects() {
    const selects = [el('simProduct'), el('applyProduct')];
    selects.forEach(function (select) {
        if (!select) {
            return;
        }
        const current = select.value;
        select.innerHTML = '<option value="">選択してください</option>' + state.products.map(function (p) {
            const name = p.productName || p.internalName || p.productId;
            return '<option value="' + escapeHtml(p.productId) + '">' + escapeHtml(name) + ' (' + (p.isLease ? 'リース' : 'ローン') + ')</option>';
        }).join('');
        if (current) {
            select.value = current;
        }
    });
}

async function loadProducts() {
    const data = await get('/ef/products');
    state.products = data.products || [];
    renderProducts();
}

function updateProductLabels() {
    const simProduct = productById(el('simProduct').value);
    const applyProduct = productById(el('applyProduct').value);
    el('simAmountLabel').textContent = simProduct && simProduct.isLease ? '取得見積額（円）' : '借入希望額（円）';
    el('applyAmountLabel').textContent = applyProduct && applyProduct.isLease ? '取得見積額（円）' : '借入希望額（円）';
    const lease = applyProduct && applyProduct.isLease;
    el('loanApplicantFields').hidden = lease;
    el('leaseApplicantFields').hidden = !lease;
    el('applyRepayment').disabled = Boolean(lease);
}

async function simulate(event) {
    event.preventDefault();
    const product = productById(el('simProduct').value);
    if (!product) {
        showFlash('商品を選択してください。', 'warn');
        return;
    }
    const amount = Number(el('simAmount').value);
    const term = Number(el('simTerm').value);
    const rate = el('simRate').value ? Number(el('simRate').value) : null;
    const body = {productId: product.productId, termMonths: term};
    if (product.isLease) {
        body.acquisitionCost = amount;
        if (rate !== null) {
            body.taxRate = rate;
        }
    } else {
        body.principalAmount = amount;
        body.repaymentMethod = 'EQUAL_PAYMENT';
        if (rate !== null) {
            body.annualInterestRate = rate;
        }
    }
    try {
        const result = await api(product.isLease ? '/ef/simulate/lease' : '/ef/simulate/loan', {
            method: 'POST',
            auth: false,
            body: body
        });
        const lines = [];
        if (product.isLease) {
            lines.push('毎月のリース料: ' + Number(result.monthlyLeaseAmount || 0).toLocaleString('ja-JP') + ' 円');
            lines.push('総リース料: ' + Number(result.totalLeaseAmount || 0).toLocaleString('ja-JP') + ' 円');
            lines.push('消費税合計: ' + Number(result.totalTax || 0).toLocaleString('ja-JP') + ' 円');
        } else {
            lines.push('毎月返済額: ' + Number(result.monthlyPayment || 0).toLocaleString('ja-JP') + ' 円');
            lines.push('総返済額: ' + Number(result.totalPayment || 0).toLocaleString('ja-JP') + ' 円');
            lines.push('利息合計: ' + Number(result.totalInterest || 0).toLocaleString('ja-JP') + ' 円');
            if (result.effectiveAnnualRate !== null && result.effectiveAnnualRate !== undefined) {
                lines.push('実質年率: ' + result.effectiveAnnualRate + ' %');
            }
        }
        const box = el('simulateResult');
        box.textContent = lines.join('\n');
        box.hidden = false;
    } catch (e) {
        showFlash('シミュレーションに失敗しました: ' + e.message, 'error');
    }
}

async function login(event) {
    event.preventDefault();
    try {
        const data = await api('/ef/auth/login', {
            method: 'POST',
            auth: false,
            body: {
                userLoginId: el('loginId').value,
                password: el('loginPassword').value,
                deviceId: DEVICE_ID,
                ipAddress: '127.0.0.1'
            }
        });
        if (data.mfaRequired) {
            state.mfaTempToken = data.mfaTempToken;
            el('mfaBox').hidden = false;
            showFlash('多要素認証が必要です。ワンタイムパスワードを入力してください。', 'warn');
            return;
        }
        saveTokens(data);
        el('mfaBox').hidden = true;
        showFlash('ログインしました。', 'ok');
        await loadProfile();
        await loadMyPage();
        showView('mypage');
    } catch (e) {
        showFlash('ログインに失敗しました: ' + e.message, 'error');
    }
}

async function verifyMfa() {
    try {
        const data = await api('/ef/auth/verify-mfa', {
            method: 'POST',
            auth: false,
            body: {
                tempToken: state.mfaTempToken,
                otpCode: el('mfaCode').value,
                deviceId: DEVICE_ID,
                ipAddress: '127.0.0.1'
            }
        });
        saveTokens(data);
        el('mfaBox').hidden = true;
        showFlash('ログインしました。', 'ok');
        await loadProfile();
        await loadMyPage();
        showView('mypage');
    } catch (e) {
        showFlash('認証に失敗しました: ' + e.message, 'error');
    }
}

async function register(event) {
    event.preventDefault();
    try {
        const data = await api('/ef/auth/register', {
            method: 'POST',
            auth: false,
            body: {
                email: el('registerEmail').value,
                password: el('registerPassword').value,
                firstName: el('registerFirstName').value,
                lastName: el('registerLastName').value,
                phone: el('registerPhone').value,
                memberType: 'PERSONAL'
            }
        });
        el('loginId').value = data.userLoginId || el('registerEmail').value;
        showFlash('登録を受け付けました。メール確認後にログインできます。', 'ok');
    } catch (e) {
        showFlash('登録に失敗しました: ' + e.message, 'error');
    }
}

async function verifyEmail() {
    try {
        await api('/ef/auth/verify-email', {
            method: 'POST',
            auth: false,
            body: {token: el('verifyToken').value}
        });
        showFlash('メールアドレスを確認しました。', 'ok');
    } catch (e) {
        showFlash('メール確認に失敗しました: ' + e.message, 'error');
    }
}

async function logout() {
    try {
        if (state.token) {
            await post('/ef/auth/logout', {
                accessToken: state.token,
                refreshToken: state.refreshToken
            });
        }
    } catch (e) {
        // The local session is cleared even if the server call fails.
    } finally {
        clearAuth();
        showFlash('ログアウトしました。', 'ok');
        showView('home');
    }
}

async function loadProfile() {
    if (!state.token) {
        return;
    }
    try {
        const data = await get('/ef/me');
        state.member = data.member || data.profile || data;
        const profile = state.member || {};
        el('profileSummary').textContent = 'ログイン中: '
            + (profile.lastName || '') + ' ' + (profile.firstName || '')
            + (profile.email ? ' / ' + profile.email : '')
            + (profile.partyId ? ' (' + profile.partyId + ')' : '');
    } catch (e) {
        showFlash('会員情報の取得に失敗しました: ' + e.message, 'error');
    }
}

function renderRows(containerId, rows, columns) {
    const container = el(containerId);
    if (!rows || !rows.length) {
        container.innerHTML = '<p class="empty">データがありません。</p>';
        return;
    }
    const header = '<tr>' + columns.map(function (column) {
        return '<th>' + escapeHtml(column.label) + '</th>';
    }).join('') + '</tr>';
    const body = rows.map(function (row) {
        return '<tr>' + columns.map(function (column) {
            const value = column.value(row);
            return '<td>' + escapeHtml(value === null || value === undefined ? '' : value) + '</td>';
        }).join('') + '</tr>';
    }).join('');
    container.innerHTML = '<table>' + header + body + '</table>';
}

async function loadMyPage() {
    if (!state.token) {
        return;
    }
    const tasks = [
        get('/ef/me/applications'),
        get('/ef/me/contracts'),
        get('/ef/me/messages', {}),
        get('/ef/support/cases', {}),
        get('/ef/me/notification-settings')
    ];
    const results = await Promise.allSettled(tasks);
    const applications = results[0].status === 'fulfilled' ? (results[0].value.applications || []) : [];
    const contracts = results[1].status === 'fulfilled' ? (results[1].value.contracts || []) : [];
    const messages = results[2].status === 'fulfilled' ? (results[2].value.messages || []) : [];
    const supportCases = results[3].status === 'fulfilled' ? (results[3].value.supportCases || []) : [];
    const notifications = results[4].status === 'fulfilled' ? (results[4].value.settings || []) : [];

    renderRows('myApplications', applications, [
        {label: '申込ID', value: function (r) { return r.financeApplicationId; }},
        {label: '種別', value: function (r) { return r.financeApplicationTypeId; }},
        {label: '商品', value: function (r) { return r.productId; }},
        {label: '状態', value: function (r) { return r.statusId; }},
        {label: '申込日', value: function (r) { return r.applicationDate; }}
    ]);
    renderRows('myContracts', contracts, [
        {label: '契約ID', value: function (r) { return r.agreementId; }},
        {label: '種別', value: function (r) { return r.agreementTypeId; }},
        {label: '状態', value: function (r) { return r.statusId; }},
        {label: '金額', value: function (r) { return r.principalAmount || r.acquisitionCost; }},
        {label: '契約日', value: function (r) { return r.agreementDate; }}
    ]);
    renderRows('myMessages', messages, [
        {label: '件名', value: function (r) { return r.subject; }},
        {label: '内容', value: function (r) { return r.content; }},
        {label: '日時', value: function (r) { return r.entryDate; }}
    ]);
    renderRows('mySupportCases', supportCases, [
        {label: 'ケースID', value: function (r) { return r.supportCaseId; }},
        {label: '件名', value: function (r) { return r.subject; }},
        {label: '状態', value: function (r) { return r.statusId; }},
        {label: '更新日', value: function (r) { return r.lastUpdatedDate || r.createdDate; }}
    ]);
    renderRows('myNotifications', notifications, [
        {label: '通知種別', value: function (r) { return r.description || r.notificationTypeEnumId; }},
        {label: 'メール', value: function (r) { return r.emailEnabled; }},
        {label: 'SMS', value: function (r) { return r.smsEnabled; }},
        {label: 'プッシュ', value: function (r) { return r.pushEnabled; }}
    ]);
}

async function acceptRequiredConsents() {
    const data = await get('/ef/consent-documents');
    const documents = data.documents || [];
    for (const documentType of REQUIRED_CONSENTS) {
        const document = documents.find(function (d) {
            return d.documentTypeEnumId === documentType;
        });
        if (!document) {
            throw new Error('必須同意文書が設定されていません: ' + documentType);
        }
        await post('/ef/consents', {documentId: document.documentId});
    }
}

async function saveDraft(draftId, step, stepData) {
    const result = await put('/ef/applications/drafts/' + encodeURIComponent(draftId), {
        step: step,
        stepData: stepData
    });
    if (result && result.stepValid === false) {
        throw new Error('入力内容を確認してください: ' + (result.validationErrors || []).join(', '));
    }
}

async function submitApplication(event) {
    event.preventDefault();
    if (!state.token) {
        showView('auth');
        showFlash('申込にはログインが必要です。', 'warn');
        return;
    }
    if (!el('applyConsentCheck').checked) {
        showFlash('同意にチェックを入れてください。', 'warn');
        return;
    }
    const product = productById(el('applyProduct').value);
    if (!product) {
        showFlash('商品を選択してください。', 'warn');
        return;
    }
    const amount = Number(el('applyAmount').value);
    const term = Number(el('applyTerm').value);
    const applicationTypeId = product.isLease ? 'LEASE' : 'LOAN';
    try {
        const created = await post('/ef/applications/drafts', {
            productId: product.productId,
            applicationTypeId: applicationTypeId
        });
        const draftId = created.draftId;
        state.draftId = draftId;
        sessionStorage.setItem('ef_draft_id', draftId);
        el('contractDraftId').value = draftId;

        if (product.isLease) {
            await saveDraft(draftId, 1, {termMonths: term});
            await saveDraft(draftId, 2, {
                assetName: el('applyAssetName').value,
                estimatedAcquisitionCost: amount
            });
            await saveDraft(draftId, 3, {
                companyName: el('applyCompanyName').value,
                representativeName: el('applyRepresentative').value
            });
            await saveDraft(draftId, 4, {confirmTerms: 'Y', confirmPrivacy: 'Y'});
        } else {
            await saveDraft(draftId, 1, {
                principalAmount: amount,
                termMonths: term,
                repaymentMethod: el('applyRepayment').value
            });
            await saveDraft(draftId, 2, {
                firstName: el('applyFirstName').value,
                lastName: el('applyLastName').value,
                birthDate: el('applyBirthDate').value,
                postalCode: el('applyPostal').value,
                prefecture: el('applyPrefecture').value,
                city: el('applyCity').value,
                address1: el('applyAddress').value,
                employerName: el('applyEmployer').value,
                annualIncome: el('applyIncome').value,
                residenceType: el('applyResidence').value
            });
            await saveDraft(draftId, 3, {confirmTerms: 'Y', confirmPrivacy: 'Y'});
        }

        await acceptRequiredConsents();
        const result = await post('/ef/applications/drafts/' + encodeURIComponent(draftId) + '/submit', {
            ipAddress: '127.0.0.1',
            deviceId: DEVICE_ID
        });
        const box = el('applyResult');
        box.textContent = '申込を送信しました。\n下書きID: ' + draftId
            + '\n金融申込ID: ' + (result.financeApplicationId || '')
            + '\n不正検知結果: ' + (result.fraudResult || '');
        box.hidden = false;
        showFlash('申込を送信しました。', 'ok');
        await loadMyPage();
    } catch (e) {
        showFlash('申込に失敗しました: ' + e.message, 'error');
    }
}

function kycCheckId() {
    return el('kycCheckId').value || state.kycCheckId;
}

async function startKyc() {
    try {
        const data = await post('/ef/kyc', {
            kycMethodEnumId: el('kycMethod').value,
            draftId: state.draftId || undefined
        });
        state.kycCheckId = data.kycCheckId;
        sessionStorage.setItem('ef_kyc_check_id', state.kycCheckId);
        el('kycCheckId').value = state.kycCheckId;
        showFlash('本人確認を開始しました。', 'ok');
        await refreshKyc();
    } catch (e) {
        showFlash('本人確認の開始に失敗しました: ' + e.message, 'error');
    }
}

async function uploadKycImage() {
    const checkId = kycCheckId();
    if (!checkId) {
        showFlash('先に本人確認を開始してください。', 'warn');
        return;
    }
    const file = el('kycFile').files[0];
    if (!file) {
        showFlash('画像ファイルを選択してください。', 'warn');
        return;
    }
    const form = new FormData();
    form.append('imageTypeEnumId', el('kycImageType').value);
    form.append('fileName', file.name);
    form.append('contentType', file.type || 'application/octet-stream');
    form.append('uploadedFile', file);
    try {
        await api('/ef/kyc/' + encodeURIComponent(checkId) + '/images', {method: 'POST', form: form});
        showFlash('画像をアップロードしました。', 'ok');
        await refreshKyc();
    } catch (e) {
        showFlash('画像アップロードに失敗しました: ' + e.message, 'error');
    }
}

async function submitKyc() {
    const checkId = kycCheckId();
    if (!checkId) {
        showFlash('先に本人確認を開始してください。', 'warn');
        return;
    }
    try {
        await post('/ef/kyc/' + encodeURIComponent(checkId) + '/submit', {});
        showFlash('本人確認を審査へ提出しました。', 'ok');
        await refreshKyc();
    } catch (e) {
        showFlash('本人確認の提出に失敗しました: ' + e.message, 'error');
    }
}

async function refreshKyc() {
    const checkId = kycCheckId();
    if (!checkId) {
        showFlash('本人確認IDがありません。', 'warn');
        return;
    }
    try {
        const data = await get('/ef/kyc/' + encodeURIComponent(checkId));
        const box = el('kycResult');
        box.textContent = JSON.stringify(data, null, 2);
        box.hidden = false;
    } catch (e) {
        showFlash('本人確認の状態取得に失敗しました: ' + e.message, 'error');
    }
}

function contractDraftId() {
    return el('contractDraftId').value || state.draftId;
}

async function loadOffer() {
    const draftId = contractDraftId();
    if (!draftId) {
        showFlash('申込下書きIDを入力してください。', 'warn');
        return;
    }
    try {
        const data = await get('/ef/applications/drafts/' + encodeURIComponent(draftId) + '/offer');
        state.offer = data;
        el('contractResult').textContent = JSON.stringify(data, null, 2);
        el('contractResult').hidden = false;
    } catch (e) {
        showFlash('見積の取得に失敗しました: ' + e.message, 'error');
    }
}

async function acceptOffer() {
    if (!state.offer || !state.offer.quoteId) {
        showFlash('先に見積を取得してください。', 'warn');
        return;
    }
    try {
        await post('/ef/applications/drafts/' + encodeURIComponent(contractDraftId()) + '/offer/accept', {
            quoteId: state.offer.quoteId,
            quoteTypeId: state.offer.quoteTypeId
        });
        showFlash('見積を受理しました。', 'ok');
    } catch (e) {
        showFlash('見積の受理に失敗しました: ' + e.message, 'error');
    }
}

async function startSigning() {
    if (!state.offer || !state.offer.quoteId) {
        showFlash('先に見積を取得してください。', 'warn');
        return;
    }
    try {
        const data = await post('/ef/applications/drafts/' + encodeURIComponent(contractDraftId()) + '/signing', {
            quoteId: state.offer.quoteId,
            quoteTypeId: state.offer.quoteTypeId
        });
        state.signingSessionId = data.signingSessionId;
        sessionStorage.setItem('ef_signing_session_id', state.signingSessionId);
        el('signingSessionId').value = state.signingSessionId;
        el('contractResult').textContent = JSON.stringify(data, null, 2);
        el('contractResult').hidden = false;
        showFlash('署名セッションを開始しました。', 'ok');
    } catch (e) {
        showFlash('署名の開始に失敗しました: ' + e.message, 'error');
    }
}

async function completeSigning() {
    const sessionId = el('signingSessionId').value || state.signingSessionId;
    if (!sessionId) {
        showFlash('署名セッションIDがありません。', 'warn');
        return;
    }
    const password = el('signingReauthPassword').value;
    if (!password) {
        showFlash('再認証パスワードを入力してください。', 'warn');
        return;
    }
    try {
        const reauth = await post('/ef/auth/reauth', {
            password: password,
            deviceId: DEVICE_ID,
            ipAddress: '127.0.0.1'
        });
        const data = await post('/ef/signing/' + encodeURIComponent(sessionId) + '/complete', {
            confirmContent: true,
            ipAddress: '127.0.0.1',
            deviceId: DEVICE_ID,
            reauthToken: reauth.reauthToken
        });
        el('contractResult').textContent = JSON.stringify(data, null, 2);
        el('contractResult').hidden = false;
        showFlash('契約を締結しました。', 'ok');
        await loadMyPage();
    } catch (e) {
        showFlash('契約締結に失敗しました: ' + e.message, 'error');
    }
}

async function loadDocuments() {
    const draftId = contractDraftId();
    if (!draftId) {
        showFlash('申込下書きIDを入力してください。', 'warn');
        return;
    }
    try {
        const data = await get('/ef/applications/drafts/' + encodeURIComponent(draftId) + '/documents');
        el('contractResult').textContent = JSON.stringify(data, null, 2);
        el('contractResult').hidden = false;
    } catch (e) {
        showFlash('交付書類の取得に失敗しました: ' + e.message, 'error');
    }
}

function bindEvents() {
    document.querySelectorAll('#mainNav [data-view]').forEach(function (button) {
        button.addEventListener('click', function () {
            showView(button.getAttribute('data-view'));
        });
    });
    el('logoutBtn').addEventListener('click', logout);
    el('simulateForm').addEventListener('submit', simulate);
    el('applyForm').addEventListener('submit', submitApplication);
    el('loginForm').addEventListener('submit', login);
    el('registerForm').addEventListener('submit', register);
    el('verifyMfaBtn').addEventListener('click', verifyMfa);
    el('verifyEmailBtn').addEventListener('click', verifyEmail);
    el('acceptConsentsBtn').addEventListener('click', function () {
        acceptRequiredConsents().then(function () {
            showFlash('必須同意を登録しました。', 'ok');
        }).catch(function (e) {
            showFlash('同意の登録に失敗しました: ' + e.message, 'error');
        });
    });
    el('startKycBtn').addEventListener('click', startKyc);
    el('uploadKycBtn').addEventListener('click', uploadKycImage);
    el('submitKycBtn').addEventListener('click', submitKyc);
    el('refreshKycBtn').addEventListener('click', refreshKyc);
    el('loadOfferBtn').addEventListener('click', loadOffer);
    el('acceptOfferBtn').addEventListener('click', acceptOffer);
    el('startSigningBtn').addEventListener('click', startSigning);
    el('completeSigningBtn').addEventListener('click', completeSigning);
    el('loadDocumentsBtn').addEventListener('click', loadDocuments);
    el('refreshMypageBtn').addEventListener('click', function () {
        loadProfile();
        loadMyPage();
    });
    el('simProduct').addEventListener('change', updateProductLabels);
    el('applyProduct').addEventListener('change', updateProductLabels);
    document.addEventListener('click', function (event) {
        const simButton = event.target.closest('[data-sim-product]');
        if (simButton) {
            el('simProduct').value = simButton.getAttribute('data-sim-product');
            updateProductLabels();
            showView('simulate');
            return;
        }
        const applyButton = event.target.closest('[data-apply-product]');
        if (applyButton) {
            if (!state.token) {
                showFlash('申込にはログインが必要です。', 'warn');
                showView('auth');
                return;
            }
            el('applyProduct').value = applyButton.getAttribute('data-apply-product');
            updateProductLabels();
            showView('apply');
        }
    });
    window.addEventListener('hashchange', function () {
        showView(window.location.hash.replace('#', '') || 'home');
    });
}

async function init() {
    bindEvents();
    setAuthState();
    if (state.draftId) {
        el('contractDraftId').value = state.draftId;
    }
    if (state.kycCheckId) {
        el('kycCheckId').value = state.kycCheckId;
    }
    if (state.signingSessionId) {
        el('signingSessionId').value = state.signingSessionId;
    }
    try {
        await loadProducts();
    } catch (e) {
        showFlash('商品情報の取得に失敗しました: ' + e.message, 'error');
    }
    if (state.token) {
        await loadProfile();
        await loadMyPage();
    }
    showView(window.location.hash.replace('#', '') || 'home');
}

document.addEventListener('DOMContentLoaded', init);
