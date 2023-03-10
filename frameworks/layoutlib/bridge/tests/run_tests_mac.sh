#!/bin/bash

# There is no macOS build of the SDK anymore
# Do not run layoutlib tests
exit 0

readonly OUT_DIR="$1"
readonly DIST_DIR="$2"
readonly BUILD_NUMBER="$3"

readonly SCRIPT_DIR="$(dirname "$0")"

readonly FAILURE_DIR=layoutlib-test-failures
readonly FAILURE_ZIP=layoutlib-test-failures.zip

STUDIO_JDK=${SCRIPT_DIR}"/../../../../prebuilts/jdk/jdk11/darwin-x86"
MISC_COMMON=${SCRIPT_DIR}"/../../../../prebuilts/misc/common"
OUT_INTERMEDIATES=${SCRIPT_DIR}"/../../../../out/soong/.intermediates"
NATIVE_LIBRARIES=${SCRIPT_DIR}"/../../../../out/host/darwin-x86/lib64/"
SDK=${SCRIPT_DIR}"/../../../../out/host/darwin-x86/sdk/sdk*/android-sdk*"
SDK_REPO=${SCRIPT_DIR}"/../../../../out/soong/host/linux-x86/sdk-repo"
FONT_DIR=${SCRIPT_DIR}"/../../../../out/host/common/obj/PACKAGING/fonts_intermediates"
ICU_DATA_PATH=${SCRIPT_DIR}"/../../../../out/host/darwin-x86/com.android.i18n/etc/icu/icudt69l.dat"
TMP_DIR=$(mktemp -d -t tmp)
PLATFORM=${TMP_DIR}/"android"

# Copy resources to a temp directory
cp -r ${SDK}/platforms/android* ${PLATFORM}

# Unzip build-tools to access aapt2
mkdir ${TMP_DIR}/build-tools
unzip -q ${SDK_REPO}/sdk-repo-linux-build-tools.zip -d ${TMP_DIR}/build-tools

# Compile 9-patch files
mkdir ${TMP_DIR}/compiled
mkdir ${TMP_DIR}/manifest
echo \
'<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.google.android.layoutlib" />' \
> ${TMP_DIR}/manifest/AndroidManifest.xml
for f in ${SDK}/platforms/android*/data/res/*
do
    find $f -name "*.9.png" -print0 | xargs -0 ${TMP_DIR}/build-tools/android-*/aapt2 compile -o ${TMP_DIR}/compiled/
    find ${TMP_DIR}/compiled -name "*.flat" -print0 | xargs -0 ${TMP_DIR}/build-tools/android-*/aapt2 link -o ${TMP_DIR}/compiled.apk --manifest ${TMP_DIR}/manifest/AndroidManifest.xml -R
    if [[ -f "${TMP_DIR}/compiled.apk" ]]; then
        unzip -qo ${TMP_DIR}/compiled.apk -d ${TMP_DIR}
        rm -r ${TMP_DIR}/compiled/*
        rm ${TMP_DIR}/compiled.apk
    fi
done
for f in ${TMP_DIR}/res/*; do mv "$f" "${f/-v4/}";done
cp -RL ${TMP_DIR}/res ${PLATFORM}/data

# Run layoutlib tests
${STUDIO_JDK}/bin/java -ea \
    -Dnative.lib.path=${NATIVE_LIBRARIES} \
    -Dfont.dir=${FONT_DIR} \
    -Dicu.data.path=${ICU_DATA_PATH} \
    -Dplatform.dir=${PLATFORM} \
    -Dtest_res.dir=${SCRIPT_DIR}/res \
    -Dtest_failure.dir=${OUT_DIR}/${FAILURE_DIR} \
    -cp ${MISC_COMMON}/tools-common/tools-common-prebuilt.jar:${MISC_COMMON}/ninepatch/ninepatch-prebuilt.jar:${MISC_COMMON}/sdk-common/sdk-common.jar:${MISC_COMMON}/kxml2/kxml2-2.3.0.jar:${MISC_COMMON}/layoutlib_api/layoutlib_api-prebuilt.jar:${OUT_INTERMEDIATES}/prebuilts/tools/common/m2/trove-prebuilt/darwin_common/combined/trove-prebuilt.jar:${OUT_INTERMEDIATES}/external/junit/junit/darwin_common/javac/junit.jar:${OUT_INTERMEDIATES}/external/guava/guava-jre/darwin_common/javac/guava-jre.jar:${OUT_INTERMEDIATES}/external/hamcrest/hamcrest-core/hamcrest/darwin_common/javac/hamcrest.jar:${OUT_INTERMEDIATES}/external/mockito/mockito/darwin_common/combined/mockito.jar:${OUT_INTERMEDIATES}/external/objenesis/objenesis/darwin_common/javac/objenesis.jar:${OUT_INTERMEDIATES}/frameworks/layoutlib/bridge/layoutlib/darwin_common/withres/layoutlib.jar:${OUT_INTERMEDIATES}/frameworks/layoutlib/temp_layoutlib/darwin_common/gen/temp_layoutlib.jar:${OUT_INTERMEDIATES}/frameworks/layoutlib/bridge/tests/layoutlib-tests/darwin_common/withres/layoutlib-tests.jar \
    org.junit.runner.JUnitCore \
    com.android.layoutlib.bridge.intensive.Main

test_exit_code=$?

# Create zip of all failure screenshots
if [[ -d "${OUT_DIR}/${FAILURE_DIR}" ]]; then
    zip -q -j -r ${OUT_DIR}/${FAILURE_ZIP} ${OUT_DIR}/${FAILURE_DIR}
fi

# Move failure zip to dist directory if specified
if [[ -d "${DIST_DIR}" ]] && [[ -e "${OUT_DIR}/${FAILURE_ZIP}" ]]; then
    mv ${OUT_DIR}/${FAILURE_ZIP} ${DIST_DIR}
fi

# Clean
rm -rf ${TMP_DIR}
rm -rf ${OUT_DIR}/${FAILURE_DIR}

exit ${test_exit_code}
