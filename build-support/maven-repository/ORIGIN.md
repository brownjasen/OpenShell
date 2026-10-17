# Pinned JediTerm repository

This directory contains unmodified JediTerm 3.76 core/UI artifacts and their
matching source JARs, published by JetBrains at:

https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/jediterm/

Both binary and source artifacts were checked against the publisher's SHA-1
sidecars before inclusion. SHA256SUMS records local SHA-256 checksums. Maven
uses the supplied POMs and normal transitive dependency resolution; no system
scope or hand-written IDEA classpath is used.

These pinned files let Maven and IDEA import the project when the vendor CDN
cannot be reached. Other dependencies continue to use normal Maven repositories.

Upstream: https://github.com/JetBrains/jediterm

The published POMs declare LGPL 3.0. Corresponding source JARs and copies of the
LGPLv3 and GPLv3 license texts are included. The upstream repository also offers
Apache 2.0; its license text is retained here without changing artifact metadata.
The two upstream license files were obtained from the upstream master branch;
the GPLv3 text was obtained from https://www.gnu.org/licenses/gpl-3.0.txt.

Do not edit the dependency JARs. To upgrade, replace binaries, POMs and sources
together, verify the publisher's checksums, update SHA256SUMS and pom.xml, and
verify compilation and terminal behavior before changing the pinned version.
