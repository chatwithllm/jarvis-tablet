#!/usr/bin/env python3
"""Manual (no-Gradle) debug build of the Jarvis tablet APK.

Why this exists: the Gradle daemon cannot run in the cloud sandbox —
the sandbox network filter blocks loopback TCP data from Java processes,
so the Gradle client can never talk to its daemon ("Could not receive a
message from the daemon"). CI (GitHub Actions) still builds with Gradle;
this script is the sandbox equivalent, using the SDK command-line tools
directly: aapt2 (resources + manifest), kotlinc (Kotlin/Java sources),
d8 (dex), zipalign + apksigner (debug signing only — never a release
keystore; the release keystore lives on the user's Mac Mini).

Usage:  python3 android/build-manual.py
Output: android/build/jarvis-tablet-debug.apk
Requires: ANDROID_HOME (or ~/workspace/.android-sdk), JAVA_HOME
(~/workspace/.jdk17), kotlinc at ~/workspace/.kotlin/kotlinc,
and android/libs/resolved.txt (written by android/libs/resolve.py).

Dexing uses R8 8.5.35 (the version AGP 8.5.2 ships), NOT the d8 bundled
with build-tools 34.0.0: that d8 is 8.2.2-dev and crashes with an
internal NullPointerException on appcompat classes (reproduced on
appcompat-resources/classes.jar alone; R8 8.5.35 dexes the same input
cleanly). The jar is fetched once from Google Maven into
~/workspace/.downloads/ if it is not already there.
"""
import os
import re
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "android", "app")
LIBS = os.path.join(ROOT, "android", "libs")
BUILD = os.path.join(ROOT, "android", "build")
MANUAL = os.path.join(BUILD, "manual")

SDK = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/workspace/.android-sdk")
JAVA_HOME = os.environ.get("JAVA_HOME") or os.path.expanduser("~/workspace/.jdk17")
KOTLINC_HOME = os.environ.get("KOTLINC_HOME") or os.path.expanduser("~/workspace/.kotlin/kotlinc")

PACKAGE = "net.npalakurla.jarvis.tablet"
VERSION_CODE = "1"
VERSION_NAME = "0.1.0-phase1"
MIN_SDK = "30"
TARGET_SDK = "34"

# Artifacts with a real POM but no artifact file (aggregator/relocation
# POMs) — resolved.txt lists them, but there is nothing to compile/link.
SKIP_NO_FILE = {("androidx.lifecycle", "lifecycle-runtime-ktx")}

# Artifacts that must NOT go into the dex: metadata-only jars (no JVM
# classes), or jars superseded by the kotlinc-bundled stdlib.
DEX_EXCLUDE = {
    "kotlin-stdlib-common-1.8.20.jar",
    "kotlinx-coroutines-core-1.8.1.jar",   # multiplatform metadata only
    "atomicfu-0.17.0.jar",                 # multiplatform metadata only
    "annotation-1.7.0.jar",                # 0 classes; real ones are in annotation-jvm
    "lifecycle-common-2.8.7.jar",          # 0 classes; real ones are in lifecycle-common-jvm
    "lifecycle-runtime-2.8.7.jar",         # see note below
}
# lifecycle-runtime-2.8.7.jar: 0 classes (multiplatform metadata only;
# the real classes are in lifecycle-runtime-android-2.8.7.aar).
# NOTE: it is still on the kotlinc COMPILE classpath (harmless there).


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd))
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        raise SystemExit(f"FAILED ({r.returncode}): {' '.join(str(c) for c in cmd)}")
    return r


def tool(name):
    p = os.path.join(SDK, "build-tools", "34.0.0", name)
    if not os.path.exists(p):
        raise SystemExit(f"missing SDK tool: {p}")
    return p


def aar_info(aar_path):
    """Return (package, has_res, has_jni, classes_jar_bytes|None)."""
    z = zipfile.ZipFile(aar_path)
    names = z.namelist()
    pkg = None
    if "AndroidManifest.xml" in names:
        try:
            root = ET.fromstring(z.read("AndroidManifest.xml"))
            pkg = root.get("package")
        except Exception:
            pass
    return {
        "package": pkg,
        "has_res": any(n.startswith("res/") and not n.endswith("/") for n in names),
        "has_jni": any(n.startswith("jni/") and n.endswith(".so") for n in names),
        "has_classes": "classes.jar" in names,
    }


