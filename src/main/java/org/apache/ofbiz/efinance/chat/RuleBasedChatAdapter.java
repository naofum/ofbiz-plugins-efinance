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
package org.apache.ofbiz.efinance.chat;

import java.util.Locale;

/**
 * STEP W9 default FAQ adapter.
 *
 * <p>This adapter intentionally does not make credit decisions or quote
 * individual terms. It answers common procedural questions and escalates
 * sensitive or unresolved requests to the support team.</p>
 */
public class RuleBasedChatAdapter implements ChatAdapter {

    private static final String INTENT_GREETING = "GREETING";
    private static final String INTENT_PRODUCT = "PRODUCT";
    private static final String INTENT_APPLICATION = "APPLICATION";
    private static final String INTENT_KYC = "KYC";
    private static final String INTENT_CONTRACT = "CONTRACT";
    private static final String INTENT_PAYMENT = "PAYMENT";
    private static final String INTENT_PRIVACY = "PRIVACY";
    private static final String INTENT_DEALER = "DEALER";
    private static final String INTENT_THANKS = "THANKS";
    private static final String INTENT_FALLBACK = "FALLBACK";
    private static final String INTENT_HUMAN = "HUMAN";

    @Override
    public String greeting(Locale locale) {
        if (isJapanese(locale)) {
            return "こんにちは。eFinance AIサポートです。"
                    + "商品、お申込み、本人確認、ご契約、ご返済についてご案内します。"
                    + "担当者へのご相談が必要な場合は「担当者と話したい」と入力してください。";
        }
        return "Hello. This is eFinance AI support. "
                + "I can help with products, applications, identity verification, contracts and repayments. "
                + "Type \"human\" if you need to speak to a support agent.";
    }

