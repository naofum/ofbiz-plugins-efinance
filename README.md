# Apache OFBiz eFinance

金融商品（ローン・リース）の Web 申込と顧客ポータルを提供する OFBiz プラグイン。

- 要件：`REQUIREMENTS_EFINANCE.md`
- 仕様：`SPEC_EFINANCE.md`
- 状態：W0〜W6 の API・DB は実装済み（2026-10-22）。法人アカウント、`EfAuthFilter`、`EfStageEvent` / `NotificationAdapter`、管理画面、同意文書の版検証、再認証、`/portal` サンプル SPA、ポータル用デモデータ、JasperReports 契約 PDF・電子交付、`ESignatureAdapter`、REST CORS・エラーチェーンテストを追加。外部署名サービス、W7 は未実装。
- 依存：`efinance → finance`、`efinance → support`、OFBiz core、rest-api。ecommerce には依存しない。
- 注意：`/ef/` のトークン失効検証に rest-api の `EfAuthFilter` を、公開 REST 操作の認証除外に `APIAuthFilter` を使うため、efinance プラグイン単体ではなく rest-api の差分もデプロイ対象とする。
- 画面：`/efinanceadmin`（担当者向け管理）、`/portal`（顧客向けサンプル）。本番の顧客画面は別リポジトリで作成する。

## 主な機能

| STEP | 内容 | 状態 |
|------|------|------|
| W0 | 会員登録・ログイン・MFA・トークン・同意・監査・法人（契約照会権限を含む）・認証フィルタ | 実装済み |
| W1 | 商品紹介・ローン/リースシミュレーション | 実装済み |
| W2 | 申込下書き・書類・送信 | 実装済み |
| W3 | 本人確認・同意・KYC 審査 | 実装済み |
| W4 | 仮審査・進捗・追加書類・ステージイベント・通知 | 実装済み |
| W5 | 見積受理・電子契約・交付 | API・JasperReports PDF・電子交付・`ESignatureAdapter` は実装済み（外部署名サービス連携は未実装） |
| W6 | マイページ・手続き・メッセージ・通知設定・support ブリッジ・管理画面（監査ログを含む） | 実装済み |
| W7 | 口座振替・振込照会 | 設計 |
| W8 | 販売店ポータル | 設計 |
| W9 | 提携先組込み・チャット | 設計 |

## デモ

デモデータを投入すると、`/portal` から次のアカウントでログインできる。

- ログインID：`demo-ef-customer`
- パスワード：`ofbiz`
- 内容：KYC・同意済み。サンプル申込 1 件、有効なローン契約 1 件、メッセージ、通知設定あり。

```powershell
.\gradlew.bat "ofbiz --load-data readers=demo --load-data component=finance"
.\gradlew.bat "ofbiz --load-data readers=demo --load-data component=efinance"
```

`test-ef-customer` は `testdef/data/EfinanceTestData.xml` のテスト専用データで、demo には投入されない。

## 検証

```powershell
.\gradlew.bat compileJava compileGroovy
.\gradlew.bat "ofbiz --test component=efinance"
```

結果は `runtime/logs/test-results/efinancetests.xml` で確認する。
