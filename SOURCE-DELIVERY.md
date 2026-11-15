# Matching source and library replacement — v1.0.0

Public delivery order: source/material assets first, verify their checksums and anonymous download access, then JAR/binary assets and Docker image. Never mark public source availability PASS before it exists.

Designated location at publication: https://github.com/ParsaEfte/torchnode/releases/tag/v1.0.0

Required assets:
- torchnode-1.0.0-source.tar.gz: preferred project source, exact POM, JSPs, precompilation and image/build/install scripts, license texts/notices.
- torchnode-1.0.0-corresponding-sources.tar.gz: project/helper/vendor/library sources, generated-view rebuild inputs, exact JRE/OS source/recipes/patches/notices, build materials and replaceable-java/torchnode.jar with separate lib/*.
- THIRD_PARTY_NOTICES.md, DISTRIBUTION-LICENSE.md and SHA256SUMS; original component terms are inside corresponding materials and the image. ECJ compiler source is supplied separately under EPL2, with its original license and notices.

Docker Hub description and the GitHub release must link the actual corresponding-material asset and immutable image digest. GPLv3 section6(d) equivalent source access is at no further charge and must remain available as required; no deferred written-offer route is selected. GPL/LGPL grant full recipient modification/relinking rights; no access key or extra EULA limits them.

Replace an interface-compatible library without changing the application:
    java -cp 'replacement/lanterna-rebuilt.jar:lib/*:torchnode.jar' io.github.gavinruff007.torchnode.Main

Use the supplied replaceable-java directory and modified library preferred source. Other interface-compatible LGPL libraries can similarly precede lib/* in the classpath. Complete project source/JSP/precompilation scripts also permit rebuilding the application with a modified library. Keep original component license texts/notices and permit reverse engineering for debugging library modifications. These are recipient instructions, not authorization to operate the owner's production database.

The source package includes all generator inputs. Maven/JDK/Ant/Go are ordinary build tools; matching materials retain bundled tool notices and full Go source. The original JRE sources/build metadata are supplied; no independent JRE binary rebuild is claimed.

Non-production/new-empty-storage is tested/support scope only, not an open-source license condition. Recovery is NOT VERIFIED. Do not use owner production data or retained snapshots for validation.

Exact paths inside the corresponding-source archive:
- ECJ EPL2 source: maven/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0-sources.jar and upstream-projects/ecj-project-source.tar.gz. Its unmodified binary is separate build material in build-tools/ and maven/. Original EPL2/NOTICE copies are retained; source is provided under EPL2, not a supposed GPL secondary grant.
- Lanterna source: maven/com/googlecode/lanterna/lanterna/3.1.2/lanterna-3.1.2-sources.jar.
- JNR-POSIX source: maven/com/github/jnr/jnr-posix/3.1.15/jnr-posix-3.1.15-sources.jar, with exact upstream root alternatives preserved.
- JFFI source: maven/com/github/jnr/jffi/1.3.9/jffi-1.3.9-sources.jar plus the complete upstream-projects native/libffi project source.
- Application/library replacement layout: replaceable-java/torchnode.jar and replaceable-java/lib/*. Full legal resources are in the thin application, so Main and generated views remain usable with replacement libraries.
