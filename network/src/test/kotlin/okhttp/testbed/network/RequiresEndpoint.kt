/*
 * Copyright (C) 2026 Square, Inc.
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
package okhttp.testbed.network

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * The servers a test needs in order to say anything.
 *
 * On a class it applies to every test in it; on a method it adds to whatever the class declared.
 * If any of them fails its probe the test is skipped with the reason attached, rather than failed
 * — a server that is gone is not a result about OkHttp.
 *
 * Declare what the test *depends on*, not everything it touches. `EchTest` reaches four names
 * under `tls-ech.dev`, but one server stands behind them.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(EndpointPreflight::class)
annotation class RequiresEndpoint(
  vararg val value: Endpoint,
)

/**
 * Runs [Preflight] for whatever the test declared, before the test does.
 *
 * The skip is a JUnit assumption, which lands in the JUnit XML as a skipped case with its
 * message — so it reaches the status page through the machinery already there, with no new file
 * format and no change to how results are collected.
 */
class EndpointPreflight : BeforeEachCallback {
  override fun beforeEach(context: ExtensionContext) {
    for (endpoint in context.requiredEndpoints()) {
      val result = Preflight.check(endpoint)
      assumeTrue(result.up) {
        "${endpoint.server} (${endpoint.operator}) is unavailable: ${result.detail}. " +
          "Probed ${endpoint.probe.target}."
      }
    }
  }

  /**
   * The method's declaration and the class's, in that order, de-duplicated.
   *
   * `getElement` gives the annotated method or class for this context; walking up to the parent
   * picks up a class-level declaration when we are looking at a method.
   */
  private fun ExtensionContext.requiredEndpoints(): List<Endpoint> =
    generateSequence(this) { it.parent.orElse(null) }
      .mapNotNull { it.element.orElse(null) }
      .flatMap { it.getAnnotationsByType(RequiresEndpoint::class.java).asSequence() }
      .flatMap { it.value.asSequence() }
      .distinct()
      .toList()
}
