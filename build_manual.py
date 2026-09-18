"""Manual build for 干掉HyperOS4官改非官方内容 (no Gradle needed).
Uses local Android SDK (aapt2/d8/zipalign/apksigner) + libxposed AAR from Maven.
Run: python build_manual.py
Output: out/BanModified-1-1.0.apk (release-signed with self-created key)
Key: out/release.keystore (self-created, BACK IT UP, never upload to git).
"""
import os
import pathlib
import secrets
import shutil
import string
import subprocess
import sys
import zipfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
APP = ROOT / "app" / "src" / "main"
OUT = ROOT / "out"
SDK = pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
BT = SDK / "build-tools" / "37.0.0"
PLATFORM_JAR = SDK / "platforms" / "android-34" / "android.jar"
if not PLATFORM_JAR.exists():  # fallback to 37
    PLATFORM_JAR = SDK / "platforms" / "android-37" / "android.jar"
AAR_URL = "https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar"
AAR = OUT / "libxposed.aar"
API_JAR = OUT / "libxposed-api.jar"

PKG = "io.github.yuhj319.banmodifiedsecurityservice"
VERSION_CODE = "1"
VERSION_NAME = "1.0"
APK_NAME = f"BanModified-{VERSION_CODE}-{VERSION_NAME}.apk"

KEYSTORE = OUT / "release.keystore"
KEY_INFO = OUT / "keystore-info.txt"
KEY_ALIAS = "banmodified"


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-4000:])
        print(r.stderr[-4000:])
        raise SystemExit(f"FAILED: {cmd[0]} exit={r.returncode}")
    return r


def ensure_api():
    OUT.mkdir(parents=True, exist_ok=True)
    if not API_JAR.exists():
        if not AAR.exists():
            print(f"downloading {AAR_URL} ...")
            urllib.request.urlretrieve(AAR_URL, AAR)
        with zipfile.ZipFile(AAR) as z:
            with z.open("classes.jar") as src, open(API_JAR, "wb") as dst:
                shutil.copyfileobj(src, dst)
    print("api jar:", API_JAR.stat().st_size, "bytes")


def load_or_create_key():
    """Self-created release key (single password, like a normal release key).
    Password kept in keystore-info.txt (gitignored)."""
    if KEYSTORE.exists() and KEY_INFO.exists():
        info = {}
        for line in KEY_INFO.read_text(encoding="utf-8").splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                info[k.strip()] = v.strip()
        if info.get("storepass"):
            print("using existing release key:", KEYSTORE.name)
            return info["storepass"], info.get("alias", KEY_ALIAS)
    alphabet = string.ascii_letters + string.digits
    password = "".join(secrets.choice(alphabet) for _ in range(16))
    if KEYSTORE.exists():
        KEYSTORE.unlink()
    run(["keytool", "-genkeypair", "-keystore", str(KEYSTORE),
         "-alias", KEY_ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
         "-storepass", password, "-keypass", password,
         "-dname", "CN=yuhj319, OU=BanModified, O=yuhj319, C=CN"])
    KEY_INFO.write_text(
        f"alias={KEY_ALIAS}\nstorepass={password}\n"
        f"# 自创签名，仅此一份。备份 release.keystore + 本文件，勿上传到 git。\n",
        encoding="utf-8")
    print("created new release key:", KEYSTORE.name)
    return password, KEY_ALIAS


def main():
    for p in [BT / "aapt2.exe", BT / "d8.bat", BT / "zipalign.exe", BT / "apksigner.bat"]:
        if not p.exists():
            raise SystemExit(f"missing SDK tool: {p}")
    if not PLATFORM_JAR.exists():
        raise SystemExit(f"missing platform jar: {PLATFORM_JAR}")
    ensure_api()
    password, alias = load_or_create_key()

    # clean
    if (OUT / "gen").exists():
        shutil.rmtree(OUT / "gen")
    for d in ["classes", "dex", "compiled_res"]:
        p = OUT / d
        if p.exists():
            shutil.rmtree(p)
        p.mkdir(parents=True)

    # 1. aapt2 compile res
    run([str(BT / "aapt2.exe"), "compile", "--dir", str(APP / "res"),
         "-o", str(OUT / "compiled_res.zip")])
    # 2. aapt2 link
    run([str(BT / "aapt2.exe"), "link",
         "-o", str(OUT / "base.apk"),
         "-I", str(PLATFORM_JAR),
         "--manifest", str(APP / "AndroidManifest.xml"),
         "--java", str(OUT / "gen"),
         "--min-sdk-version", "26",
         "--target-sdk-version", "34",
         "--version-code", VERSION_CODE,
         "--version-name", VERSION_NAME,
         str(OUT / "compiled_res.zip")])

    # 3. javac
    java_files = list((APP / "java").rglob("*.java"))
    print("sources:", [str(f.relative_to(ROOT)) for f in java_files])
    javac = shutil.which("javac") or os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac.exe")
    cp = f"{PLATFORM_JAR}{os.pathsep}{API_JAR}"
    run([javac, "-source", "8", "-target", "8",
         "-cp", cp, "-d", str(OUT / "classes")] + [str(f) for f in java_files])

    # 4. d8
    classes = list((OUT / "classes").rglob("*.class"))
    run(["cmd", "/c", str(BT / "d8.bat"),
         "--lib", str(PLATFORM_JAR),
         "--classpath", str(API_JAR),
         "--min-api", "26",
         "--output", str(OUT / "dex")] + [str(c) for c in classes])

    # 5. add classes.dex + META-INF/xposed/* into apk
    with zipfile.ZipFile(OUT / "base.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        for dex in sorted((OUT / "dex").glob("*.dex")):
            # first dex must be classes.dex
            name = "classes.dex" if dex.name == "classes.dex" else dex.name
            apk.write(dex, name)
            print("added", name)
        res_base = APP / "resources"
        for f in res_base.rglob("*"):
            if f.is_file():
                arc = f.relative_to(res_base).as_posix()
                apk.write(f, arc)
                print("added", arc)
        # verify no libxposed classes bundled (compileOnly)
        bad = [n for n in apk.namelist() if n.startswith("io/github/libxposed")]
        if bad:
            raise SystemExit(f"ERROR: libxposed bundled! {bad[:5]}")

    # 6. zipalign + sign (release key)
    if (OUT / "aligned.apk").exists():
        (OUT / "aligned.apk").unlink()
    run([str(BT / "zipalign.exe"), "-f", "4",
         str(OUT / "base.apk"), str(OUT / "aligned.apk")])
    run(["cmd", "/c", str(BT / "apksigner.bat"), "sign",
         "--ks", str(KEYSTORE), "--ks-key-alias", alias,
         "--ks-pass", f"pass:{password}",
         "--key-pass", f"pass:{password}", "--out",
         str(OUT / APK_NAME), str(OUT / "aligned.apk")])
    r = run(["cmd", "/c", str(BT / "apksigner.bat"), "verify", "--print-certs",
             str(OUT / APK_NAME)])
    print(r.stdout[-1000:])
    apk = OUT / APK_NAME
    print("OK:", apk, apk.stat().st_size, "bytes")
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        print("entries:", len(names))
        for key in ["classes.dex", "AndroidManifest.xml",
                    "META-INF/xposed/java_init.list",
                    "META-INF/xposed/module.prop",
                    "META-INF/xposed/scope.list"]:
            print(("FOUND " if key in names else "MISS  ") + key)


if __name__ == "__main__":
    sys.exit(main())
