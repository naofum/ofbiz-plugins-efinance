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
package org.apache.ofbiz.efinance.contract;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small dependency-free PDF writer for the eFinance contract delivery sample.
 *
 * <p>The document uses the PDF built-in Japanese CID font
 * {@code HeiseiKakuGo-W5} with {@code UniJIS-UCS2-H}. It is intentionally
 * limited to text, but produces a valid PDF suitable for the electronic
 * delivery flow and tests. A production deployment should replace this with
 * the finance/jasperreports template when that adapter is available.</p>
 */
public final class ContractDocumentPdf {

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int LINE_HEIGHT = 14;
    private static final int FIRST_BASELINE = 800;
    private static final int LINES_PER_PAGE = 54;

    private ContractDocumentPdf() {
    }

    public static byte[] build(List<String> lines) {
        List<String> safeLines = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            safeLines.add("eFinance contract document");
        } else {
            safeLines.addAll(lines);
        }

        int pageCount = (safeLines.size() + LINES_PER_PAGE - 1) / LINES_PER_PAGE;
        Map<Integer, byte[]> objects = new TreeMap<>();
        int firstPageObject = 6;
        objects.put(1, asciiObject(1, "<< /Type /Catalog /Pages 2 0 R >>"));
        objects.put(3, asciiObject(3,
                "<< /Type /Font /Subtype /Type0 /BaseFont /HeiseiKakuGo-W5 "
                        + "/Encoding /UniJIS-UCS2-H /DescendantFonts [4 0 R] >>"));
        objects.put(4, asciiObject(4,
                "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /HeiseiKakuGo-W5 "
                        + "/CIDSystemInfo << /Registry (Adobe) /Ordering (Japan1) /Supplement 2 >> "
                        + "/FontDescriptor 5 0 R /DW 1000 >>"));
        objects.put(5, asciiObject(5,
                "<< /Type /FontDescriptor /FontName /HeiseiKakuGo-W5 /Flags 4 "
                        + "/FontBBox [-25 -20 1000 900] /ItalicAngle 0 /Ascent 880 "
                        + "/Descent -120 /CapHeight 700 /StemV 80 >>"));

        StringBuilder pageRefs = new StringBuilder();
        for (int page = 0; page < pageCount; page++) {
            int pageObject = firstPageObject + page * 2;
            int contentObject = pageObject + 1;
            pageRefs.append(pageObject).append(" 0 R ");
            objects.put(pageObject, asciiObject(pageObject,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT
                            + "] /Resources << /Font << /F1 3 0 R >> /ProcSet [/PDF /Text] >> "
                            + "/Contents " + contentObject + " 0 R >>"));

            int from = page * LINES_PER_PAGE;
            int to = Math.min(from + LINES_PER_PAGE, safeLines.size());
            StringBuilder content = new StringBuilder();
            content.append("BT\n/F1 9 Tf\n50 ").append(FIRST_BASELINE).append(" Td\n");
            for (int i = from; i < to; i++) {
                content.append('<').append(toUtf16Hex(safeLines.get(i))).append("> Tj\n");
                if (i + 1 < to) {
                    content.append("0 -").append(LINE_HEIGHT).append(" Td\n");
                }
            }
            content.append("ET\n");
            objects.put(contentObject, streamObject(contentObject,
                    content.toString().getBytes(StandardCharsets.US_ASCII)));
        }

        objects.put(2, asciiObject(2,
                "<< /Type /Pages /Kids [" + pageRefs.toString().trim() + "] /Count " + pageCount + " >>"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "%PDF-1.4\n%");
        out.write(0xE2);
        out.write(0xE3);
        out.write(0xCF);
        out.write(0xD3);
        out.write('\n');

        int[] offsets = new int[objects.size() + 1];
        for (Map.Entry<Integer, byte[]> entry : objects.entrySet()) {
            offsets[entry.getKey()] = out.size();
            out.write(entry.getValue(), 0, entry.getValue().length);
        }

        int xrefOffset = out.size();
        writeAscii(out, "xref\n0 " + (objects.size() + 1) + "\n");
        writeAscii(out, "0000000000 65535 f \n");
        for (int i = 1; i <= objects.size(); i++) {
            writeAscii(out, String.format("%010d 00000 n \n", offsets[i]));
        }
        writeAscii(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\n");
        writeAscii(out, "startxref\n" + xrefOffset + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static byte[] asciiObject(int objectNumber, String body) {
        return ("\n" + objectNumber + " 0 obj\n" + body + "\nendobj\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] streamObject(int objectNumber, byte[] stream) {
        byte[] header = ("\n" + objectNumber + " 0 obj\n<< /Length " + stream.length + " >>\nstream\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] footer = "\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header, 0, header.length);
        out.write(stream, 0, stream.length);
        out.write(footer, 0, footer.length);
        return out.toByteArray();
    }

    private static String toUtf16Hex(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16BE);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }
}
