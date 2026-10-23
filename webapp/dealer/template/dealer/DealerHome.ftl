<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <title>eFinance Dealer Portal (Sample)</title>
    <style>
        :root { --navy:#173b64; --blue:#2563a8; --line:#d7e2ec; --ink:#22313f; --muted:#617182; --bg:#f4f7fa; --ok:#176b46; --danger:#a52222; }
        * { box-sizing:border-box; }
        body { margin:0; font-family:"Segoe UI","Meiryo",sans-serif; color:var(--ink); background:var(--bg); }
        header { background:var(--navy); color:#fff; padding:16px 24px; display:flex; justify-content:space-between; align-items:center; }
        header h1 { font-size:20px; margin:0; }
        main { max-width:1100px; margin:0 auto; padding:24px; }
        section { background:#fff; border:1px solid var(--line); border-radius:8px; padding:18px; margin-bottom:16px; }
        h2 { font-size:17px; margin:0 0 12px; color:var(--navy); }
        label { display:block; font-size:13px; color:var(--muted); margin:8px 0 4px; }
        input, select { width:100%; padding:8px; border:1px solid var(--line); border-radius:5px; }
        button { background:var(--blue); color:#fff; border:0; border-radius:5px; padding:8px 14px; cursor:pointer; margin-top:10px; }
        button.secondary { background:#eef3f8; color:var(--ink); }
        table { width:100%; border-collapse:collapse; font-size:13px; }
        th, td { border-bottom:1px solid var(--line); padding:7px; text-align:left; vertical-align:top; }
        .muted { color:var(--muted); font-size:13px; }
        .flash { padding:10px 12px; border-radius:5px; margin-bottom:12px; display:none; }
        .flash.ok { background:#e9f7ef; color:var(--ok); }
        .flash.error { background:#fdecec; color:var(--danger); }
        .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:14px; }
        .hidden { display:none; }
        @media (max-width:700px) { main { padding:12px; } header { padding:12px; } }
    </style>
</head>
<body>
<header>
    <h1>eFinance Dealer Portal <span class="muted">(Sample)</span></h1>
    <div>
        <span id="sessionLabel" class="muted"></span>
        <button id="logoutButton" class="secondary hidden" type="button">ログアウト</button>
    </div>
</header>
<main>
    <div id="flash" class="flash"></div>

    <section id="loginSection">
        <h2>販売店ログイン</h2>
        <p class="muted">デモ: <code>demo-ef-dealer</code> / <code>ofbiz</code></p>
        <form id="loginForm">
            <div class="grid">
                <div><label for="userLoginId">ログインID</label><input id="userLoginId" value="demo-ef-dealer" required/></div>
                <div><label for="password">パスワード</label><input id="password" type="password" value="ofbiz" required/></div>
            </div>
            <button type="submit">ログイン</button>
        </form>
    </section>

    <div id="appSection" class="hidden">
        <section>
            <h2>販売店</h2>
            <label for="dealerSelect">担当販売店</label>
            <select id="dealerSelect"></select>
            <p id="dealerRoles" class="muted"></p>
        </section>

        <div class="grid">
            <section>
                <h2>顧客同意を登録</h2>
                <form id="customerForm">
                    <label for="customerPartyId">顧客 Party ID</label>
                    <input id="customerPartyId" placeholder="DEMO_EF_CUST" required/>
                    <label for="consentReference">同意書参照</label>
                    <input id="consentReference" placeholder="CONSENT-001" required/>
                    <button type="submit">登録</button>
                </form>
            </section>
            <section>
                <h2>代理申込の下書き</h2>
                <form id="draftForm">
                    <label for="draftCustomerPartyId">顧客 Party ID</label>
                    <input id="draftCustomerPartyId" placeholder="DEMO_EF_CUST" required/>
                    <label for="draftProductId">商品 ID</label>
                    <input id="draftProductId" value="DEMO_LOANPROD1" required/>
                    <label for="draftType">申込種別</label>
                    <select id="draftType"><option value="LOAN">LOAN</option><option value="LEASE">LEASE</option></select>
                    <button type="submit">下書き作成</button>
                </form>
            </section>
        </div>

        <section><h2>同意済み顧客</h2><table><thead><tr><th>顧客</th><th>状態</th><th>同意日</th></tr></thead><tbody id="customersBody"></tbody></table></section>
        <section><h2>代理申込の下書き</h2><table><thead><tr><th>下書き</th><th>顧客</th><th>商品</th><th>種別</th><th>進捗</th></tr></thead><tbody id="draftsBody"></tbody></table></section>
        <section><h2>申込一覧</h2><table><thead><tr><th>申込ID</th><th>顧客</th><th>種別</th><th>連携日</th></tr></thead><tbody id="applicationsBody"></tbody></table></section>
        <section><h2>契約一覧</h2><table><thead><tr><th>契約ID</th><th>顧客</th><th>種別</th><th>状態</th><th>金額</th></tr></thead><tbody id="contractsBody"></tbody></table></section>
    </div>
</main>
<script src="js/dealer.js"></script>
</body>
</html>
