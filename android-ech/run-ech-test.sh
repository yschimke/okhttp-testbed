#!/usr/bin/env bash

# Runs the Android ECH suite against the host-side containers.
#
# The containers can't run on the device, and the device can't reach the host by name, so
# this script is the glue: it starts the fixture on the host, waits for it to publish its
# ports and CA, forwards those ports onto the device with `adb reverse`, and then runs the
# instrumentation tests. `--smoke-only` stops after the fixture is up, which is what to run
# where no emulator is available — it still exercises Docker, Gradle and the fixture itself.

set -euo pipefail

mode="${1:-instrumentation}"
if [[ "$mode" != "instrumentation" && "$mode" != "--smoke-only" ]]; then
  echo "usage: $0 [--smoke-only]" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
endpoint_file="$temporary_dir/okhttp-testbed-ech.endpoint"
service_log="$temporary_dir/okhttp-testbed-ech.log"
startup_timeout_seconds="${ECH_FIXTURE_TIMEOUT_SECONDS:-1200}"
rm -f "$endpoint_file" "$service_log"

# Passed through so a run can pick a version the same way the Gradle suites do.
gradle_arguments=()
if [[ -n "${OKHTTP_VERSION:-}" ]]; then
  gradle_arguments+=("-PokhttpVersion=$OKHTTP_VERSION")
fi

ECH_FIXTURE_ENDPOINT_FILE="$endpoint_file" \
  "$repository_root/gradlew" -p "$repository_root" :ech-fixture:runEchFixture "${gradle_arguments[@]}" \
  >"$service_log" 2>&1 &
service_pid=$!

