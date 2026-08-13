# App Blocker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A minimal Android app blocker for one Pixel 9: pick apps, master toggle, blocked apps bounce to a block screen, pause for N minutes auto-resumes.

**Architecture:** An `AccessibilityService` receives foreground-window-change events and consults a pure `shouldBlock()` function against state (enabled / blockedPackages / pausedUntil) persisted in DataStore. Blocking = fire global HOME action + launch a full-screen block activity. Pause is only a timestamp — no alarms or timers except a cosmetic UI countdown.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), DataStore Preferences, single Gradle module, no other dependencies. JUnit4 + kotlinx-coroutines-test for JVM unit tests.

**Spec:** `docs/superpowers/specs/2026-08-13-app-blocker-design.md`

## Global Constraints

- Package name: `com.anastasiia.appblocker` (everywhere; also the `SELF_PACKAGE` constant).
- `minSdk = 35`, `targetSdk = 36`, `compileSdk = 36`.
- Dependencies: AndroidX + Compose BOM + DataStore only. No DI framework, no navigation library, no image library.
- Friction, not fortress: never add device-admin, never block own package.
- Pause is a timestamp (`pausedUntil` epoch millis, `0` = not paused). Never schedule alarms/jobs for resume.
- Version pins below are known-good at plan time. If a pinned artifact version does not resolve, use the nearest newer stable version and note it in the commit message.
- Repo: `git@github.com/AnastasiiaPirus/app-blocker` (https remote already configured, branch `master`). Commit at the end of every task; push after Tasks 2, 9, 10.

---

### Task 1: Dev environment (JDK, Android SDK, adb)

**Files:**
- Create: `~/.zprofile` additions (JAVA_HOME/ANDROID_HOME exports) — append only, do not rewrite the file.

**Interfaces:**
- Produces: working `java 17`, `sdkmanager`, `adb`, SDK packages `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`. `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

- [ ] **Step 1: Install JDK 17 and Android command-line tools**

```bash
brew install --cask temurin@17 android-commandlinetools
```

- [ ] **Step 2: Verify java resolves**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && "$JAVA_HOME/bin/java" -version`
Expected: `openjdk version "17...`

- [ ] **Step 3: Install SDK packages and accept licenses**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --licenses > /dev/null
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

- [ ] **Step 4: Verify**

Run: `sdkmanager --list_installed && "$ANDROID_HOME/platform-tools/adb" --version`
Expected: the three packages listed; adb prints a version.

- [ ] **Step 5: Persist env for future shells**

Append to `~/.zprofile` (only if these lines are not already present):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

No commit (nothing in repo changed).

---

### Task 2: Gradle project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `local.properties`, `.gitignore`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/anastasiia/appblocker/MainActivity.kt`, `app/src/main/res/values/strings.xml`, gradle wrapper files.

**Interfaces:**
- Produces: a building app module; `./gradlew assembleDebug` and `./gradlew test` succeed. Later tasks add files under `app/src/main/java/com/anastasiia/appblocker/` and `app/src/test/java/com/anastasiia/appblocker/`.

- [ ] **Step 1: Write root files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "app-blocker"
include(":app")
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
```

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.12.0"
kotlin = "2.2.0"
composeBom = "2025.06.01"
activityCompose = "1.10.1"
lifecycle = "2.9.1"
datastore = "1.1.7"
coroutines = "1.10.2"
junit = "4.13.2"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui = { module = "androidx.compose.ui:ui" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
datastore-preferences-core = { module = "androidx.datastore:datastore-preferences-core", version.ref = "datastore" }
junit = { module = "junit:junit", version.ref = "junit" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`local.properties`:

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

`.gitignore`:

```gitignore
.gradle/
build/
local.properties
.DS_Store
*.iml
.idea/
```

- [ ] **Step 2: Write app module**

`app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.anastasiia.appblocker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anastasiia.appblocker"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.datastore.preferences.core)
}
```

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="@string/app_name"
        android:icon="@android:drawable/sym_def_app_icon"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">App Blocker</string>
    <string name="accessibility_service_description">Watches which app is in the foreground and shows a block screen for apps you chose to block. Nothing is read from the screen and nothing leaves the device.</string>
</resources>
```

