#!/usr/bin/env python3
"""Minimal Maven resolver for the Jarvis manual build.
Two phases: (1) walk POMs to settle each artifact at its max version
(Gradle-style conflict resolution); (2) re-walk from the roots at the
settled versions to get the final artifact set, then download AAR/JARs
into android/libs/. Fetches via curl through the sandbox egress proxy.
"""
import os, re, subprocess, sys, xml.etree.ElementTree as ET

PROXY = os.environ.get("HTTPS_PROXY", "")
GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
OUT = os.path.dirname(os.path.abspath(__file__))
NS = "{http://maven.apache.org/POM/4.0.0}"

# Roots = the exact dependencies in android/app/build.gradle.kts, plus
# lifecycle-runtime-ktx (MainActivity uses lifecycleScope) and
# onnxruntime-android (WakeModel imports ai.onnxruntime.*).
ROOTS = [
    ("androidx.core", "core-ktx", "1.13.1", GOOGLE_MAVEN),
    ("androidx.appcompat", "appcompat", "1.7.0", GOOGLE_MAVEN),
    ("androidx.webkit", "webkit", "1.12.1", GOOGLE_MAVEN),
    ("androidx.security", "security-crypto", "1.1.0-alpha06", GOOGLE_MAVEN),
    ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.8.7", GOOGLE_MAVEN),
    ("com.microsoft.onnxruntime", "onnxruntime-android", "1.20.0", CENTRAL),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.8.1", CENTRAL),
]
PINNED = {
    ("androidx.annotation", "annotation"): "1.7.0",
    ("org.jetbrains", "annotations"): "13.0",
    ("com.google.crypto.tink", "tink-android"): "1.9.0",
    ("com.google.protobuf", "protobuf-javalite"): "3.25.1",
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core"): "1.8.1",
}
# Artifacts whose POM is real (walk its deps) but which have no artifact
# file of their own (aggregator/relocation POMs).
SKIP_ARTIFACT = {("androidx.lifecycle", "lifecycle-runtime-ktx")}
# kotlin-stdlib comes with the kotlinc distribution; never download it.
SKIP_DOWNLOAD = {("org.jetbrains.kotlin", "kotlin-stdlib")}

_pom_cache = {}

def curl_code_body(url):
    r = subprocess.run(
        ["curl", "-sSL", "--proxy", PROXY, "-w", "\n%{http_code}", url],
        capture_output=True,
    )
    if r.returncode != 0:
        raise RuntimeError(f"curl {url} rc={r.returncode}: {r.stderr.decode()[:200]}")
    body, _, code = r.stdout.rpartition(b"\n")
    return code.decode().strip(), body

def gav_path(g, a, v, ext):
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"

def get_pom(g, a, v, repo):
    key = (g, a, v)
    if key in _pom_cache:
        return _pom_cache[key]
    last = None
    for r in [repo, CENTRAL if repo == GOOGLE_MAVEN else GOOGLE_MAVEN]:
        url = f"{r}/{gav_path(g, a, v, 'pom')}"
        try:
            code, data = curl_code_body(url)
        except Exception as e:
            last = e
            continue
        if code == "200" and (data.startswith(b"<?xml") or data.lstrip().startswith(b"<project")):
            root = ET.fromstring(data)
            _pom_cache[key] = root
            return root
        last = RuntimeError(f"POM {g}:{a}:{v} from {r}: HTTP {code}")
    raise last

def props_of(root):
    d = {}
    p = root.find(f"{NS}properties")
    if p is not None:
        for ch in p:
            d[ch.tag.replace(NS, "")] = ch.text
    return d

def subst(s, propsd):
    if s is None:
        return None
    def repl(m):
        return propsd.get(m.group(1), m.group(0))
    return re.sub(r"\$\{([^}]+)\}", repl, s)

def ver_tuple(v):
    parts = []
    for p in re.split(r"[.\-+]", v):
        parts.append(int(p) if p.isdigit() else 0)
    return tuple(parts)

def max_ver(a, b):
    if a is None:
        return b
    if b is None:
        return a
    return a if ver_tuple(a) >= ver_tuple(b) else b

def norm_version(v, propsd):
    """Resolve a declared version: property substitution + [X] ranges."""
    v = subst(v, propsd)
    if v is None:
        return None
    m = re.fullmatch(r"\[([^\],]+)\]", v.strip())
    if m:
        return m.group(1).strip()
    if v.startswith("[") or "," in v:
        return None  # real range: caller decides (pinned or skip)
    return v

