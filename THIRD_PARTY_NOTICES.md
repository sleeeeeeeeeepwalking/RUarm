# Third-party components

RUarm bundles prebuilt binaries of the following open-source projects. They are taken from the
[Termux packages](https://github.com/termux/termux-packages) repository (aarch64 builds); the exact
package versions are listed below. Source code for each package is available from the upstream projects
and from the Termux build recipes linked here.

| Component | Version | License | Upstream |
|---|---|---|---|
| Wine (Hangover fork) | 11.16 | LGPL-2.1-or-later | https://www.winehq.org/, https://github.com/AndreRH/hangover |
| wowbox64 (Box64) | 11.16 / 0.4.4 | MIT | https://github.com/ptitSeb/box64 |
| 7-Zip (7zz) | 26.06 | LGPL-2.1-or-later with unRAR restriction, BSD-3-Clause parts | https://www.7-zip.org/ |
| innoextract | 1.13 | zlib | https://constexpr.org/innoextract/ |
| Boost (iostreams, filesystem, program_options, random, atomic, container) | 1.92.0 | BSL-1.0 | https://www.boost.org/ |
| XZ Utils (liblzma) | 5.8.4 | 0BSD / public domain | https://tukaani.org/xz/ |
| bzip2 (libbz2) | 1.0.8 | bzip2 license (BSD-like) | https://sourceware.org/bzip2/ |
| zlib | 1.4 | zlib | https://zlib.net/ |
| GNU libiconv | 1.19.1 | LGPL-2.1-or-later | https://www.gnu.org/software/libiconv/ |
| LLVM libc++ | 30 | Apache-2.0 WITH LLVM-exception | https://libcxx.llvm.org/ |
| libandroid-spawn | 0.3 | BSD-2-Clause | https://github.com/termux/termux-packages |

Termux build recipes: https://github.com/termux/termux-packages (packages `hangover-wine`, `hangover-wowbox64`,
`7zip`, `innoextract`, `boost`, `liblzma`, `libbz2`, `zlib`, `libiconv`, `libc++`, `libandroid-spawn`).

Modifications made for RUarm (binary patches only, no source changes):
- shared-library file names and `DT_NEEDED`/`SONAME` entries renamed so they can be packaged as `lib*.so`;
- `RUNPATH` changed to `$ORIGIN`; hard-coded `/data/data/com.termux/files` paths changed to `/data/data/ru.ruarm/files`;
- debug information stripped from Wine PE modules;
- the `IsWow64Process2` export name in the 32-bit (`i386-windows`) `kernel32.dll` renamed so old Inno Setup
  installers fall back to `GetNativeSystemInfo` and run on ARM64.

The unRAR code in 7-Zip may not be used to re-create the RAR compression algorithm.

Android libraries: AndroidX / Jetpack Compose (Apache-2.0), Shizuku API (Apache-2.0, https://github.com/RikkaApps/Shizuku-API).
