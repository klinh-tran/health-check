package com.example.healthcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the app's privacy promises at the source level (no network, one permission, nothing exported). */
class AppPolicyTest {
    private fun appFile(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.exists() } ?: error("not found: $path")

    private val ns = "http://schemas.android.com/apk/res/android"

    private val manifest by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(appFile("src/main/AndroidManifest.xml")).documentElement
    }

    private fun elements(tag: String) = manifest.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as org.w3c.dom.Element } }

    @Test fun onlyTheCameraPermission() {
        assertEquals(listOf("android.permission.CAMERA"), elements("uses-permission").map { it.getAttributeNS(ns, "name") })
    }

    @Test fun backupOffAndNoCleartext() {
        val app = elements("application").single()
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
        assertTrue(app.getAttributeNS(ns, "usesCleartextTraffic") != "true")
        assertEquals("", app.getAttributeNS(ns, "networkSecurityConfig"))
        assertEquals("", app.getAttributeNS(ns, "debuggable"))
        // Both backup rule files exclude every domain.
        for (f in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) {
            val text = appFile(f).readText()
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue("$f must exclude $domain", text.contains("<exclude domain=\"$domain\" path=\".\" />"))
            }
            assertTrue("$f must not include anything", !text.contains("<include"))
        }
    }

    @Test fun onlyTheLauncherActivityIsExported() {
        val exported = (elements("activity") + elements("service") + elements("receiver") + elements("provider"))
            .filter { it.getAttributeNS(ns, "exported") == "true" }
            .map { it.getAttributeNS(ns, "name") }
        assertEquals(listOf(".MainActivity"), exported)
        val removed = elements("receiver").filter { it.getAttributeNS("http://schemas.android.com/tools", "node") == "remove" }
        assertEquals(listOf("androidx.profileinstaller.ProfileInstallReceiver"), removed.map { it.getAttributeNS(ns, "name") })
    }

    @Test fun debugOverlayRemovesTheToolingActivities() {
        val debug = appFile("src/debug/AndroidManifest.xml").readText()
        for (name in listOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")) {
            assertTrue("$name must be removed in debug", Regex("""android:name="${Regex.escape(name)}"[^>]*tools:node="remove""").containsMatchIn(debug))
        }
    }

    @Test fun noNetworkCodeInSources() {
        val forbidden = Regex(
            """(?i)^\s*import\s+(java\.net|javax\.net|okhttp3|retrofit2|io\.ktor|com\.android\.volley|""" +
                """com\.google\.firebase|com\.google\.android\.gms|android\.webkit|android\.net\.http)""",
        )
        val words = Regex("""(?i)(https?://(?!schemas\.android\.com)|Socket\(|HttpURLConnection|URL\(|crashlytics|admob|analytics)""")
        val files = appFile("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size > 30)
        val offenders = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (forbidden.containsMatchIn(line) || words.containsMatchIn(line)) "${f.name}:${i + 1}: $line" else null
            }
        }
        assertTrue("network code found:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun noNetworkLibrariesDeclared() {
        val catalog = appFile("../gradle/libs.versions.toml").readText()
        val build = appFile("build.gradle.kts").readText()
        val bad = Regex("""(?i)okhttp|retrofit|ktor|volley|cronet|firebase|play-services|gms|crashlytics|analytics|sentry""")
        assertTrue(bad.findAll(catalog + build).map { it.value }.toList().toString(), !bad.containsMatchIn(catalog + build))
    }

    @Test fun loggingGoesOnlyThroughDebugLog() {
        val files = appFile("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "DebugLog.kt" }
        val raw = Regex("""\b(android\.util\.Log|Log\.[dewiv]\(|println\(|printStackTrace\(|System\.(out|err))""")
        val offenders = files.filter { f -> f.readLines().any { raw.containsMatchIn(it) } }.map { it.name }.toList()
        assertTrue("raw logging in $offenders", offenders.isEmpty())
        val debugLog = appFile("src/main/java/com/example/healthcheck/util/DebugLog.kt").readText()
        assertEquals(2, Regex("""if \(BuildConfig\.DEBUG\) Log\.""").findAll(debugLog).count())
    }

    @Test fun roomSchemaIsExportedForTheCurrentVersion() {
        val db = appFile("src/main/java/com/example/healthcheck/data/AppDatabase.kt").readText()
        val version = Regex("""version = (\d+)""").find(db)!!.groupValues[1]
        assertTrue("exportSchema must stay on", db.contains("exportSchema = true"))
        assertTrue("destructive migration must stay off", !db.contains("fallbackToDestructiveMigration"))
        val schema = appFile("schemas/com.example.healthcheck.data.AppDatabase/$version.json").readText()
        for (column in listOf("date", "heartRateBpm", "hrvRmssdMs", "breathingRate", "signalQuality", "steps", "sleepHours", "bedtime", "updatedAt", "isDemo")) {
            assertTrue("schema $version is missing $column; re-export it", schema.contains("\"columnName\": \"$column\""))
        }
    }
}