def read_deps(g, a, v, repo):
    """Return [(group, artifact, version, repo)] compile/runtime deps of a POM."""
    root = get_pom(g, a, v, repo)
    pr = props_of(root)
    pr["project.version"] = v
    dm = {}
    dm_el = root.find(f"{NS}dependencyManagement/{NS}dependencies")
    if dm_el is not None:
        for dep in dm_el.findall(f"{NS}dependency"):
            dg = dep.findtext(f"{NS}groupId")
            da = dep.findtext(f"{NS}artifactId")
            dv = norm_version(dep.findtext(f"{NS}version"), pr)
            if dg and da and dv:
                dm[(dg, da)] = dv
    parent = root.find(f"{NS}parent")
    if parent is not None:
        pg = parent.findtext(f"{NS}groupId")
        pa = parent.findtext(f"{NS}artifactId")
        pv = norm_version(parent.findtext(f"{NS}version"), pr)
        if pg and pa and pv:
            try:
                proot = get_pom(pg, pa, pv, repo)
                ppr = props_of(proot)
                for k, val in ppr.items():
                    pr.setdefault(k, val)
                pdm = proot.find(f"{NS}dependencyManagement/{NS}dependencies")
                if pdm is not None:
                    for dep in pdm.findall(f"{NS}dependency"):
                        dg = dep.findtext(f"{NS}groupId")
                        da = dep.findtext(f"{NS}artifactId")
                        dv = norm_version(dep.findtext(f"{NS}version"), pr)
                        if dg and da and dv:
                            dm.setdefault((dg, da), dv)
            except Exception as e:
                print(f"  (parent {pg}:{pa}:{pv} unreadable: {e})", file=sys.stderr)
    out = []
    deps_el = root.find(f"{NS}dependencies")
    if deps_el is not None:
        for dep in deps_el.findall(f"{NS}dependency"):
            scope = dep.findtext(f"{NS}scope") or "compile"
            if scope not in ("compile", "runtime"):
                continue
            if dep.findtext(f"{NS}optional") == "true":
                continue
            dg = dep.findtext(f"{NS}groupId")
            da = dep.findtext(f"{NS}artifactId")
            if not dg or not da:
                continue
            dv = dep.findtext(f"{NS}version")
            dv = norm_version(dv, pr) if dv else dm.get((dg, da))
            if not dv:
                if (dg, da) in PINNED:
                    dv = PINNED[(dg, da)]
                else:
                    print(f"  WARN no version for {dg}:{da} (from {g}:{a})", file=sys.stderr)
                    continue
            drepo = GOOGLE_MAVEN if (dg.startswith("androidx.") or dg.startswith("com.google")) else CENTRAL
            out.append((dg, da, dv, drepo))
    return out

def main():
    versions = {}
    for g, a, v, _ in ROOTS:
        versions[(g, a)] = v
    # Phase 1: settle max versions.
    visiting = set()
    def settle(g, a, v, repo):
        key = (g, a)
        if key in PINNED:
            v = PINNED[key]
        versions[key] = max_ver(versions.get(key), v)
        if key in SKIP_DOWNLOAD or key in visiting:
            return
        visiting.add(key)
        try:
            for dg, da, dv, drepo in read_deps(g, a, v, repo):
                settle(dg, da, dv, drepo)
        except Exception as e:
            print(f"  WARN {g}:{a}:{v}: {e}", file=sys.stderr)
        visiting.discard(key)
    for g, a, v, repo in ROOTS:
        settle(g, a, v, repo)
    # Phase 2: final artifact set at settled versions.
    final = []
    seen2 = set()
    def collect2(g, a, repo):
        key = (g, a)
        if key in seen2 or key in SKIP_DOWNLOAD:
            return
        seen2.add(key)
        v = versions[key]
        try:
            deps = read_deps(g, a, v, repo)
        except Exception as e:
            print(f"  WARN {g}:{a}:{v}: {e}", file=sys.stderr)
            deps = []
        for dg, da, _, drepo in deps:
            collect2(dg, da, drepo)
        final.append((g, a, v, repo))
    for g, a, _, repo in ROOTS:
        collect2(g, a, repo)
    print("resolved (leaf-first):")
    for g, a, v, _ in final:
        print(f"  {g}:{a}:{v}")
    with open(os.path.join(OUT, "resolved.txt"), "w") as mf:
        for g, a, v, _ in final:
            mf.write(f"{g}:{a}:{v}\n")
    for g, a, v, repo in final:
        if (g, a) in SKIP_ARTIFACT:
            print(f"  skip artifact {g}:{a} (aggregator POM only)")
            continue
        fetched = False
        for ext in ("aar", "jar"):
            out = os.path.join(OUT, f"{a}-{v}.{ext}")
            if os.path.exists(out) and os.path.getsize(out) > 1000:
                print(f"  cached {out}")
                fetched = True
                break
            for r in [repo, CENTRAL if repo == GOOGLE_MAVEN else GOOGLE_MAVEN]:
                url = f"{r}/{gav_path(g, a, v, ext)}"
                rr = subprocess.run(
                    ["curl", "-sSL", "--proxy", PROXY, "-o", out, "-w", "%{http_code}", url],
                    capture_output=True,
                )
                code = rr.stdout.decode().strip()
                if code == "200" and os.path.exists(out) and os.path.getsize(out) > 1000:
                    print(f"  downloaded {out} ({os.path.getsize(out)} bytes)")
                    fetched = True
                    break
                if os.path.exists(out):
                    os.remove(out)
            if fetched:
                break
        if not fetched:
            print(f"  ERROR could not fetch {g}:{a}:{v} as aar or jar", file=sys.stderr)
            sys.exit(1)
    print("OK")

if __name__ == "__main__":
    main()
