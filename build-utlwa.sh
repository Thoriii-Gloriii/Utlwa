#!/bin/bash
# Build Utlwa! with NewPipeExtractor bundled, without Gradle.
set -euo pipefail
P=$(cd "$(dirname "$0")" && pwd)
SDK_JAR=${SDK_JAR:-$HOME/android-sdk/android-36/android.jar}
if [ ! -f "$SDK_JAR" ] && [ -f "/root/android-sdk/android-36/android.jar" ]; then
  SDK_JAR="/root/android-sdk/android-36/android.jar"
fi
LIBS=${LIBS:-$P/libs}
if [ ! -d "$LIBS" ]; then LIBS=/root/.buddy/work/libs; fi
KEYDIR=$HOME/.buddy/keys
OUT=$P/build
VCODE=6; VNAME=1.5; MINSDK=26

CP=$(printf '%s:' "$LIBS"/*.jar)
[ -f "$SDK_JAR" ] || { echo "android.jar missing" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/libclasses" "$OUT/dex"

echo "1/6 Resources"
aapt2 compile --dir "$P/res" -o "$OUT/res.zip"
aapt2 link -I "$SDK_JAR" --manifest "$P/AndroidManifest.xml" \
  --min-sdk-version "$MINSDK" --target-sdk-version 36 \
  --version-code "$VCODE" --version-name "$VNAME" \
  --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "2/6 Java (app + extractor)"
javac --release 17 -classpath "$SDK_JAR:$CP" -Xlint:-options -encoding UTF-8 -nowarn \
  -d "$OUT/classes" $(find "$P/java" "$OUT/gen" -name '*.java')

echo "3/6 Extract dependency classes"
for j in "$LIBS"/*.jar; do
  (cd "$OUT/libclasses" && unzip -q -o "$j" '*.class' 2>/dev/null || true)
done

echo "4/6 Dex"
find "$OUT/libclasses" -name 'module-info.class' -delete
d8 --release --min-api "$MINSDK" --lib "$SDK_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" "$OUT/libclasses" -name '*.class')

echo "5/6 Package"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/aligned.apk" <<'EOF'
import sys, zipfile
src, dex, dst = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
    entries.append((zipfile.ZipInfo("classes.dex"), open(dex, "rb").read()))
    entries[-1][0].compress_type = zipfile.ZIP_DEFLATED
    for info, data in entries:
        out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        out.compress_type = info.compress_type
        out.external_attr = info.external_attr
        if out.compress_type == zipfile.ZIP_STORED:
            start = zout.fp.tell() + 30 + len(out.filename.encode())
            out.extra = b"\0" * ((-start) % 4)
        zout.writestr(out, data)
EOF

echo "6/6 Sign"
if [ ! -f "$KEYDIR/apps.jks" ]; then
  mkdir -p "$KEYDIR"; chmod 700 "$KEYDIR"
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24 > "$KEYDIR/password"
  chmod 600 "$KEYDIR/password"
  keytool -genkeypair -keystore "$KEYDIR/apps.jks" -alias apps -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$KEYDIR/password")" -keypass "$(cat "$KEYDIR/password")" -dname "CN=Buddy apps" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYDIR/apps.jks" --ks-key-alias apps --ks-pass "file:$KEYDIR/password" \
  --out "$OUT/app.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/app.apk"
echo "Built $OUT/app.apk ($(du -h "$OUT/app.apk" | cut -f1))"
