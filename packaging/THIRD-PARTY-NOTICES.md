# Included third-party software

The portable ZIP contains the OpenShell executable JAR, original third-party notices,
and an unmodified Eclipse Temurin JRE. Each component retains its upstream license.
OpenShell source: https://github.com/brownjasen/OpenShell

| Component | Version | Upstream license |
| --- | --- | --- |
| JediTerm core / UI | 3.76 | LGPL 3.0, as declared by the published POMs |
| Kotlin standard library | 2.4.0 | Apache 2.0 |
| JetBrains annotations | 24.0.1 | Apache 2.0 |
| SLF4J API / simple | 2.0.9 | MIT |
| FlatLaf / extras | 3.5.4 | Apache 2.0 |
| JSVG | 1.4.0 | MIT |
| JSch (mwiede fork) | 0.2.20 | BSD 3-Clause |
| Jackson core / annotations / databind | 2.17.2 | Apache 2.0 |
| Eclipse Temurin JRE for Windows x64 | 17.0.20.1+1 | GPL 2.0 with Classpath Exception; component notices in runtime/legal |

`jediterm/` retains the matching original binaries, source JARs, POMs, checksums,
license texts and origin record. `components/` preserves notices and POM metadata
from the original runtime dependency JARs. These files are not replaced by the
application's shaded META-INF entries.

The complete JRE legal directory and NOTICE are retained under `runtime/`.
Pinned runtime binary/source links and its SHA-256 are recorded in
`runtime-windows-x64.json`. OpenShell does not change the JRE or dependency JARs.

Packaging uses the OpenJDK jpackage app-image launcher. Its license notice and
matching packager JDK release metadata are included under `jpackage-tool/`.
The included Java runtime is independent of the JDK used by the build machine.