cleanup() {
  adb reverse --remove tcp:8053 >/dev/null 2>&1 || true
  adb reverse --remove tcp:443 >/dev/null 2>&1 || true
  adb reverse --remove tcp:8443 >/dev/null 2>&1 || true
  # Deleting the endpoint file is how the fixture is asked to stop.
  rm -f "$endpoint_file"
  for _ in {1..200}; do
    if ! kill -0 "$service_pid" 2>/dev/null; then
      break
    fi
    sleep 0.1
  done
  kill "$service_pid" >/dev/null 2>&1 || true
  wait "$service_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT

startup_deadline=$((SECONDS + startup_timeout_seconds))
while ((SECONDS < startup_deadline)); do
  if [[ -s "$endpoint_file" ]]; then
    break
  fi
  if ! kill -0 "$service_pid" 2>/dev/null; then
    cat "$service_log" >&2
    exit 1
  fi
  sleep 1
done

if [[ ! -s "$endpoint_file" ]]; then
  cat "$service_log" >&2
  echo "Timed out waiting for the ECH fixture" >&2
  exit 1
fi

property() {
  sed -n "s/^$1=//p" "$endpoint_file"
}

doh_host_port="$(property DOH_HOST_PORT)"
target_host_port="$(property TARGET_HOST_PORT)"
ca_certificate="$(property CA_CERT)"
if [[ ! "$doh_host_port" =~ ^[0-9]+$ || ! "$target_host_port" =~ ^[0-9]+$ || -z "$ca_certificate" ]]; then
  cat "$service_log" >&2
  echo "Invalid ECH fixture metadata" >&2
  exit 1
fi

if [[ "$mode" == "--smoke-only" ]]; then
  exit 0
fi

# Wait for the device to be ready to install a package, which is not the same thing as
# booted.
#
# `sys.boot_completed` flips to 1 while the system services are still registering, and
# `android-emulator-runner` hands the script the device at that point. Installing into that
# window fails the run outright:
#
#   Failed to install split APK(s): android-ech-debug-androidTest.apk
#   java.lang.IllegalStateException: Cannot access system provider: 'settings' before
#   system providers are installed!
#   [cmd: Can't find service: package]  →  Starting 0 tests on emulator-5554
#
# Zero tests run, so Gradle finds no results XML and fails reading it — which reads as the
# ECH suite breaking rather than as the emulator not being up yet.
#
# External storage arrives later still, and a run that starts without it dies differently:
#
#   mkdir: '/sdcard/Android': Transport endpoint is not connected
#   Instrumentation run failed due to Process crashed.
#
# So gate on what the run actually needs — the services that perform the install, and the
# storage it writes to — rather than on the boot flag.
wait_for_device_ready() {
  local deadline=$((SECONDS + ${ANDROID_READY_TIMEOUT_SECONDS:-300}))
  local property

  adb wait-for-device

  while ((SECONDS < deadline)); do
    property="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')"
    if [[ "$property" == "1" ]] &&
      # `service check` asks the service manager whether the binder is registered. Both are
      # named in the failure above: `package` performs the install, and the settings
      # provider is what `install-create` reads before choosing a volume.
      [[ "$(adb shell service check package 2>/dev/null | tr -d '\r\n')" == *"found"* ]] &&
      [[ "$(adb shell service check settings 2>/dev/null | tr -d '\r\n')" == *"found"* ]] &&
      # Registered is not the same as answering. One real call through the package manager
      # is the only thing that proves the install path works.
      adb shell pm path android >/dev/null 2>&1 &&
      # External storage is mounted through FUSE and arrives after the services do. AGP
      # creates its additional-test-output directory under /sdcard before the run, and on a
      # half-mounted device that fails with "Transport endpoint is not connected" — which
      # takes the instrumentation with it.
      adb shell test -d /sdcard/Android >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for the emulator to be ready to install and run tests" >&2
  echo "sys.boot_completed=$(adb shell getprop sys.boot_completed 2>&1 | tr -d '\r\n')" >&2
  echo "service check package: $(adb shell service check package 2>&1 | tr -d '\r\n')" >&2
  echo "service check settings: $(adb shell service check settings 2>&1 | tr -d '\r\n')" >&2
  echo "ls /sdcard: $(adb shell ls -d /sdcard/Android 2>&1 | tr -d '\r\n')" >&2
  return 1
}

wait_for_device_ready

# 8053 is the resolver. The origin is reached on 8443, the port the HTTPS record publishes,
# and on 443 for the default the URL would otherwise use.
adb reverse tcp:8053 "tcp:$doh_host_port"
adb reverse tcp:443 "tcp:$target_host_port"
adb reverse tcp:8443 "tcp:$target_host_port"

# Boot completion does not mean that the emulator has installed an outbound route. The public
# suite resolves through Cloudflare's DoH endpoint, so wait for the device itself to reach that
# address before turning one missing route into the same failure in every public test case.
#
# This deliberately probes an IP address: DNS is the thing the public suite is trying to test.
wait_for_public_network() {
  local deadline=$((SECONDS + ${ANDROID_NETWORK_TIMEOUT_SECONDS:-60}))

  while ((SECONDS < deadline)); do
    if adb shell ping -c 1 -W 2 1.1.1.1 >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  echo "The emulator did not acquire an outbound route to 1.1.1.1; skipping the public ECH suite." >&2
  echo "Device routes:" >&2
  adb shell ip route show >&2 || true
  return 1
}

# One instrumentation run per suite, because the two report differently and Gradle writes both
# to the same place. `results_dir` is moved aside after each run so the workflow can upload them
# together; without that the second run would overwrite the first.
results_dir="$repository_root/android-ech/build/outputs/androidTest-results/connected"

run_suite() {
  local class="$1"
  shift
  local status=0
  local report="$repository_root/android-ech/build/test-results/ech-$class.json"

  rm -rf "$results_dir"
  mkdir -p "$(dirname "$report")"
  adb shell run-as okhttp.testbed.android.ech.test rm -f files/ech-results.json >/dev/null 2>&1 || true
  # `|| status=$?` rather than a bare call: this runs under `set -e`, and a failing suite whose
  # results were never moved aside is a failing suite nobody can read.
  "$repository_root/gradlew" -p "$repository_root" :android-ech:connectedDebugAndroidTest \
    "${gradle_arguments[@]}" \
    -Pandroid.testInstrumentationRunnerArguments.class="okhttp.testbed.android.ech.$class" \
    "$@" || status=$?

  # A run that recorded no test case didn't fail its assertions — it never got as far as making
  # them, and a suite that reported nothing is worse than a slow job: on the status page it is
  # indistinguishable from one that had nothing to say. `wait_for_device_ready` above is what
  # stops that happening; this is the backstop for when it doesn't.
  #
  # The question is about test cases, not about the directory. An install that never ran still
  # leaves the results tree behind, empty — so gating this on the directory's absence meant it
  # never fired on the run it was written for.
  if [ "$status" -ne 0 ] && ! grep -rqs '<testcase' "$results_dir"; then
    echo "$class produced no results; retrying once." >&2
    status=0
    "$repository_root/gradlew" -p "$repository_root" :android-ech:connectedDebugAndroidTest \
      "${gradle_arguments[@]}" \
      -Pandroid.testInstrumentationRunnerArguments.class="okhttp.testbed.android.ech.$class" \
      "$@" || status=$?
  fi

  if [ -d "$results_dir" ]; then
    rm -rf "$results_dir-$class"
    mv "$results_dir" "$results_dir-$class"
  fi
  if ! adb exec-out run-as okhttp.testbed.android.ech.test cat files/ech-results.json >"$report"; then
    rm -f "$report"
  fi
  return $status
}

# The public servers first, and not allowed to fail the run. tls-ech.dev, defo.ie and
# cloudflare-ech.com belong to other people; an outage there is not a result about OkHttp, and
# the JVM `network` suites treat the same servers the same way. The XML still records what
# happened, which is what the status page reads.
public_status=0
public_arguments=()
device_api_level="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')"
if [[ "$device_api_level" =~ ^[0-9]+$ ]] && ((device_api_level >= 37)) &&
  ! wait_for_public_network; then
  public_arguments+=("-Pandroid.testInstrumentationRunnerArguments.publicNetworkAvailable=false")
fi
run_suite PublicEncryptedClientHelloTest "${public_arguments[@]}" || public_status=$?
if [ "$public_status" -ne 0 ]; then
  echo "PublicEncryptedClientHelloTest failed; recorded, not fatal." >&2
fi

# Certificate Transparency is exercised against the local TLS fixture, not a public test site:
# `no-sct.badssl.com` has repeatedly expired, and accepting its generic certificate failure as a
# CT result creates a false positive. This suite gates because both the server and its CA are ours.
run_suite CertificateTransparencyTest \
  -Pandroid.testInstrumentationRunnerArguments.ct=true \
  -Pandroid.testInstrumentationRunnerArguments.caCertificate="$ca_certificate"

# The fixture suite does gate: it runs against containers this repository starts, so a failure
# is about OkHttp or about this repository, and there is nobody else to blame for it.
run_suite EncryptedClientHelloTest \
  -Pandroid.testInstrumentationRunnerArguments.ech=true \
  -Pandroid.testInstrumentationRunnerArguments.dohPort=8053 \
  -Pandroid.testInstrumentationRunnerArguments.caCertificate="$ca_certificate"