def r8_jar():
    """R8 8.5.35 jar (AGP 8.5.2's dexer). Downloads once if missing.

    Hardened after the Mini fallback build failed to download it (issue
    #2): the download directory is created first (on the Mini,
    ~/workspace/.downloads did not exist, so curl could not write the
    file), the download is retried, and the jar is validated as a real
    zip containing the D8 entry point — a truncated download must never
    be used (a bad R8 jar was the prime suspect for the missing
    androidx.appcompat.R$drawable crash in issue #3).
    """
    p = os.environ.get("R8_JAR") or os.path.expanduser(
        "~/workspace/.downloads/r8-8.5.35.jar")
    if _valid_r8_jar(p):
        return p
    url = ("https://dl.google.com/dl/android/maven2/com/android/tools/r8/"
           "8.5.35/r8-8.5.35.jar")
    print(f"downloading R8 8.5.35 from {url} ...")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    proxy = os.environ.get("HTTPS_PROXY", "")
    cmd = ["curl", "-sSLf", "--retry", "3", "--retry-all-errors",
           "--retry-delay", "5"]
    if proxy:
        cmd += ["--proxy", proxy]
    cmd += ["-o", p, url]
    r = subprocess.run(cmd)
    if r.returncode != 0 or not _valid_r8_jar(p):
        if os.path.exists(p):
            os.remove(p)
        raise SystemExit(
            "could not download a valid R8 8.5.35 jar; set R8_JAR to a "
            "local copy of https://dl.google.com/dl/android/maven2/"
            "com/android/tools/r8/8.5.35/r8-8.5.35.jar")
    return p


def _valid_r8_jar(p):
    """True if p is a real R8 jar (valid zip, contains the D8 entry)."""
    if not p or not os.path.exists(p) or os.path.getsize(p) < 1_000_000:
        return False
    try:
        with zipfile.ZipFile(p) as z:
            return "com/android/tools/r8/D8.class" in z.namelist()
    except zipfile.BadZipFile:
        return False


