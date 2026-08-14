/*
 * Copyright (c) 2026 OkHttp Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp.testbed.android.ech

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import okhttp3.OkHttp

/**
 * Hands OkHttp the application context before any test runs.
 *
 * `okhttp-android` reads the public suffix list from its own assets, and to find them it needs a
 * `Context`. In an app that context arrives through an androidx Startup `Initializer` declared in
 * the library's manifest. That doesn't happen here: the library is on the instrumentation APK's
 * classpath rather than the app's, so nothing runs the initializer and the context is never set.
 *
 * What that looks like is not a missing asset — the asset is present — but this, on every test
 * that resolves a name:
 *
 *     java.lang.IllegalStateException: Unable to load PublicSuffixDatabase.list resource.
 *     Caused by: java.io.IOException: Platform applicationContext not initialized.
 *         Startup Initializer possibly disabled, call OkHttp.initialize before test.
 *
 * `DnsOverHttps` asks `isPrivateHost` about the name before it opens anything, so this throws
 * before ECH is reached and every case fails for a reason that has nothing to do with ECH.
 *
 * Doing it in the runner rather than in a `@BeforeEach` is what makes it true for every suite,
 * including ones added later, and it happens once rather than per test.
 */
class EchTestRunner : AndroidJUnitRunner() {
  override fun callApplicationOnCreate(app: Application) {
    super.callApplicationOnCreate(app)
    OkHttp.initialize(app)
  }
}
