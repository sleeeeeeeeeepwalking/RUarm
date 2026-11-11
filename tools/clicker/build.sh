#!/bin/bash
# Builds ruarm_clicker.exe (aarch64-windows, no CRT) with the Android NDK's clang + lld.
# Usage: NDK=/path/to/ndk tools/clicker/build.sh   -> app/src/main/assets/ruarm_clicker.exe
set -e
cd "$(dirname "$0")"
BIN="${NDK:?set NDK}/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)/bin"
OUT=build; mkdir -p $OUT
printf 'LIBRARY user32.dll\nEXPORTS\nEnumWindows\nEnumChildWindows\nGetClassNameW\nGetWindowTextW\nGetDlgCtrlID\nGetDlgItem\nPostMessageW\nIsWindow\n' > $OUT/user32.def
printf 'LIBRARY kernel32.dll\nEXPORTS\nSleep\nGetStdHandle\nWriteFile\nWideCharToMultiByte\nExitProcess\n' > $OUT/kernel32.def
"$BIN/llvm-dlltool" -m arm64 -d $OUT/user32.def -l $OUT/user32.lib
"$BIN/llvm-dlltool" -m arm64 -d $OUT/kernel32.def -l $OUT/kernel32.lib
"$BIN/clang" --target=aarch64-pc-windows-msvc -O2 -ffreestanding -fno-builtin -fno-stack-protector \
  -mno-stack-arg-probe -c clicker.c -o $OUT/clicker.obj
"$BIN/ld.lld" -flavor link /subsystem:console /entry:start /nodefaultlib /out:$OUT/ruarm_clicker.exe \
  $OUT/clicker.obj $OUT/user32.lib $OUT/kernel32.lib
cp $OUT/ruarm_clicker.exe ../../app/src/main/assets/ruarm_clicker.exe
echo "built $(wc -c < $OUT/ruarm_clicker.exe) bytes"