`app/src/main/java/com/anastasiia/appblocker/MainActivity.kt` (placeholder, replaced in Task 8):

```kotlin
package com.anastasiia.appblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("App Blocker") } }
    }
}
```

- [ ] **Step 3: Generate the wrapper and build**

```bash
cd /Users/anastasiia/Documents/Projects/app-blocker
gradle wrapper --gradle-version 8.14.3
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (First run downloads Gradle + dependencies; takes minutes.)

- [ ] **Step 4: Commit and push**

```bash
git add -A && git commit -m "feat: Gradle/Compose project scaffold" && git push
```

---

### Task 3: Core decision logic (`shouldBlock`, `formatRemaining`)

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/core/BlockerState.kt`, `app/src/main/java/com/anastasiia/appblocker/core/Decision.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/DecisionTest.kt`

**Interfaces:**
- Produces: `data class BlockerState(val enabled: Boolean = false, val blockedPackages: Set<String> = emptySet(), val pausedUntil: Long = 0L)`; `const val SELF_PACKAGE = "com.anastasiia.appblocker"`; `fun shouldBlock(pkg: String?, state: BlockerState, now: Long): Boolean`; `fun formatRemaining(millis: Long): String` (e.g. `"4:32"`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/anastasiia/appblocker/core/DecisionTest.kt`:

```kotlin
package com.anastasiia.appblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTest {
    private val state = BlockerState(
        enabled = true,
        blockedPackages = setOf("com.instagram.android"),
        pausedUntil = 0L,
    )

    @Test fun blocksListedAppWhenEnabled() =
        assertTrue(shouldBlock("com.instagram.android", state, now = 1000L))

    @Test fun ignoresUnlistedApp() =
        assertFalse(shouldBlock("com.spotify.music", state, now = 1000L))

    @Test fun ignoresWhenDisabled() =
        assertFalse(shouldBlock("com.instagram.android", state.copy(enabled = false), 1000L))

    @Test fun ignoresWhilePaused() =
        assertFalse(shouldBlock("com.instagram.android", state.copy(pausedUntil = 2000L), now = 1000L))

    @Test fun blocksAgainWhenPauseExpires() =
        assertTrue(shouldBlock("com.instagram.android", state.copy(pausedUntil = 2000L), now = 2000L))

    @Test fun neverBlocksSelf() =
        assertFalse(shouldBlock(SELF_PACKAGE, state.copy(blockedPackages = setOf(SELF_PACKAGE)), 1000L))

    @Test fun ignoresNullPackage() =
        assertFalse(shouldBlock(null, state, 1000L))

    @Test fun formatsRemainingRoundingUp() {
        assertEquals("4:32", formatRemaining(271_001L))
        assertEquals("0:01", formatRemaining(1L))
        assertEquals("0:00", formatRemaining(0L))
        assertEquals("0:00", formatRemaining(-5_000L))
        assertEquals("60:00", formatRemaining(3_600_000L))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test`
Expected: compilation FAILURE (`BlockerState` unresolved).

- [ ] **Step 3: Implement**

`app/src/main/java/com/anastasiia/appblocker/core/BlockerState.kt`:

```kotlin
package com.anastasiia.appblocker.core

data class BlockerState(
    val enabled: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val pausedUntil: Long = 0L,
) {
    fun isPaused(now: Long): Boolean = now < pausedUntil
}
```

`app/src/main/java/com/anastasiia/appblocker/core/Decision.kt`:

```kotlin
package com.anastasiia.appblocker.core

const val SELF_PACKAGE = "com.anastasiia.appblocker"

fun shouldBlock(pkg: String?, state: BlockerState, now: Long): Boolean =
    pkg != null &&
        pkg != SELF_PACKAGE &&
        state.enabled &&
        !state.isPaused(now) &&
        pkg in state.blockedPackages

fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all `DecisionTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: blocking decision logic with tests"
```

---

### Task 4: State repository (DataStore)

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/core/BlockerStateRepository.kt`, `app/src/main/java/com/anastasiia/appblocker/core/DataStoreExt.kt`
- Test: `app/src/test/java/com/anastasiia/appblocker/core/BlockerStateRepositoryTest.kt`

**Interfaces:**
- Consumes: `BlockerState` (Task 3).
- Produces: `class BlockerStateRepository(dataStore: DataStore<Preferences>)` with `val state: Flow<BlockerState>`, `suspend fun setEnabled(value: Boolean)`, `suspend fun setBlockedPackages(value: Set<String>)`, `suspend fun setPausedUntil(value: Long)`; extension `val Context.blockerDataStore: DataStore<Preferences>`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anastasiia/appblocker/core/BlockerStateRepositoryTest.kt`:

```kotlin
package com.anastasiia.appblocker.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlockerStateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsAllFields() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val store = PreferenceDataStoreFactory.createWithPath(scope = scope) {
            tmp.newFile("state.preferences_pb").absolutePath.toPath()
        }
        val repo = BlockerStateRepository(store)

        assertEquals(BlockerState(), repo.state.first())

        repo.setEnabled(true)
        repo.setBlockedPackages(setOf("com.instagram.android", "com.zhiliaoapp.musically"))
        repo.setPausedUntil(123_456L)

        assertEquals(
            BlockerState(
                enabled = true,
                blockedPackages = setOf("com.instagram.android", "com.zhiliaoapp.musically"),
                pausedUntil = 123_456L,
            ),
            repo.state.first(),
        )
        scope.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test`
Expected: compilation FAILURE (`BlockerStateRepository` unresolved).

- [ ] **Step 3: Implement**

`app/src/main/java/com/anastasiia/appblocker/core/BlockerStateRepository.kt`:

```kotlin
package com.anastasiia.appblocker.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BlockerStateRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
    }

    val state: Flow<BlockerState> = dataStore.data.map { prefs ->
        BlockerState(
            enabled = prefs[Keys.ENABLED] ?: false,
            blockedPackages = prefs[Keys.BLOCKED_PACKAGES] ?: emptySet(),
            pausedUntil = prefs[Keys.PAUSED_UNTIL] ?: 0L,
        )
    }

    suspend fun setEnabled(value: Boolean) = dataStore.edit { it[Keys.ENABLED] = value }
    suspend fun setBlockedPackages(value: Set<String>) = dataStore.edit { it[Keys.BLOCKED_PACKAGES] = value }
    suspend fun setPausedUntil(value: Long) = dataStore.edit { it[Keys.PAUSED_UNTIL] = value }
}
```

`app/src/main/java/com/anastasiia/appblocker/core/DataStoreExt.kt`:

```kotlin
package com.anastasiia.appblocker.core

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.blockerDataStore by preferencesDataStore(name = "blocker_state")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, repository + decision tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: DataStore-backed state repository with round-trip test"
```

---

### Task 5: Block screen activity

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/BlockScreenActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register activity)

**Interfaces:**
- Produces: `BlockScreenActivity` with `companion object { const val EXTRA_PACKAGE = "blocked_package" }`; launched with the blocked app's package name in that extra. Task 6's service launches it.

- [ ] **Step 1: Implement the activity**

`app/src/main/java/com/anastasiia/appblocker/BlockScreenActivity.kt`:

```kotlin
package com.anastasiia.appblocker

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

class BlockScreenActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PACKAGE = "blocked_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val (label, icon) = try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg ?: "", 0)
            pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info).toBitmap()
        } catch (e: PackageManager.NameNotFoundException) {
            (pkg ?: "App") to null
        }

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        icon?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(72.dp).padding(bottom = 16.dp),
                            )
                        }
                        Text(label, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Blocked",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                        )
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Register in the manifest**

Add inside `<application>` in `app/src/main/AndroidManifest.xml`:

```xml
<activity
    android:name=".BlockScreenActivity"
    android:excludeFromRecents="true"
    android:exported="false"
    android:launchMode="singleInstance"
    android:taskAffinity="" />
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (Behavior verified on device in Task 10.)

- [ ] **Step 4: Commit**

```bash
git add app/src && git commit -m "feat: full-screen block activity"
```

---

### Task 6: Blocker accessibility service

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/BlockerService.kt`, `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (register service)

**Interfaces:**
- Consumes: `shouldBlock`, `BlockerState`, `BlockerStateRepository`, `blockerDataStore` (Tasks 3–4), `BlockScreenActivity.EXTRA_PACKAGE` (Task 5).
- Produces: `BlockerService : AccessibilityService`, component `com.anastasiia.appblocker/.BlockerService` (Task 7 checks for it in enabled-services settings).

- [ ] **Step 1: Implement the service**

`app/src/main/java/com/anastasiia/appblocker/BlockerService.kt`:

```kotlin
package com.anastasiia.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.anastasiia.appblocker.core.BlockerState
import com.anastasiia.appblocker.core.BlockerStateRepository
import com.anastasiia.appblocker.core.blockerDataStore
import com.anastasiia.appblocker.core.shouldBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockerService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var state = BlockerState()

    override fun onServiceConnected() {
        val repository = BlockerStateRepository(applicationContext.blockerDataStore)
        scope.launch { repository.state.collect { state = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (shouldBlock(pkg, state, System.currentTimeMillis())) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            startActivity(
                Intent(this, BlockScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(BlockScreenActivity.EXTRA_PACKAGE, pkg),
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 2: Service configuration XML**

`app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="false"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="50" />
```

- [ ] **Step 3: Register in the manifest**

Add inside `<application>` in `app/src/main/AndroidManifest.xml`:

```xml
<service
    android:name=".BlockerService"
    android:exported="false"
    android:label="@string/app_name"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: accessibility service that blocks listed apps"
```

---

### Task 7: Main ViewModel + installed-apps and service-status helpers

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/ui/MainViewModel.kt`, `app/src/main/java/com/anastasiia/appblocker/ui/InstalledApps.kt`, `app/src/main/java/com/anastasiia/appblocker/ui/ServiceStatus.kt`
- Modify: `app/src/main/AndroidManifest.xml` (package-visibility `<queries>`)

**Interfaces:**
- Consumes: `BlockerStateRepository`, `blockerDataStore`, `BlockerState` (Tasks 3–4), `BlockerService` (Task 6).
- Produces:
  - `class MainViewModel(app: Application) : AndroidViewModel(app)` with `val state: StateFlow<BlockerState>`, `fun setEnabled(value: Boolean)`, `fun pauseFor(minutes: Int)`, `fun resumeNow()`, `fun setBlockedPackages(value: Set<String>)`.
  - `data class AppInfo(val packageName: String, val label: String)`; `fun launchableApps(pm: PackageManager): List<AppInfo>` (sorted by label, self excluded).
  - `fun isBlockerServiceEnabled(context: Context): Boolean`.

- [ ] **Step 1: Implement the ViewModel**

`app/src/main/java/com/anastasiia/appblocker/ui/MainViewModel.kt`:

```kotlin
package com.anastasiia.appblocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anastasiia.appblocker.core.BlockerState
import com.anastasiia.appblocker.core.BlockerStateRepository
import com.anastasiia.appblocker.core.blockerDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = BlockerStateRepository(app.blockerDataStore)

    val state: StateFlow<BlockerState> = repository.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, BlockerState())

    fun setEnabled(value: Boolean) = viewModelScope.launch { repository.setEnabled(value) }

    fun pauseFor(minutes: Int) = viewModelScope.launch {
        repository.setPausedUntil(System.currentTimeMillis() + minutes * 60_000L)
    }

    fun resumeNow() = viewModelScope.launch { repository.setPausedUntil(0L) }

    fun setBlockedPackages(value: Set<String>) =
        viewModelScope.launch { repository.setBlockedPackages(value) }
}
```

- [ ] **Step 2: Implement helpers**

`app/src/main/java/com/anastasiia/appblocker/ui/InstalledApps.kt`:

```kotlin
package com.anastasiia.appblocker.ui

import android.content.Intent
import android.content.pm.PackageManager
import com.anastasiia.appblocker.core.SELF_PACKAGE

data class AppInfo(val packageName: String, val label: String)

fun launchableApps(pm: PackageManager): List<AppInfo> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcherIntent, 0)
        .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .distinctBy { it.packageName }
        .filter { it.packageName != SELF_PACKAGE }
        .sortedBy { it.label.lowercase() }
}
```

`app/src/main/java/com/anastasiia/appblocker/ui/ServiceStatus.kt`:

```kotlin
package com.anastasiia.appblocker.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.anastasiia.appblocker.BlockerService

fun isBlockerServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, BlockerService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}
```

- [ ] **Step 3: Package visibility**

Add to `app/src/main/AndroidManifest.xml` directly under `<manifest>` (before `<application>`):

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "feat: main view model, installed-apps and service-status helpers"
```

---

### Task 8: Main screen UI

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/MainActivity.kt` (replace placeholder)

**Interfaces:**
- Consumes: `MainViewModel`, `isBlockerServiceEnabled`, `launchableApps`/`AppInfo` (Task 7), `formatRemaining`, `BlockerState` (Task 3).
- Produces: `@Composable fun MainScreen(viewModel: MainViewModel, onEditApps: () -> Unit)`. `MainActivity` hosts `MainScreen` and (from Task 9) `EditAppsScreen` behind a `var screen` state; Task 9 fills in `EditAppsScreen`.

- [ ] **Step 1: Implement the screen**

`app/src/main/java/com/anastasiia/appblocker/ui/MainScreen.kt`:

```kotlin
package com.anastasiia.appblocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anastasiia.appblocker.core.formatRemaining
import kotlinx.coroutines.delay

private val PAUSE_MINUTES = listOf(1, 5, 15, 60)

@Composable
fun MainScreen(viewModel: MainViewModel, onEditApps: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var serviceEnabled by remember { mutableStateOf(isBlockerServiceEnabled(context)) }
    var advancedExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            serviceEnabled = isBlockerServiceEnabled(context)
            delay(1_000L)
        }
    }

    val appLabels = remember(state.blockedPackages) {
        val byPackage = launchableApps(context.packageManager).associateBy { it.packageName }
        state.blockedPackages.map { pkg -> byPackage[pkg]?.label ?: pkg }.sorted()
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (!serviceEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clickable {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                ) {
                    Text(
                        "Blocking isn't active — tap to enable the accessibility service.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Blocking", style = MaterialTheme.typography.headlineSmall)
                Switch(checked = state.enabled, onCheckedChange = { viewModel.setEnabled(it) })
            }

            if (state.isPaused(now)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Paused — resumes in ${formatRemaining(state.pausedUntil - now)}")
                    TextButton(onClick = { viewModel.resumeNow() }) { Text("Resume now") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Blocked apps", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onEditApps) { Text("Edit") }
            }

            LazyColumn(Modifier.weight(1f)) {
                if (appLabels.isEmpty()) {
                    item {
                        Text(
                            "No apps selected yet.",
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(appLabels) { label ->
                    Text(label, modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(if (advancedExpanded) "Advanced ▲" else "Advanced ▼")
            }
            if (advancedExpanded) {
                Text("Pause blocking", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PAUSE_MINUTES.forEach { minutes ->
                        Button(onClick = { viewModel.pauseFor(minutes) }) { Text("${minutes}m") }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Replace MainActivity**

`app/src/main/java/com/anastasiia/appblocker/MainActivity.kt` (full replacement; `EditAppsScreen` arrives in Task 9 — until then keep the `Screen.EditApps` branch as the `Text("Edit apps")` placeholder shown here):

```kotlin
package com.anastasiia.appblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anastasiia.appblocker.ui.MainScreen
import com.anastasiia.appblocker.ui.MainViewModel

private enum class Screen { Main, EditApps }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                val viewModel: MainViewModel = viewModel()
                var screen by remember { mutableStateOf(Screen.Main) }
                when (screen) {
                    Screen.Main -> MainScreen(viewModel, onEditApps = { screen = Screen.EditApps })
                    Screen.EditApps -> Text("Edit apps") // replaced in Task 9
                }
            }
        }
    }
}
```

- [ ] **Step 3: Verify it compiles and tests still pass**

Run: `./gradlew assembleDebug test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src && git commit -m "feat: main screen with toggle, pause controls, service banner"
```

---

### Task 9: Edit-apps screen (searchable checklist)

**Files:**
- Create: `app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt`
- Modify: `app/src/main/java/com/anastasiia/appblocker/MainActivity.kt` (wire in the screen)

**Interfaces:**
- Consumes: `MainViewModel.setBlockedPackages`, `launchableApps`, `AppInfo` (Task 7).
- Produces: `@Composable fun EditAppsScreen(viewModel: MainViewModel, onDone: () -> Unit)`.

- [ ] **Step 1: Implement the screen**

`app/src/main/java/com/anastasiia/appblocker/ui/EditAppsScreen.kt`:

```kotlin
package com.anastasiia.appblocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun EditAppsScreen(viewModel: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { launchableApps(context.packageManager) }
    val saved = viewModel.state.collectAsState().value.blockedPackages
    var selected by remember { mutableStateOf(saved) }
    var query by remember { mutableStateOf("") }

    val visible = apps.filter { it.label.contains(query, ignoreCase = true) }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps") },
                singleLine = true,
            )
            LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(app.label, modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = app.packageName in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + app.packageName
                                else selected - app.packageName
                            },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    // Prune anything no longer installed while we're here.
                    val installed = apps.map { it.packageName }.toSet()
                    viewModel.setBlockedPackages(selected intersect installed)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
