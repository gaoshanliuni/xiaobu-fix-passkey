package io.github.gaoshanliuni.xiaobupasskey;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Looper;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import android.annotation.SuppressLint;

/** Explicit UI operation in an authorized root app_process. No GMS hooks or scanned URI input. */
@SuppressLint("PrivateApi") // Isolated, verified root helper; UI provides Settings fallback on other ROMs.
public final class SystemDefaults {
    private SystemDefaults() {}
    static Intent probe() { return new Intent(Intent.ACTION_VIEW, Uri.parse("FIDO:/")); }

    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("root required");
            if (args.length < 1 || (!"set".equals(args[0]) && !"clear".equals(args[0])))
                throw new IllegalArgumentException("unsupported operation");
            // PackageManager's preference maintenance is intended for the system identity.
            // The short-lived helper drops root before making Binder calls; no persistent service.
            android.system.Os.setuid(1000);
            Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            PackageManager pm = context.getPackageManager();
            Intent intent = probe();
            List<ResolveInfo> handlers = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (handlers.isEmpty()) throw new IllegalStateException("no FIDO handlers");
            List<ComponentName> components = new ArrayList<>();
            int match = 0;
            for (ResolveInfo info : handlers) {
                components.add(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                match = Math.max(match, info.match);
            }
            Class<?> api = Class.forName("android.content.pm.IPackageManager");
            Object binder = threadClass.getMethod("getPackageManager").invoke(null);
            IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            filter.addDataScheme("FIDO");
            if ("set".equals(args[0])) {
                if (args.length != 2) throw new IllegalArgumentException("missing component");
                ComponentName chosen = ComponentName.unflattenFromString(args[1]);
                if (chosen == null || !components.contains(chosen)) throw new IllegalArgumentException("not a FIDO handler");
                api.getMethod("replacePreferredActivity", IntentFilter.class, int.class,
                        ComponentName[].class, ComponentName.class, int.class)
                        .invoke(binder, filter, match, components.toArray(new ComponentName[0]), chosen, 0);
                ResolveInfo resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved == null || !chosen.equals(new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)))
                    throw new IllegalStateException("default verification failed");
                System.out.println("OK:set");
            } else {
                clearFidoDefaults(api, binder);
                System.out.println("OK:clear");
            }
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
            System.out.println("ERROR:" + (cause == null ? "Unknown" : cause.getClass().getSimpleName()));
            if (cause != null) cause.printStackTrace(System.err);
            System.exit(1);
        }
        System.exit(0);
    }

    private static Document backup(Class<?> api, Object binder) throws Exception {
        byte[] bytes = (byte[]) api.getMethod("getPreferredActivityBackup", int.class).invoke(binder, 0);
        if (bytes == null || bytes.length == 0) throw new IllegalStateException("backup unavailable");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        // Android's built-in XML parser does not expose the desktop Xerces feature.
        // This document comes only from PackageManager, and must contain no DTD.
        if (new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("<!DOCTYPE"))
            throw new IllegalStateException("unexpected document type");
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if (document.getElementsByTagName("preferred-activities").getLength() != 1)
            throw new IllegalStateException("unsupported backup format");
        return document;
    }

    private static boolean isFido(Element item) {
        NodeList schemes = item.getElementsByTagName("scheme");
        boolean fido = false;
        for (int i = 0; i < schemes.getLength(); i++)
            if ("FIDO".equals(((Element) schemes.item(i)).getAttribute("name"))) fido = true;
        if (!fido) return false;
        NodeList actions = item.getElementsByTagName("action");
        for (int i = 0; i < actions.getLength(); i++)
            if (Intent.ACTION_VIEW.equals(((Element) actions.item(i)).getAttribute("name"))) return true;
        return false;
    }

    private static String itemPackage(Element item) {
        ComponentName component = ComponentName.unflattenFromString(item.getAttribute("name"));
        if (component == null) throw new IllegalStateException("invalid backup component");
        return component.getPackageName();
    }

    private static byte[] xml(Node node) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(node), new StreamResult(output));
        return output.toByteArray();
    }

    private static byte[] subset(Document source, Set<String> packages, boolean includeFido) throws Exception {
        Document copy = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        copy.appendChild(copy.importNode(source.getDocumentElement(), true));
        NodeList items = copy.getElementsByTagName("item");
        for (int i = items.getLength() - 1; i >= 0; i--) {
            Element item = (Element) items.item(i);
            // PackageManager's clear operation only removes always=true defaults. Last-used
            // entries remain in place and must not be restored a second time.
            if (!"true".equals(item.getAttribute("always")) || !packages.contains(itemPackage(item)) || (!includeFido && isFido(item)))
                item.getParentNode().removeChild(item);
        }
        return xml(copy);
    }

    private static Set<String> preservedRecords(Document document, Set<String> packages) throws Exception {
        Set<String> records = new TreeSet<>();
        NodeList items = document.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if ("true".equals(item.getAttribute("always")) && packages.contains(itemPackage(item)) && !isFido(item))
                records.add(new String(xml(item), java.nio.charset.StandardCharsets.UTF_8));
        }
        return records;
    }

    private static void clearFidoDefaults(Class<?> api, Object binder) throws Exception {
        Document before = backup(api, binder);
        Set<String> packages = new TreeSet<>();
        NodeList items = before.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if ("true".equals(item.getAttribute("always")) && isFido(item)) {
                if (item.getElementsByTagName("scheme").getLength() != 1 || item.getElementsByTagName("action").getLength() != 1)
                    throw new IllegalStateException("shared filter requires system settings");
                packages.add(itemPackage(item));
            }
        }
        if (packages.isEmpty()) return;
        byte[] rollback = subset(before, packages, true);
        byte[] remaining = subset(before, packages, false);
        Set<String> expected = preservedRecords(before, packages);
        // ColorOS does not remove 'always' entries with setLastChosenActivity. The supported
        // backup/restore API retains complete filters, matches and candidate sets. Only touched
        // packages are restored, preventing duplication of all other system preferences.
        try {
            for (String pkg : packages) api.getMethod("clearPackagePreferredActivities", String.class).invoke(binder, pkg);
            api.getMethod("restorePreferredActivities", byte[].class, int.class).invoke(binder, remaining, 0);
            Document after = backup(api, binder);
            if (!expected.equals(preservedRecords(after, packages)))
                throw new IllegalStateException("unrelated preferences verification failed");
            NodeList afterItems = after.getElementsByTagName("item");
            for (int i = 0; i < afterItems.getLength(); i++) {
                Element item = (Element) afterItems.item(i);
                if ("true".equals(item.getAttribute("always")) && isFido(item))
                    throw new IllegalStateException("FIDO clear verification failed");
            }
        } catch (Exception error) {
            for (String pkg : packages) api.getMethod("clearPackagePreferredActivities", String.class).invoke(binder, pkg);
            api.getMethod("restorePreferredActivities", byte[].class, int.class).invoke(binder, rollback, 0);
            throw error;
        }
    }
}
