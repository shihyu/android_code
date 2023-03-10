#!/bin/bash -ex
#
# Copyright 2019 Google Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Regenerate host configuration files for the current host

cd `dirname ${BASH_SOURCE[0]}`

ANDROID_BUILD_TOP=$(cd ../../../..; pwd)

DIR=`uname | tr 'A-Z' 'a-z'`_x86_64
mkdir -p $DIR/pyconfig
cd $DIR

export CLANG_VERSION=$(cd $ANDROID_BUILD_TOP; build/soong/scripts/get_clang_version.py)

if [ $DIR == "linux_x86_64" ]; then
  export CC="$ANDROID_BUILD_TOP/prebuilts/clang/host/linux-x86/$CLANG_VERSION/bin/clang"
  export CFLAGS="--sysroot=$ANDROID_BUILD_TOP/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8/sysroot"
  export LDFLAGS="--sysroot=$ANDROID_BUILD_TOP/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8/sysroot -B$ANDROID_BUILD_TOP/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8/lib/gcc/x86_64-linux/4.8.3 -L$ANDROID_BUILD_TOP/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8/lib/gcc/x86_64-linux/4.8.3 -L$ANDROID_BUILD_TOP/prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8/x86_64-linux/lib64"
fi

#
# Generate pyconfig.h
#
rm -rf tmp
mkdir tmp
cd tmp
../../../configure

if [ $DIR == "darwin_x86_64" ]; then
  # preadv and pwritev are not safe on <11, which we still target
  sed -ibak "s%#define HAVE_PREADV 1%/* #undef HAVE_PREADV */%" pyconfig.h
  sed -ibak "s%#define HAVE_PWRITEV 1%/* #undef HAVE_PWRITEV */%" pyconfig.h
fi

if [ $DIR == "linux_x86_64" ]; then
  mkdir -p ../../bionic/pyconfig
  cp pyconfig.h ../../bionic/pyconfig/
  # Changes to support bionic
  bionic_pyconfig=../../bionic/pyconfig/pyconfig.h
  sed -i 's%#define HAVE_CONFSTR 1%/* #undef HAVE_CONFSTR */%' $bionic_pyconfig
  sed -i 's%#define HAVE_CRYPT_H 1%/* #undef HAVE_CRYPT_H */%' $bionic_pyconfig
  sed -i 's%#define HAVE_CRYPT_R 1%/* #undef HAVE_CRYPT_R */%' $bionic_pyconfig
  sed -i 's%#define HAVE_DECL_RTLD_DEEPBIND 1%/* #undef HAVE_DECL_RTLD_DEEPBIND */%' $bionic_pyconfig
  sed -i "s%#define HAVE_GCC_ASM_FOR_X87 1%#ifdef __i386__\n#define HAVE_GCC_ASM_FOR_X87 1\n#endif%" $bionic_pyconfig
  sed -i 's%#define HAVE_LIBINTL_H 1%/* #undef HAVE_LIBINTL_H */%' $bionic_pyconfig
  sed -i 's%#define HAVE_STROPTS_H 1%/* #undef HAVE_STROPTS_H */%' $bionic_pyconfig
  sed -i 's%#define HAVE_WAIT3 1%/* #undef HAVE_WAIT3 */%' $bionic_pyconfig

  sed -i 's%#define SIZEOF_FPOS_T .*%#define SIZEOF_FPOS_T 8%' $bionic_pyconfig
  sed -i 's%#define SIZEOF_LONG .*%#ifdef __LP64__\n#define SIZEOF_LONG 8\n#else\n#define SIZEOF_LONG 4\n#endif%' $bionic_pyconfig
  sed -i 's%#define SIZEOF_LONG_DOUBLE .*%#define SIZEOF_LONG_DOUBLE (SIZEOF_LONG * 2)%' $bionic_pyconfig
  sed -i 's%#define SIZEOF_PTHREAD_T .*%#define SIZEOF_PTHREAD_T SIZEOF_LONG%' $bionic_pyconfig
  sed -i 's%#define SIZEOF_SIZE_T .*%#define SIZEOF_SIZE_T SIZEOF_LONG%' $bionic_pyconfig
  sed -i 's%#define SIZEOF_TIME_T .*%#define SIZEOF_TIME_T SIZEOF_LONG%' $bionic_pyconfig
  sed -i 's%#define SIZEOF_UINTPTR_T .*%#define SIZEOF_UINTPTR_T SIZEOF_LONG%' $bionic_pyconfig
  sed -i 's%#define SIZEOF_VOID_P .*%#define SIZEOF_VOID_P SIZEOF_LONG%' $bionic_pyconfig

  # Changes to support musl
  sed -i "s%#define HAVE_DECL_RTLD_DEEPBIND 1%#ifdef __GLIBC__\n#define HAVE_DECL_RTLD_DEEPBIND 1\n#endif%" pyconfig.h
fi

cp pyconfig.h ../pyconfig/

function generate_srcs() {
  #
  # Generate config.c
  #
  echo >Makefile.pre
  ../../../Modules/makesetup -c ../../../Modules/config.c.in -s Modules -m Makefile.pre ../Setup.local ../../Setup.local ../../../Modules/Setup
  cp config.c ../

  #
  # Generate module file list
  #
  grep '$(CC)' Makefile | sed 's/;.*//' | sed 's/.*: //' | sed 's#$(srcdir)/##' | sort -u >srcs
  (
    echo '// Generated by android/regen.sh'
    echo 'filegroup {'
    echo "    name: \"py3-c-modules-$1\","
    echo "    srcs: ["
    for src in $(cat srcs); do
      echo "        \"${src}\","
    done
    echo "    ],"
    echo '}'
  ) >../../../Android-$1.bp
}

generate_srcs $DIR

cd ..
rm -rf tmp

if [ $DIR == "linux_x86_64" ]; then
  mkdir ../bionic/tmp
  pushd ../bionic/tmp
  generate_srcs bionic
  popd
  rm -rf ../bionic/tmp
fi