```

- [ ] **Step 2: Wire into MainActivity**

In `app/src/main/java/com/anastasiia/appblocker/MainActivity.kt` replace the placeholder branch:

```kotlin
Screen.EditApps -> EditAppsScreen(viewModel, onDone = { screen = Screen.Main })
```

and add the import `com.anastasiia.appblocker.ui.EditAppsScreen`; remove the now-unused `Text` import.

- [ ] **Step 3: Verify it compiles and tests pass**

Run: `./gradlew assembleDebug test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit and push**

```bash
git add app/src && git commit -m "feat: searchable edit-apps checklist" && git push
```

---

### Task 10: On-device install and verification

**Files:** none (verification only).

**Interfaces:**
- Consumes: everything above; a Pixel 9 with USB debugging enabled.

**USER STEP (cannot be automated):** On the Pixel 9: Settings → About phone → tap **Build number** 7× to enable Developer options; then Settings → System → Developer options → enable **USB debugging**; connect via USB and accept the computer's RSA fingerprint prompt.

- [ ] **Step 1: Confirm device visible**

Run: `adb devices`
Expected: one device listed as `device` (not `unauthorized`).

- [ ] **Step 2: Install**

Run: `cd /Users/anastasiia/Documents/Projects/app-blocker && ./gradlew installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 3: Enable the accessibility service (user or adb)**

Preferred: open the app; tap the warning banner; enable **App Blocker** in Accessibility settings. (Fallback via adb: `adb shell settings put secure enabled_accessibility_services com.anastasiia.appblocker/com.anastasiia.appblocker.BlockerService && adb shell settings put secure accessibility_enabled 1` — only if the Settings UI path fails.)

- [ ] **Step 4: Verification checklist (drive via adb, confirm via screenshots)**

Each check: launch with `adb shell monkey -p <pkg> 1`, screenshot with `adb exec-out screencap -p > shot.png`, inspect.

1. Add one victim app (e.g. Chrome, `com.android.chrome`) via Edit; toggle ON. Launch it → block screen appears, Close lands on home.
2. Advanced → Pause 1m → victim opens normally.
3. Wait 65s → victim is blocked again (no interaction in between).
4. Toggle OFF → victim opens.
5. Toggle ON → `adb reboot` → after boot, victim is still blocked.
6. Own app is never blocked while ON.

Expected: all six pass.

- [ ] **Step 5: Update README and push**

Create `README.md`:

```markdown
# App Blocker

Personal Android app blocker (Pixel 9, sideloaded). Pick apps, flip the
toggle; blocked apps bounce to a block screen. Pause for 1/5/15/60 minutes
from the Advanced section — auto-resumes.

- Spec: docs/superpowers/specs/2026-08-13-app-blocker-design.md
- Build & install: `./gradlew installDebug` (device with USB debugging)
- Enable: the app's warning banner links to Accessibility settings.

Backlog: domain blocking, schedules, pause friction.
```

```bash
git add README.md && git commit -m "docs: README" && git push
```