def main():
    java_bin = os.path.join(JAVA_HOME, "bin", "java")
    kotlinc = os.path.join(KOTLINC_HOME, "bin", "kotlinc")
    for p, what in [(java_bin, "java"), (kotlinc, "kotlinc"),
                    (os.path.join(SDK, "platforms", "android-34", "android.jar"), "android.jar")]:
        if not os.path.exists(p):
            raise SystemExit(f"missing {what}: {p}")
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    env["PATH"] = os.path.join(JAVA_HOME, "bin") + ":" + env.get("PATH", "")

    resolved = []
    with open(os.path.join(LIBS, "resolved.txt")) as f:
        for line in f:
            line = line.strip()
            if line:
                g, a, v = line.split(":")
                resolved.append((g, a, v))
    print(f"resolved artifacts: {len(resolved)}")

    if os.path.exists(MANUAL):
        shutil.rmtree(MANUAL)
    os.makedirs(os.path.join(MANUAL, "aar"), exist_ok=True)
    os.makedirs(os.path.join(MANUAL, "compiled"), exist_ok=True)
    os.makedirs(os.path.join(MANUAL, "gen"), exist_ok=True)
    os.makedirs(os.path.join(MANUAL, "classes"), exist_ok=True)
    os.makedirs(os.path.join(MANUAL, "dex"), exist_ok=True)

    aapt2 = tool("aapt2")
    zipalign = tool("zipalign")
    apksigner = tool("apksigner")
    keytool = os.path.join(JAVA_HOME, "bin", "keytool")
    r8 = r8_jar()
    android_jar = os.path.join(SDK, "platforms", "android-34", "android.jar")

    # -- 1. extract AARs ------------------------------------------------
    dep_classes_jars = []   # for kotlinc/d8 classpaths
    dep_plain_jars = []
    flat_zips = []
    extra_packages = []
    jni_libs = []           # (abi, path) for the APK lib/ dir
    for g, a, v in resolved:
        if (g, a) in SKIP_NO_FILE:
            print(f"skip {g}:{a}:{v} (aggregator POM only, no artifact file)")
            continue
        aar = os.path.join(LIBS, f"{a}-{v}.aar")
        jar = os.path.join(LIBS, f"{a}-{v}.jar")
        if os.path.exists(aar):
            info = aar_info(aar)
            outdir = os.path.join(MANUAL, "aar", a)
            with zipfile.ZipFile(aar) as z:
                z.extractall(outdir)
            if info["has_classes"]:
                dep_classes_jars.append(os.path.join(outdir, "classes.jar"))
            if info["has_res"]:
                flat = os.path.join(MANUAL, "compiled", f"{a}.flat.zip")
                run([aapt2, "compile", "--dir", os.path.join(outdir, "res"),
                     "-o", flat], env=env)
                flat_zips.append(flat)
            if info["package"]:
                extra_packages.append(info["package"])
            if info["has_jni"]:
                for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
                    jdir = os.path.join(outdir, "jni", abi)
                    if os.path.isdir(jdir):
                        for so in sorted(os.listdir(jdir)):
                            jni_libs.append((abi, os.path.join(jdir, so)))
        elif os.path.exists(jar):
            dep_plain_jars.append(jar)
        else:
            raise SystemExit(f"artifact file missing for {g}:{a}:{v}")

    # -- 2. compile the app's own resources ------------------------------
    app_flat = os.path.join(MANUAL, "compiled", "app.flat.zip")
    run([aapt2, "compile", "--dir", os.path.join(APP, "src", "main", "res"),
         "-o", app_flat], env=env)

    # -- 3. manifest with the package attribute (Gradle supplies it via
    #    namespace; aapt2 needs it in the manifest) -----------------------
    manifest_src = os.path.join(APP, "src", "main", "AndroidManifest.xml")
    manifest = os.path.join(MANUAL, "AndroidManifest.xml")
    with open(manifest_src) as f:
        text = f.read()
    text = text.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
        f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="{PACKAGE}">',
        1)
    with open(manifest, "w") as f:
        f.write(text)

    # -- 4. BuildConfig (AGP generates this; the manual build writes it) --
    bc_dir = os.path.join(MANUAL, "gen", *PACKAGE.split("."))
    os.makedirs(bc_dir, exist_ok=True)
    with open(os.path.join(bc_dir, "BuildConfig.java"), "w") as f:
        f.write(
            f"package {PACKAGE};\n"
            "public final class BuildConfig {\n"
            "  public static final boolean DEBUG = true;\n"
            f"  public static final String APPLICATION_ID = \"{PACKAGE}\";\n"
            "  public static final String BUILD_TYPE = \"debug\";\n"
            f"  public static final int VERSION_CODE = {VERSION_CODE};\n"
            f"  public static final String VERSION_NAME = \"{VERSION_NAME}\";\n"
            "}\n")

    # -- 5. aapt2 link ----------------------------------------------------
    base_apk = os.path.join(MANUAL, "base.apk")
    link_cmd = [
        aapt2, "link", "-o", base_apk,
        "--manifest", manifest,
        "-I", android_jar,
        "--java", os.path.join(MANUAL, "gen"),
        "--auto-add-overlay",
        "--min-sdk-version", MIN_SDK,
        "--target-sdk-version", TARGET_SDK,
        "--version-code", VERSION_CODE,
        "--version-name", VERSION_NAME,
        "-R", app_flat,
    ]
    for flat in flat_zips:
        link_cmd += ["-R", flat]
    for pkg in extra_packages:
        link_cmd += ["--extra-packages", pkg]
    run(link_cmd, env=env)

    # -- 6. kotlinc --------------------------------------------------------
    kt_sources = []
    src_root = os.path.join(APP, "src", "main", "java")
    for dp, _, fns in os.walk(src_root):
        for fn in sorted(fns):
            if fn.endswith(".kt"):
                kt_sources.append(os.path.join(dp, fn))
    gen_java = []
    for dp, _, fns in os.walk(os.path.join(MANUAL, "gen")):
        for fn in sorted(fns):
            if fn.endswith(".java"):
                gen_java.append(os.path.join(dp, fn))
    print(f"kotlin sources: {len(kt_sources)}; generated java: {len(gen_java)} "
          f"({[os.path.basename(p) for p in gen_java][:6]}...)")
    compile_cp = [android_jar] + dep_classes_jars + dep_plain_jars
    run([
        kotlinc, "-jvm-target", "17", "-d", os.path.join(MANUAL, "classes"),
        "-cp", ":".join(compile_cp),
        *kt_sources, *gen_java,
    ], env=env)

    # -- 7. d8 --------------------------------------------------------------
    # d8 rejects a raw classes directory (it chokes on kotlinc's
    # META-INF/*.kotlin_module files), so jar the compiled classes first —
    # inside a jar, d8 ignores non-class entries.
    classes_jar = os.path.join(MANUAL, "classes.jar")
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, _, fns in os.walk(os.path.join(MANUAL, "classes")):
            for fn in sorted(fns):
                full = os.path.join(dp, fn)
                rel = os.path.relpath(full, os.path.join(MANUAL, "classes"))
                z.write(full, rel)
    stdlib_jars = [
        os.path.join(KOTLINC_HOME, "lib", "kotlin-stdlib.jar"),
        os.path.join(KOTLINC_HOME, "lib", "kotlin-stdlib-jdk7.jar"),
        os.path.join(KOTLINC_HOME, "lib", "kotlin-stdlib-jdk8.jar"),
    ]
    dex_inputs = [classes_jar] + stdlib_jars
    for p in dep_classes_jars + dep_plain_jars:
        if os.path.basename(p) not in DEX_EXCLUDE:
            dex_inputs.append(p)
    run([
        java_bin, "-Xmx1024m", "-cp", r8, "com.android.tools.r8.D8",
        "--min-api", MIN_SDK,
        "--lib", android_jar,
        "--output", os.path.join(MANUAL, "dex"),
        *dex_inputs,
    ], env=env)
    dex_files = sorted(
        os.path.join(MANUAL, "dex", f)
        for f in os.listdir(os.path.join(MANUAL, "dex"))
        if f.endswith(".dex"))
    print(f"dex files: {[os.path.basename(p) for p in dex_files]}")
    if not dex_files:
        raise SystemExit("d8 produced no dex files")

    # -- 7b. dex verification ---------------------------------------------
    # Fail loudly if the dex is missing a class the app needs at launch.
    # The Mini-built APK crashed on launch with NoClassDefFoundError for
    # androidx.appcompat.R$drawable (issue #3) — a bad R8 jar or a bad
    # dex step must never again ship as a silent broken APK.
    required_dex_classes = [
        "Landroidx/appcompat/R$drawable;",
        "Lnet/npalakurla/jarvis/tablet/MainActivity;",
        "Landroidx/appcompat/app/AppCompatActivity;",
        "Landroidx/webkit/WebViewCompat;",
        "Lai/onnxruntime/OrtEnvironment;",
    ]
    dex_bytes = b"".join(open(f, "rb").read() for f in dex_files)
    missing = [c for c in required_dex_classes if c.encode() not in dex_bytes]
    if missing:
        raise SystemExit("dex verification failed — missing classes: "
                         + ", ".join(missing))
    print("dex verification: all required classes present")

    # -- 8. assemble the APK -------------------------------------------------
    apk = os.path.join(BUILD, "jarvis-tablet-debug.apk")
    unsigned = os.path.join(MANUAL, "unsigned.apk")
    shutil.copyfile(base_apk, unsigned)
    # dex at the APK root
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for dex in dex_files:
            z.write(dex, os.path.basename(dex))
    # bundled HUD assets (src/main/assets/**)
    assets_root = os.path.join(APP, "src", "main", "assets")
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for dp, _, fns in os.walk(assets_root):
            for fn in sorted(fns):
                full = os.path.join(dp, fn)
                rel = os.path.relpath(full, assets_root)
                z.write(full, "assets/" + rel)
    # native libs (onnxruntime .so files)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for abi, so_path in jni_libs:
            z.write(so_path, f"lib/{abi}/{os.path.basename(so_path)}")
    aligned = os.path.join(MANUAL, "aligned.apk")
    run([zipalign, "-f", "-p", "4", unsigned, aligned], env=env)

    # debug keystore only (never a release keystore — that lives on the Mini).
    # It lives in android/build/ (gitignored), NOT under manual/ — manual/
    # is wiped on every rebuild, and a fresh key per build would make
    # `adb install -r` fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
    ks = os.path.join(BUILD, "debug.keystore")
    if not os.path.exists(ks):
        run([keytool, "-genkeypair", "-keystore", ks, "-alias", "androiddebugkey",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", "android", "-keypass", "android",
             "-dname", "CN=Android Debug,O=Android,C=US"], env=env)
    if os.path.exists(apk):
        os.remove(apk)
    run([apksigner, "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--out", apk, aligned], env=env)

    # -- 9. verify ------------------------------------------------------------
    run([apksigner, "verify", "--print-certs", apk], env=env)
    r = run([aapt2, "dump", "badging", apk], env=env, capture_output=True, text=True)
    print(r.stdout)
    print(f"BUILT {apk} ({os.path.getsize(apk)} bytes)")


if __name__ == "__main__":
    main()
