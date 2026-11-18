#!/bin/bash
# ============================================================================
# pc-power-debug.apk 纯命令行构建脚本(Git Bash / Windows,Linux / macOS 同样适用)
# 不使用 Gradle / AndroidX / 任何第三方库,只用 android.jar。
#
# 工具链不在仓库里,用环境变量指向本机安装位置,未设置时回退到 ../tools:
#   ANDROID_TOOLS   JDK17 与 android-sdk 的上级目录(默认 <仓库>/tools)
#   JDK17_HOME      JDK17 根目录(默认 $ANDROID_TOOLS/jdk17/* 里唯一一项)
#   ANDROID_SDK     Android SDK 根目录(默认 $ANDROID_TOOLS/android-sdk)
#   BUILD_TOOLS     构建工具版本(默认 34.0.0)
# 需要:JDK 17、build-tools 34、platforms/android-34。
#
# 流程:aapt2 compile(res)→ aapt2 link(manifest+res,生成 resources.arsc 与 R.java)
#      → javac(Java 8 + android.jar,含 src 与生成的 R.java)
#      → d8(--min-api 24)
#      → jar 把 classes.dex 追加进 APK 根目录
#      → zipalign -f 4
#      → 沿用 apk/debug.keystore(不存在时才生成;覆盖安装必须保持原签名)
#      → apksigner sign + verify
# ============================================================================
set -euo pipefail

APK_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${ANDROID_TOOLS:-$(cd "$APK_DIR/.." && pwd)/tools}"

if [ -z "${JDK17_HOME:-}" ]; then
    JDK17_HOME="$(ls -d "$TOOLS"/jdk17/* 2>/dev/null | head -n 1 || true)"
fi
SDK="${ANDROID_SDK:-$TOOLS/android-sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
PLATFORM="$SDK/platforms/android-34/android.jar"

if [ -z "${JDK17_HOME:-}" ] || [ ! -d "$JDK17_HOME" ]; then
    echo "错误:未找到 JDK17。请设置 JDK17_HOME,或把 JDK 放在 $TOOLS/jdk17/ 下。" >&2
    exit 1
fi
if [ ! -f "$PLATFORM" ]; then
    echo "错误:未找到 $PLATFORM。请设置 ANDROID_SDK,或安装 platforms/android-34。" >&2
    exit 1
fi

# Windows(Git Bash / MSYS)下的可执行文件带 .exe 后缀
EXE=""
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac

JAVA="$JDK17_HOME/bin/java$EXE"
JAVAC="$JDK17_HOME/bin/javac$EXE"
KEYTOOL="$JDK17_HOME/bin/keytool$EXE"

KS="$APK_DIR/debug.keystore"
OUT="$APK_DIR/pc-power-debug.apk"
BUILD="$APK_DIR/build"

echo "==> 0/8 清理旧的构建目录"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> 1/8 aapt2 compile + link(manifest + res/,生成 resources.arsc 与 R.java)"
"$BT/aapt2$EXE" compile --dir "$APK_DIR/res" -o "$BUILD/res.zip"
"$BT/aapt2$EXE" link \
    -I "$PLATFORM" \
    --manifest "$APK_DIR/AndroidManifest.xml" \
    -R "$BUILD/res.zip" \
    --auto-add-overlay \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --java "$BUILD/gen" \
    -o "$BUILD/base.apk"

echo "==> 2/8 javac 编译 Java 源码(UTF-8,source/target 8)"
"$JAVAC" -encoding UTF-8 \
    --release 8 \
    -Xlint:none -nowarn \
    -classpath "$PLATFORM" \
    -d "$BUILD/classes" \
    $(find "$APK_DIR/src" -name '*.java' | tr '\n' ' ') \
    "$BUILD/gen/com/example/pcpower/R.java"

echo "==> 3/8 d8 编译为 classes.dex(min-api 24)"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release \
    --min-api 24 \
    --lib "$PLATFORM" \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class' | tr '\n' ' ')

if [ ! -f "$BUILD/dex/classes.dex" ]; then
    echo "错误:未生成 classes.dex" >&2
    exit 1
fi

echo "==> 4/8 把 classes.dex 打进 APK 根目录(jar 追加)"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
if command -v zip >/dev/null 2>&1; then
    (cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex)
else
    # Git Bash 通常没有 zip,使用 JDK 自带 jar 工具追加到压缩包根目录
    "$JDK17_HOME/bin/jar$EXE" uf "$BUILD/unsigned.apk" -C "$BUILD/dex" classes.dex
fi

echo "==> 5/8 zipalign -f 4"
"$BT/zipalign$EXE" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> 6/8 准备 debug 签名密钥(apk/debug.keystore,沿用既有密钥保证覆盖安装)"
if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair \
        -keystore "$KS" -storetype PKCS12 \
        -alias pcpower -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=PC Power Debug, OU=Debug, O=Example, C=CN"
    echo "    已生成新密钥库 $KS"
fi

echo "==> 7/8 apksigner 签名"
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-key-alias pcpower \
    --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 24 \
    --out "$OUT" \
    "$BUILD/aligned.apk"

echo "==> 8/8 apksigner verify --print-certs"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT"

ls -la "$OUT"
echo "构建完成:$OUT"
