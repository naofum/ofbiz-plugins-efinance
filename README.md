# Apache OFBiz eFinance

金融商品（ローン・リース）の Web 申込と顧客ポータルを提供する OFBiz プラグイン。

- 要件：`REQUIREMENTS_EFINANCE.md`
- 仕様：`SPEC_EFINANCE.md`
- 状態：W0〜W6 の API・DB は実装済み（2026-10-22）。サービス権限、JWT `jti`、OTP 非公開、同意文書、KYC・同意ゲート、support 顧客 API ブリッジを追加。リクエスト単位の認証フィルタ、法人アカウント、管理画面、顧客サンプル画面は未完成。
- 依存：`efinance → finance`、`efinance → support`、OFBiz core、rest-api。ecommerce には依存しない。
- 画面：`/efinanceadmin`（担当者向け管理）、`/portal`（顧客向けサンプル）。本番の顧客画面は別リポジトリで作成する。

## 主な機能

| STEP | 内容 | 状態 |
|------|------|------|
| W0 | 会員登録・ログイン・MFA・トークン・同意・監査 | API/DB 実装済み |
| W1 | 商品紹介・ローン/リースシミュレーション | 実装済み |
| W2 | 申込下書き・書類・送信 | 実装済み |
| W3 | 本人確認・同意 | API 実装済み |
| W4 | 仮審査・進捗・追加書類 | API 実装済み |
| W5 | 見積受理・電子契約・交付 | API 実装済み |
| W6 | マイページ・手続き・メッセージ・通知設定・support ブリッジ | API 実装済み |
| W7 | 口座振替・振込案内 | 設計 |
| W8 | 販売店ポータル | 設計 |
| W9 | 提携先組込み・チャット | 設計 |

## 検証

```powershell
.\gradlew.bat compileJava compileGroovy
.\gradlew.bat "ofbiz --test component=efinance"
```

結果は `runtime/logs/test-results/efinancetests.xml` で確認する。
