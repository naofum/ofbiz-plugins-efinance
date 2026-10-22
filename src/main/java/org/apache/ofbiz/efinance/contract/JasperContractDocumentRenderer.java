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

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collection;
import java.util.Map;

import org.apache.ofbiz.base.location.FlexibleLocation;
import org.apache.ofbiz.base.util.Debug;

/**
 * Renders the JasperReports contract template reflectively.
 *
 * <p>The optional jasperreports plugin is not a compile-time dependency of
 * efinance. If the plugin or template is unavailable, the caller falls back to
 * {@link ContractDocumentPdf}. This keeps the base efinance plugin usable in
 * deployments that only load the simple PDF renderer.</p>
 */
public final class JasperContractDocumentRenderer {

    private static final String MODULE = JasperContractDocumentRenderer.class.getName();
    private static final String TEMPLATE =
            "component://efinance/webapp/efinance/reports/EfinanceContractReport.jrxml";

    private JasperContractDocumentRenderer() {
    }

    public static byte[] render(Map<String, Object> parameters,
            Collection<Map<String, Object>> rows) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> compiler = Class.forName("net.sf.jasperreports.engine.JasperCompileManager", true, loader);
            Class<?> filler = Class.forName("net.sf.jasperreports.engine.JasperFillManager", true, loader);
            Class<?> exporter = Class.forName("net.sf.jasperreports.engine.JasperExportManager", true, loader);
            Class<?> reportClass = Class.forName("net.sf.jasperreports.engine.JasperReport", true, loader);
            Class<?> dataSourceClass = Class.forName("net.sf.jasperreports.engine.JRDataSource", true, loader);
            Class<?> mapDataSourceClass = Class.forName(
                    "net.sf.jasperreports.engine.data.JRMapCollectionDataSource", true, loader);

            URL templateUrl = FlexibleLocation.resolveLocation(TEMPLATE);
            if (templateUrl == null) {
                Debug.logWarning("JasperReports contract template not found: " + TEMPLATE, MODULE);
                return null;
            }

            Object report;
            try (InputStream input = templateUrl.openStream()) {
                Method compile = compiler.getMethod("compileReport", InputStream.class);
                report = compile.invoke(null, input);
            }

            Object dataSource = mapDataSourceClass.getConstructor(Collection.class).newInstance(rows);
            Method fill = filler.getMethod("fillReport", reportClass, Map.class, dataSourceClass);
            Object print = fill.invoke(null, report, parameters, dataSource);

            Method export = exporter.getMethod("exportReportToPdf",
                    Class.forName("net.sf.jasperreports.engine.JasperPrint", true, loader));
            return (byte[]) export.invoke(null, print);
        } catch (ClassNotFoundException e) {
            Debug.logInfo("JasperReports plugin is not available; using simple contract PDF", MODULE);
            return null;
        } catch (Exception e) {
            Debug.logError(e, "Unable to render the JasperReports contract template; using simple PDF", MODULE);
            return null;
        }
    }
}