    @Override
    public ChatReply reply(ChatRequest request) {
        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        String normalized = message.toLowerCase(Locale.ROOT);
        boolean ja = isJapanese(request.getLocale()) || containsJapanese(message);

        if (containsAny(normalized, "担当者", "オペレーター", "オペレータ", "人と話", "人間と",
                "エスカレ", "苦情", "クレーム", "返済できない", "支払えない", "不正利用",
                "human", "agent", "operator", "complaint", "escalate", "speak to")) {
            return new ChatReply(ja
                    ? "担当者におつなぎします。これまでの会話をサポートケースに引き継ぎますので、"
                            + "しばらくお待ちください。個人情報や暗証番号は入力しないでください。"
                    : "I will connect you to a support agent and pass this conversation to a support case. "
                            + "Please do not enter passwords or full account numbers.", true, INTENT_HUMAN);
        }

        if (containsAny(normalized, "こんにちは", "こんばんは", "おはよう", "はじめまして",
                "hello", "hi ", "good morning", "good afternoon")) {
            return new ChatReply(greeting(request.getLocale()), false, INTENT_GREETING);
        }
        if (containsAny(normalized, "ありがとう", "助かり", "thank")) {
            return new ChatReply(ja
                    ? "お役に立ててうれしいです。ほかにご不明な点があれば、お気軽にご相談ください。"
                    : "You're welcome. Let me know if you have any other questions.", false, INTENT_THANKS);
        }
        if (containsAny(normalized, "金利", "利息", "ローン", "借入", "融資", "返済額",
                "シミュレーション", "いくら借り", "loan", "interest", "simulate")) {
            return new ChatReply(ja
                    ? "ローン商品の概要と返済シミュレーションは「商品」「シミュレーション」からご確認いただけます。"
                            + "実際の適用金利・条件は審査結果により確定します。個別の与信判断はお答えできません。"
                    : "You can review loan products and run a repayment simulation from Products and Simulation. "
                            + "Final rates and terms are confirmed after review; I cannot make an individual credit decision.",
                    false, INTENT_PRODUCT);
        }
        if (containsAny(normalized, "リース", "lease")) {
            return new ChatReply(ja
                    ? "リース商品は「商品」からご確認いただけます。物件の見積額、リース期間、"
                            + "リース区分により条件が変わります。正式な条件は審査後に確定します。"
                    : "You can review lease products from Products. Terms depend on the asset quote, term and lease type, "
                            + "and are confirmed after review.", false, INTENT_PRODUCT);
        }
        if (containsAny(normalized, "審査", "申込", "申し込み", "状況", "ステータス",
                "進捗", "application", "status", "review")) {
            return new ChatReply(ja
                    ? "お申込みの最新状況は「マイページ」の申込一覧でご確認いただけます。"
                            + "追加書類のご依頼がある場合は、画面の案内に従ってアップロードしてください。"
                            + "審査の個別結果や承認可否をこのチャットでお伝えすることはできません。"
                    : "You can check the latest application status in My Page. "
                            + "If documents are requested, upload them from the application screen. "
                            + "I cannot disclose individual review results or approval decisions in chat.",
                    false, INTENT_APPLICATION);
        }
        if (containsAny(normalized, "本人確認", "kyc", "免許", "在留カード", "マイナンバー",
                "identity", "verification")) {
            return new ChatReply(ja
                    ? "本人確認は「本人確認」画面から開始できます。本人確認書類の画像をアップロードし、"
                            + "案内に従って提出してください。書類の再提出が必要な場合はメールとマイページでお知らせします。"
                    : "Start identity verification from the Identity Verification screen. "
                            + "Upload your identity document and submit it following the instructions. "
                            + "If resubmission is required, we will notify you by email and in My Page.",
                    false, INTENT_KYC);
        }
        if (containsAny(normalized, "契約", "電子署名", "署名", "押印", "contract", "sign")) {
            return new ChatReply(ja
                    ? "ご契約の手続きは「契約」画面から行えます。申込IDを入力してオファー内容を確認し、"
                            + "同意・電子署名を完了してください。契約内容を変更する場合は、署名前にサポートへご連絡ください。"
                    : "Contract procedures are available from the Contract screen. Enter your application ID, "
                            + "review the offer, and complete consent and electronic signature. "
                            + "If you need to change the terms, contact support before signing.",
                    false, INTENT_CONTRACT);
        }
        if (containsAny(normalized, "返済", "引き落とし", "口座振替", "支払い", "入金",
                "payment", "repay", "direct debit", "installment")) {
            return new ChatReply(ja
                    ? "ご返済予定や口座振替の設定は「マイページ」のご契約情報からご確認ください。"
                            + "引き落としができない場合は、速やかに担当者へご相談ください。"
                            + "返済の個別繰延や条件変更をこのチャットで確定することはできません。"
                    : "You can review repayment schedules and direct debit settings from My Page. "
                            + "If a payment cannot be made, please contact support promptly. "
                            + "Individual deferrals or term changes cannot be finalized in this chat.",
                    false, INTENT_PAYMENT);
        }
        if (containsAny(normalized, "個人情報", "プライバシー", "privacy", "personal data",
                "安全", "security")) {
            return new ChatReply(ja
                    ? "個人情報はプライバシーポリシーに従って取り扱います。このチャットに暗証番号、"
                            + "口座番号全体、本人確認書類の画像を入力しないでください。"
                    : "Personal data is handled according to the privacy policy. "
                            + "Do not enter passwords, full account numbers, or identity document images in this chat.",
                    false, INTENT_PRIVACY);
        }
        if (containsAny(normalized, "販売店", "ディーラー", "dealer", "代理", "紹介")) {
            return new ChatReply(ja
                    ? "販売店経由のお申込みについては、販売店の担当者または「マイページ」のサポートケースから"
                            + "お問い合わせください。販売店がお客様の代理で操作する場合は、事前の同意が必要です。"
                    : "For applications through a dealer, contact your dealer representative or use My Page support. "
                            + "A dealer must have your consent before acting on your behalf.",
                    false, INTENT_DEALER);
        }
        return new ChatReply(ja
                ? "ご質問をうまく理解できませんでした。商品、お申込み状況、本人確認、ご契約、ご返済の"
                        + "いずれかについてお尋ねください。担当者へのご相談が必要な場合は「担当者と話したい」と入力してください。"
                : "I could not understand your question. Please ask about products, application status, "
                        + "identity verification, contracts or repayments. Type \"human\" to speak to an agent.",
                false, INTENT_FALLBACK);
    }

    private static boolean isJapanese(Locale locale) {
        return locale != null && "ja".equalsIgnoreCase(locale.getLanguage());
    }

    private static boolean containsJapanese(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x3040 && codePoint <= 0x30ff)
                        || (codePoint >= 0x4e00 && codePoint <= 0x9faf));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
