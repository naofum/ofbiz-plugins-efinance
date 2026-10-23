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
    <title>eFinance Portal (Sample)</title>
    <style>
        :root {
            --navy: #173b64;
            --blue: #2563a8;
            --sky: #eaf3fb;
            --line: #d7e2ec;
            --ink: #22313f;
            --muted: #617182;
            --ok: #176b46;
            --warn: #9a4b00;
            --danger: #a52222;
        }
        * { box-sizing: border-box; }
        body { margin: 0; color: var(--ink); background: #f4f7fa; font-family: "Segoe UI", "Meiryo", sans-serif; line-height: 1.6; }
        button, input, select, textarea { font: inherit; }
        button { cursor: pointer; }
        .topbar { background: var(--navy); color: #fff; padding: 0 1.5rem; display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
        .brand { font-size: 1.2rem; font-weight: 700; padding: 1rem 0; white-space: nowrap; }
        .brand span { font-size: .72rem; font-weight: 400; opacity: .75; margin-left: .35rem; }
        .topbar nav { display: flex; flex-wrap: wrap; gap: .25rem; margin-left: auto; }
        .topbar nav button { background: transparent; color: #fff; border: 1px solid transparent; border-radius: 6px; padding: .55rem .7rem; }
        .topbar nav button:hover, .topbar nav button.active { background: rgba(255,255,255,.14); border-color: rgba(255,255,255,.25); }
        main { max-width: 1180px; margin: 0 auto; padding: 1.5rem; }
        .hero { background: linear-gradient(135deg, var(--navy), var(--blue)); color: #fff; border-radius: 14px; padding: 2.2rem; margin-bottom: 1.5rem; }
        .hero h1 { margin: 0 0 .5rem; font-size: clamp(1.6rem, 3vw, 2.4rem); }
        .hero p { margin: 0; max-width: 760px; color: #e2edf8; }
        .view[hidden] { display: none; }
        .section-title { display: flex; align-items: end; justify-content: space-between; gap: 1rem; margin: 1.5rem 0 .8rem; flex-wrap: wrap; }
        .section-title h2 { margin: 0; font-size: 1.3rem; color: var(--navy); }
        .section-title p { margin: 0; color: var(--muted); font-size: .9rem; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 1rem; }
        .card { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 1.2rem; box-shadow: 0 2px 8px rgba(23,59,100,.04); }
        .card h3 { margin: 0 0 .4rem; color: var(--navy); }
        .card p { color: var(--muted); margin: .3rem 0; }
        .meta { color: var(--muted); font-size: .85rem; }
        .actions { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: 1rem; }
        .btn { border: 1px solid var(--blue); background: var(--blue); color: #fff; border-radius: 7px; padding: .55rem .9rem; }
        .btn:hover { filter: brightness(1.08); }
        .btn.secondary { background: #fff; color: var(--blue); }
        .btn.danger { background: var(--danger); border-color: var(--danger); }
        .btn:disabled { opacity: .5; cursor: wait; }
        .panel { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 1.2rem; margin-bottom: 1rem; }
        .panel h2, .panel h3 { color: var(--navy); margin-top: 0; }
        .form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: .8rem 1rem; }
        .field { display: flex; flex-direction: column; gap: .25rem; }
        .field label { font-size: .86rem; font-weight: 600; color: #34495e; }
        .field input, .field select, .field textarea { border: 1px solid #b9c8d6; border-radius: 6px; padding: .55rem .6rem; background: #fff; }
        .field input:focus, .field select:focus, .field textarea:focus { outline: 2px solid #9cc4e8; border-color: var(--blue); }
        .field .hint { color: var(--muted); font-size: .78rem; }
        .wide { grid-column: 1 / -1; }
        .checkline { display: flex; gap: .55rem; align-items: flex-start; margin: .45rem 0; }
        .checkline input { margin-top: .45rem; }
        .flash { border-radius: 8px; padding: .75rem 1rem; margin-bottom: 1rem; border: 1px solid transparent; white-space: pre-wrap; }
        .flash.ok { background: #e9f7ef; color: var(--ok); border-color: #b8e0c9; }
        .flash.warn { background: #fff5e8; color: var(--warn); border-color: #f3d2a3; }
        .flash.error { background: #fdeeee; color: var(--danger); border-color: #f1bcbc; }
        .split { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 1rem; }
        .result { background: #f7fbff; border: 1px solid var(--line); border-radius: 8px; padding: .8rem; overflow: auto; white-space: pre-wrap; font-size: .86rem; }
        table { width: 100%; border-collapse: collapse; font-size: .9rem; }
        th, td { border-bottom: 1px solid var(--line); text-align: left; padding: .55rem .4rem; vertical-align: top; }
        th { color: var(--navy); background: #f7fbff; }
        .empty { color: var(--muted); padding: .6rem 0; }
        .tabs { display: flex; gap: .4rem; flex-wrap: wrap; margin-bottom: .8rem; }
        .tabs button { border: 1px solid var(--line); background: #fff; color: var(--navy); border-radius: 999px; padding: .45rem .8rem; }
        .tabs button.active { background: var(--sky); border-color: #a8c8e5; }
        .muted { color: var(--muted); }
        code { background: #eef3f8; padding: .1rem .3rem; border-radius: 4px; }
        .chat-log { border: 1px solid var(--line); border-radius: 10px; background: #f8fbfe; padding: .8rem; min-height: 260px; max-height: 480px; overflow-y: auto; margin: .8rem 0; }
        .chat-message { margin: .45rem 0; padding: .65rem .8rem; border-radius: 10px; max-width: 88%; white-space: pre-wrap; line-height: 1.55; }
        .chat-message .chat-meta { display: block; font-size: .72rem; color: var(--muted); margin-bottom: .2rem; }
        .chat-message.customer { margin-left: auto; background: #e3f0fb; border: 1px solid #c3ddf2; }
        .chat-message.ai { background: #fff; border: 1px solid var(--line); }
        .chat-message.staff { background: #eef8f0; border: 1px solid #c8e6cf; }
        .chat-message.system { background: #fff7e6; border: 1px solid #f1d6a5; font-size: .88rem; }
        .chat-message .chat-body { word-break: break-word; }
        .chat-status { font-size: .88rem; }
        .chat-status .badge { display: inline-block; border-radius: 999px; padding: .15rem .55rem; background: var(--sky); color: var(--navy); font-size: .78rem; }
        footer { color: var(--muted); font-size: .8rem; text-align: center; padding: 2rem 1rem 3rem; }
        @media (max-width: 640px) {
            .topbar { padding: .5rem .8rem; }
            .brand { padding: .55rem 0; }
            .topbar nav { margin-left: 0; width: 100%; }
            main { padding: 1rem; }
            .hero { padding: 1.4rem; }
        }
    </style>
</head>
<body>
<header class="topbar">
    <div class="brand">eFinance <span>sample portal</span></div>
    <nav id="mainNav">
        <button type="button" data-view="home" class="active">ホーム</button>
        <button type="button" data-view="products">商品</button>
        <button type="button" data-view="simulate">シミュレーション</button>
        <button type="button" data-view="apply">申込</button>
        <button type="button" data-view="kyc">本人確認</button>
        <button type="button" data-view="contract">契約</button>
        <button type="button" data-view="mypage" data-auth-only hidden>マイページ</button>
        <button type="button" data-view="chat" data-auth-only hidden>チャット</button>
        <button type="button" data-view="auth" id="navAuth">ログイン</button>
        <button type="button" id="logoutBtn" hidden>ログアウト</button>
    </nav>
</header>

<main>
    <div id="flash" class="flash" hidden></div>

    <section id="view-home" class="view">
        <div class="hero">
            <h1>暮らしと事業の資金を、もっと身近に。</h1>
            <p>ローンとリースの申込、本人確認、契約、契約後の手続きをオンラインで確認できる eFinance のサンプルポータルです。画面は REST API の動作確認用で、本番の顧客画面は別リポジトリで実装します。</p>
        </div>
        <div class="section-title">
            <h2>おすすめの商品</h2>
            <p>公開中の商品を API から取得しています。</p>
        </div>
        <div id="homeProducts" class="grid"></div>
    </section>

    <section id="view-products" class="view" hidden>
        <div class="section-title">
            <h2>商品一覧</h2>
            <p>商品を選んでシミュレーションまたは申込に進めます。</p>
        </div>
        <div id="productList" class="grid"></div>
    </section>

    <section id="view-simulate" class="view" hidden>
        <div class="panel">
            <h2>返済・リース料シミュレーション</h2>
            <p class="muted">入力内容は保存されません。実際の条件は審査結果により変わります。</p>
            <form id="simulateForm" class="form-grid">
                <div class="field wide">
                    <label for="simProduct">商品</label>
                    <select id="simProduct" required></select>
                </div>
                <div class="field">
                    <label for="simAmount" id="simAmountLabel">借入希望額（円）</label>
                    <input id="simAmount" type="number" min="1000" step="1000" required value="300000"/>
                </div>
                <div class="field">
                    <label for="simTerm">期間（月）</label>
                    <input id="simTerm" type="number" min="1" max="120" required value="24"/>
                </div>
                <div class="field">
                    <label for="simRate">金利（年率・任意）</label>
                    <input id="simRate" type="number" min="0" step="0.001" placeholder="商品の既定金利を使用"/>
                </div>
                <div class="field wide actions">
                    <button type="submit" class="btn">シミュレーション実行</button>
                </div>
            </form>
            <div id="simulateResult" class="result" hidden></div>
        </div>
    </section>

    <section id="view-apply" class="view" hidden>
        <div class="panel">
            <h2>お申込み（サンプル）</h2>
            <p class="muted">ローンは3ステップ、リースは4ステップです。送信には最新の利用規約・プライバシー・信用情報に関する同意が必要です。</p>
            <form id="applyForm" class="form-grid">
                <div class="field wide">
                    <label for="applyProduct">商品</label>
                    <select id="applyProduct" required></select>
                </div>
                <div class="field">
                    <label for="applyAmount" id="applyAmountLabel">借入希望額（円）</label>
                    <input id="applyAmount" type="number" min="1000" step="1000" required value="300000"/>
                </div>
                <div class="field">
                    <label for="applyTerm">期間（月）</label>
                    <input id="applyTerm" type="number" min="1" max="120" required value="24"/>
                </div>
                <div class="field">
                    <label for="applyRepayment">返済方法（ローン）</label>
                    <select id="applyRepayment">
                        <option value="EQUAL_PAYMENT">元利均等</option>
                        <option value="EQUAL_PRINCIPAL">元金均等</option>
                    </select>
                </div>

                <div id="loanApplicantFields" class="wide split">
                    <fieldset class="panel">
                        <legend>お客さま情報</legend>
                        <div class="form-grid">
                            <div class="field"><label for="applyFirstName">名</label><input id="applyFirstName" required/></div>
                            <div class="field"><label for="applyLastName">姓</label><input id="applyLastName" required/></div>
                            <div class="field"><label for="applyBirthDate">生年月日</label><input id="applyBirthDate" type="date" required value="1985-01-01"/></div>
                            <div class="field"><label for="applyPostal">郵便番号</label><input id="applyPostal" required value="100-0001"/></div>
                            <div class="field"><label for="applyPrefecture">都道府県</label><input id="applyPrefecture" required value="東京都"/></div>
                            <div class="field"><label for="applyCity">市区町村</label><input id="applyCity" required value="千代田区"/></div>
                            <div class="field wide"><label for="applyAddress">住所</label><input id="applyAddress" required value="1-1-1"/></div>
                            <div class="field"><label for="applyEmployer">勤務先</label><input id="applyEmployer" required value="サンプル株式会社"/></div>
                            <div class="field"><label for="applyIncome">年収（円）</label><input id="applyIncome" type="number" required value="5000000"/></div>
                            <div class="field"><label for="applyResidence">居住形態</label>
                                <select id="applyResidence"><option value="RENT">賃貸</option><option value="OWN">持家</option><option value="FAMILY">家族</option></select>
                            </div>
                        </div>
                    </fieldset>
                </div>

                <div id="leaseApplicantFields" class="wide split" hidden>
                    <fieldset class="panel">
                        <legend>リース物件・法人情報</legend>
                        <div class="form-grid">
                            <div class="field wide"><label for="applyAssetName">物件名</label><input id="applyAssetName" value="業務用機器"/></div>
                            <div class="field"><label for="applyAcquisitionCost">取得見積額（円）</label><input id="applyAcquisitionCost" type="number" min="1000" step="1000" value="300000"/></div>
                            <div class="field"><label for="applyCompanyName">法人名</label><input id="applyCompanyName" value="サンプル株式会社"/></div>
                            <div class="field"><label for="applyRepresentative">代表者名</label><input id="applyRepresentative" value="山田 太郎"/></div>
                        </div>
                    </fieldset>
                </div>

                <div class="wide panel">
                    <h3>同意</h3>
                    <p class="muted">送信前に、現在有効な必須同意文書へ同意します。</p>
                    <div class="checkline"><input id="applyConsentCheck" type="checkbox" required/><label for="applyConsentCheck">利用規約・プライバシーポリシー・信用情報の取扱いに同意します。</label></div>
                    <button type="button" id="acceptConsentsBtn" class="btn secondary">同意を登録</button>
                </div>

                <div class="field wide actions">
                    <button type="submit" class="btn">下書き作成から送信まで実行</button>
                </div>
            </form>
            <div id="applyResult" class="result" hidden></div>
        </div>
    </section>

    <section id="view-kyc" class="view" hidden>
        <div class="panel">
            <h2>本人確認（eKYC）</h2>
            <p class="muted">本人確認を開始し、画像をアップロードして審査へ提出できます。</p>
            <div class="form-grid">
                <div class="field">
                    <label for="kycMethod">確認方法</label>
                    <select id="kycMethod">
                        <option value="EFKYC_M_IMAGE_FACE">本人確認書類＋顔写真</option>
                        <option value="EFKYC_M_IC_CHIP">ICチップ読取</option>
                        <option value="EFKYC_M_POSTAL">郵送書類</option>
                    </select>
                </div>
                <div class="field">
                    <label for="kycCheckId">本人確認ID</label>
                    <input id="kycCheckId" placeholder="開始後に自動設定されます"/>
                </div>
                <div class="field">
                    <label for="kycImageType">画像種別</label>
                    <select id="kycImageType">
                        <option value="EFKYC_IMG_ID_FRONT">本人確認書類（表面）</option>
                        <option value="EFKYC_IMG_ID_BACK">本人確認書類（裏面）</option>
                        <option value="EFKYC_IMG_FACIAL">顔写真</option>
                    </select>
                </div>
                <div class="field">
                    <label for="kycFile">画像ファイル</label>
                    <input id="kycFile" type="file" accept="image/*"/>
                </div>
                <div class="field wide actions">
                    <button type="button" id="startKycBtn" class="btn">本人確認を開始</button>
                    <button type="button" id="uploadKycBtn" class="btn secondary">画像をアップロード</button>
                    <button type="button" id="submitKycBtn" class="btn secondary">審査へ提出</button>
                    <button type="button" id="refreshKycBtn" class="btn secondary">状態を更新</button>
                </div>
            </div>
            <div id="kycResult" class="result" hidden></div>
        </div>
    </section>

    <section id="view-contract" class="view" hidden>
        <div class="panel">
            <h2>見積・契約・電子交付</h2>
            <p class="muted">申込後に提示された見積を確認し、契約締結まで進めます。契約締結には再認証が必要です。</p>
            <div class="form-grid">
                <div class="field wide">
                    <label for="contractDraftId">申込下書きID</label>
                    <input id="contractDraftId" placeholder="申込後に自動設定されます"/>
                    <p class="muted">デモ環境では、提示済み見積のある下書きID「DEMO_EF_DRAFT2」で契約フローを確認できます。</p>
                </div>
                <div class="field wide actions">
                    <button type="button" id="loadOfferBtn" class="btn">見積を取得</button>
                    <button type="button" id="acceptOfferBtn" class="btn secondary" disabled>見積を受理</button>
                    <button type="button" id="startSigningBtn" class="btn secondary" disabled>署名を開始</button>
                    <button type="button" id="loadDocumentsBtn" class="btn secondary">交付書類を表示</button>
                </div>
                <div class="field">
                    <label for="signingReauthPassword">再認証パスワード</label>
                    <input id="signingReauthPassword" type="password" autocomplete="current-password"/>
                </div>
                <div class="field">
                    <label for="signingSessionId">署名セッションID</label>
                    <input id="signingSessionId" placeholder="署名開始後に自動設定されます"/>
                </div>
                <div class="field wide actions">
                    <button type="button" id="completeSigningBtn" class="btn">契約を締結</button>
                </div>
            </div>
            <div id="contractResult" class="result" hidden></div>
        </div>
    </section>

    <section id="view-chat" class="view" hidden>
        <div class="panel">
            <div class="section-title">
                <h2>チャットサポート</h2>
                <span id="chatStatus" class="chat-status muted">チャットを開始してください。</span>
            </div>
            <p class="muted">商品・お申込み・本人確認・ご契約・ご返済についてご案内します。個別の審査結果や返済条件の確定はできません。担当者へのご相談が必要な場合は、会話をサポートケースに引き継ぎます。</p>
            <div class="actions">
                <button type="button" id="startChatBtn" class="btn">チャットを開始</button>
                <button type="button" id="escalateChatBtn" class="btn secondary" hidden>担当者と話す</button>
                <button type="button" id="closeChatBtn" class="btn secondary" hidden>チャットを終了</button>
            </div>
            <div id="chatLog" class="chat-log"><p class="empty">会話はまだありません。</p></div>
            <form id="chatForm" class="form-grid" hidden>
                <div class="field wide">
                    <label for="chatMessage">メッセージ</label>
                    <textarea id="chatMessage" rows="3" maxlength="4000" required placeholder="例: 本人確認の書類について教えてください"></textarea>
                </div>
                <div class="field wide actions"><button type="submit" class="btn">送信</button></div>
            </form>
        </div>
    </section>

    <section id="view-mypage" class="view" hidden>
        <div class="panel">
            <div class="section-title">
                <h2>マイページ</h2>
                <button type="button" id="refreshMypageBtn" class="btn secondary">最新情報に更新</button>
            </div>
            <div id="profileSummary" class="muted"></div>
        </div>
        <div class="split">
            <div class="panel"><h3>申込</h3><div id="myApplications"></div></div>
            <div class="panel"><h3>契約</h3><div id="myContracts"></div></div>
            <div class="panel"><h3>メッセージ</h3><div id="myMessages"></div></div>
            <div class="panel"><h3>サポートケース</h3><div id="mySupportCases"></div></div>
            <div class="panel"><h3>通知設定</h3><div id="myNotifications"></div></div>
        </div>
    </section>

    <section id="view-auth" class="view" hidden>
        <div class="split">
            <div class="panel">
                <h2>ログイン</h2>
                <form id="loginForm" class="form-grid">
                    <div class="field wide"><label for="loginId">メールアドレス / ログインID</label><input id="loginId" autocomplete="username" required/></div>
                    <div class="field wide"><label for="loginPassword">パスワード</label><input id="loginPassword" type="password" autocomplete="current-password" required/></div>
                    <div class="field wide actions"><button type="submit" class="btn">ログイン</button></div>
                </form>
                <div id="mfaBox" class="panel" hidden>
                    <h3>多要素認証</h3>
                    <div class="form-grid">
                        <div class="field wide"><label for="mfaCode">ワンタイムパスワード</label><input id="mfaCode" inputmode="numeric" autocomplete="one-time-code"/></div>
                        <div class="field wide actions"><button type="button" id="verifyMfaBtn" class="btn">認証する</button></div>
                    </div>
                </div>
                <p class="muted">デモアカウント: <code>demo-ef-customer</code> / <code>ofbiz</code></p>
            </div>
            <div class="panel">
                <h2>新規登録</h2>
                <form id="registerForm" class="form-grid">
                    <div class="field wide"><label for="registerEmail">メールアドレス</label><input id="registerEmail" type="email" required/></div>
                    <div class="field"><label for="registerFirstName">名</label><input id="registerFirstName" required/></div>
                    <div class="field"><label for="registerLastName">姓</label><input id="registerLastName"/></div>
                    <div class="field wide"><label for="registerPhone">電話番号</label><input id="registerPhone"/></div>
                    <div class="field wide"><label for="registerPassword">パスワード</label><input id="registerPassword" type="password" minlength="8" required/></div>
                    <div class="field wide actions"><button type="submit" class="btn">登録する</button></div>
                </form>
                <p class="muted">登録後、メール確認が必要です。メール送信はサンプルでは実装していません。</p>
                <details>
                    <summary>メール確認トークンで確認する</summary>
                    <div class="form-grid" style="margin-top:.7rem">
                        <div class="field wide"><label for="verifyToken">確認トークン</label><input id="verifyToken"/></div>
                        <div class="field wide actions"><button type="button" id="verifyEmailBtn" class="btn secondary">メールを確認</button></div>
                    </div>
                </details>
            </div>
        </div>
    </section>
</main>

<footer>
    eFinance sample portal — REST API: <code>/rest/ef</code> ・ 本番画面は別リポジトリで実装します。
</footer>

<script src="/portal/js/portal.js" defer></script>
</body>
</html>
