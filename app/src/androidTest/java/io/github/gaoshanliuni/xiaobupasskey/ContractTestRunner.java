package io.github.gaoshanliuni.xiaobupasskey;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/** Runs on a real Android runtime; does not open a passkey or send network requests. */
public final class ContractTestRunner extends Instrumentation {
    private int assertions;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            for (String raw : new String[]{null, "", "fido:/1", "Fido:/1", " FIDO:/1", "FIDO:1", "https://example.com/FIDO:/1", "普通文字", "WIFI:T:WPA;S:test;P:test;;"}) {
                check(!FidoIntents.matches(raw), "nonmatching input");
            }
            for (String raw : new String[]{"FIDO:/", "FIDO:/001234567890", "FIDO://000123", "FIDO:/A%2fb+00?x=%20#x\n"}) {
                check(FidoIntents.matches(raw), "prefix");
                Intent outgoing = FidoIntents.view(raw, getTargetContext());
                check(raw.equals(outgoing.getDataString()), "exact raw preservation");
                check(Intent.ACTION_VIEW.equals(outgoing.getAction()), "ACTION_VIEW");
                check(outgoing.getComponent() == null && outgoing.getPackage() == null, "implicit resolver");
                check(outgoing.getExtras() == null, "no copied extras");
                check((outgoing.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0, "nonactivity launch flag");
            }
            Intent search = new Intent(Intent.ACTION_WEB_SEARCH).setPackage("com.heytap.browser").putExtra("query", "FIDO:/0012");
            check("FIDO:/0012".equals(FidoIntents.fallbackRaw(search)), "search fallback");
            check("com.heytap.browser".equals(search.getPackage()), "input intent not mutated");
            check(FidoIntents.fallbackRaw(new Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", "hello")) == null, "ordinary search");
            check(FidoIntents.fallbackRaw(new Intent(Intent.ACTION_SEND).putExtra("query", "FIDO:/1")) == null, "unrelated intent");
            Intent text = new Intent().setComponent(new ComponentName(FidoIntents.TARGET,
                    "com.oplus.scanengine.router.ui.DialogRouterActivity"))
                    .putExtra("router_type", "type_text").putExtra("router_content", "FIDO:/002");
            check("FIDO:/002".equals(FidoIntents.fallbackRaw(text)), "text dialog fallback");
            text.setComponent(new ComponentName("other.package", "other.package.Activity"));
            check(FidoIntents.fallbackRaw(text) == null, "foreign component");
            // Android DOM implementation + preference serialization must retain unrelated
            // defaults for the SAME package, and must not duplicate last-used records.
            String prefs = "<pa><preferred-activities>"
                    + "<item name='test.handler/.Fido' always='true' match='200000'><set name='test.handler/.Fido'/><filter><action name='android.intent.action.VIEW'/><scheme name='FIDO'/></filter></item>"
                    + "<item name='test.handler/.Other' always='true' match='600000'><set name='test.handler/.Other'/><filter><action name='android.intent.action.VIEW'/><staticType name='application/pdf'/></filter></item>"
                    + "<item name='test.handler/.LastUsed' always='false'><filter><action name='android.intent.action.SEND'/></filter></item>"
                    + "<item name='other.handler/.Other' always='true'><filter><action name='android.intent.action.VIEW'/><scheme name='https'/></filter></item>"
                    + "</preferred-activities></pa>";
            org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(prefs.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            java.lang.reflect.Method subset = SystemDefaults.class.getDeclaredMethod("subset", org.w3c.dom.Document.class, java.util.Set.class, boolean.class);
            subset.setAccessible(true);
            java.util.Set<String> touched = new java.util.HashSet<>();
            touched.add("test.handler");
            String retained = new String((byte[]) subset.invoke(null, doc, touched, false), java.nio.charset.StandardCharsets.UTF_8);
            check(!retained.contains("name=\"FIDO\""), "FIDO removed from restore data");
            check(retained.contains("application/pdf") && retained.contains("600000"), "same-package default retained");
            check(retained.contains("<set name=\"test.handler/.Other\""), "candidate set retained");
            check(!retained.contains("LastUsed"), "last-used entries not duplicated");
            check(!retained.contains("other.handler"), "untouched packages not duplicated");
            String rollback = new String((byte[]) subset.invoke(null, doc, touched, true), java.nio.charset.StandardCharsets.UTF_8);
            check(rollback.contains("name=\"FIDO\"") && rollback.contains("application/pdf"), "rollback includes old defaults");
            result.putString("stream", "PASS: " + assertions + " Android intent contract assertions\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
