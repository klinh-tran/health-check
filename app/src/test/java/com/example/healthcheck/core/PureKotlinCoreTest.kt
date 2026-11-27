package com.example.healthcheck.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the rule that signal processing and scoring stay free of Android dependencies. */
class PureKotlinCoreTest {
    @Test fun coreHasNoAndroidImports() {
        val root = listOf("src/main/java/com/example/healthcheck/core", "app/src/main/java/com/example/healthcheck/core")
            .map(::File).first { it.isDirectory }
        val files = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("no core sources found", files.size >= 5)
        val offenders = files.filter { f ->
            f.readLines().any { it.startsWith("import android") || it.startsWith("import androidx") }
        }
        assertTrue("Android imports in pure core: $offenders", offenders.isEmpty())
    }
}
