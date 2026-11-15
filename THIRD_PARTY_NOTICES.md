# Third-party notices — TorchNode Observatory candidate

The covered application/geth-helper combination is conveyed under GPLv3 with existing MIT/component grants preserved. See DISTRIBUTION-LICENSE.md and HTTP /legal for scope, full licenses, source access and library replacement rights. Non-production describes support/testing, not a license field-of-use restriction. Project packaging/legal-notice modifications:2026-11-15; upstream notices remain unchanged.

TorchNode-owned source is MIT licensed. This does not license all dependencies or combined helper binaries as MIT.
The RLPx helper includes compiled geth GPL-3.0-or-later code and LGPL-3.0-or-later library code. Runtime Java libraries retain their applicable LGPL alternatives and other component terms. ECJ (EPL-2.0) is a build-only tool and its classes are excluded from the release JAR. Preserve their notices, corresponding source and replacement/rebuild rights. This component inventory preserves original grants; the documented owner-directed compliance assessment and actual source delivery govern release readiness. This file grants no production deployment authority.
The local corresponding-source bundle contains the helper source/vendor tree and resolved Maven runtime artifacts/source JARs. Rebuild instructions are in docs/third-party-licenses.md.

## Maven runtime components

| Component | Declared license metadata |
| --- | --- |
| com.maxmind.db:maxmind-db:3.1.1 | Apache-2.0 |
| org.bouncycastle:bcprov-jdk18on:1.86 | Bouncy Castle Licence |
| org.web3j:core:4.9.4 | Apache-2.0 |
| org.web3j:abi:4.9.4 | Apache-2.0 |
| org.web3j:utils:4.9.4 | Apache-2.0 |
| org.web3j:crypto:4.9.4 | Apache-2.0 |
| org.web3j:rlp:4.9.4 | Apache-2.0 |
| org.web3j:tuples:4.9.4 | Apache-2.0 |
| com.github.jnr:jnr-unixsocket:0.38.17 | Apache-2.0 |
| com.github.jnr:jnr-ffi:2.2.11 | Apache-2.0 |
| com.github.jnr:jffi:1.3.9 | Apache-2.0 |
| com.github.jnr:jffi:1.3.9:native | Apache-2.0 |
| org.ow2.asm:asm:9.2 | BSD-3-Clause |
| org.ow2.asm:asm-commons:9.2 | BSD-3-Clause |
| org.ow2.asm:asm-analysis:9.2 | BSD-3-Clause |
| org.ow2.asm:asm-tree:9.2 | BSD-3-Clause |
| org.ow2.asm:asm-util:9.2 | BSD-3-Clause |
| com.github.jnr:jnr-a64asm:1.0.0 | Apache-2.0 |
| com.github.jnr:jnr-x86asm:1.0.2 | MIT |
| com.github.jnr:jnr-constants:0.10.3 | Apache-2.0 |
| com.github.jnr:jnr-enxio:0.32.13 | Apache-2.0 |
| com.github.jnr:jnr-posix:3.1.15 | EPL-2.0, GNU General Public License Version 2, GNU Lesser General Public License Version 2.1 |
| com.squareup.okhttp3:logging-interceptor:4.9.0 | Apache-2.0 |
| io.reactivex.rxjava2:rxjava:2.2.2 | Apache-2.0 |
| org.reactivestreams:reactive-streams:1.0.2 | CC0-1.0 |
| org.java-websocket:Java-WebSocket:1.5.2 | MIT |
| org.slf4j:slf4j-api:1.7.30 | MIT |
| com.googlecode.lanterna:lanterna:3.1.2 | GNU Lesser General Public License |
| org.xerial:sqlite-jdbc:3.54.1.0 | Apache-2.0 |
| com.squareup.okhttp3:okhttp:4.12.0 | Apache-2.0 |
| com.squareup.okio:okio:3.6.0 | Apache-2.0 |
| com.squareup.okio:okio-jvm:3.6.0 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib-common:1.9.10 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib:1.8.21 | Apache-2.0 |
| org.jetbrains:annotations:13.0 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.21 | Apache-2.0 |
| com.fasterxml.jackson.core:jackson-databind:2.18.11 | Apache-2.0 |
| com.fasterxml.jackson.core:jackson-annotations:2.18.11 | Apache-2.0 |
| com.fasterxml.jackson.core:jackson-core:2.18.11 | Apache-2.0 |
| org.slf4j:slf4j-simple:1.7.30 | MIT |
| org.apache.tomcat.embed:tomcat-embed-jasper:10.1.61 | Apache-2.0 |
| org.apache.tomcat.embed:tomcat-embed-core:10.1.61 | Apache-2.0 |
| org.apache.tomcat:tomcat-annotations-api:10.1.61 | Apache-2.0 |
| org.apache.tomcat.embed:tomcat-embed-el:10.1.61 | Apache-2.0 |
| org.eclipse.jdt:ecj:3.33.0 (build-only; excluded from runtime JAR) | EPL-2.0 |

## Go helper components (Linux amd64, CGO disabled)

| Package | Version | License evidence |
| --- | --- | --- |
| github.com/VictoriaMetrics/fastcache | v1.13.0 | MIT |
| github.com/bits-and-blooms/bitset | v1.20.0 | BSD-3-Clause |
| github.com/cespare/xxhash/v2 | v2.3.0 | MIT |
| github.com/consensys/gnark-crypto | v0.18.1 | Apache-2.0 |
| github.com/crate-crypto/go-eth-kzg | v1.5.0 | Apache-2.0 |
| github.com/deckarep/golang-set/v2 | v2.6.0 | MIT |
| github.com/decred/dcrd/dcrec/secp256k1/v4 | v4.0.1 | ISC |
| github.com/emicklei/dot | v1.6.2 | MIT |
| github.com/ethereum/go-bigmodexpfix/src | v0.0.0-20250911101455-f9e208c548ab | BSD-3-Clause |
| github.com/ethereum/go-ethereum | v1.17.6 | GPL-3.0 |
| github.com/ethereum/go-ethereum/crypto/bn256 | v1.17.6 | BSD-3-Clause |
| github.com/ethereum/go-ethereum/crypto/ecies | v1.17.6 | BSD-3-Clause |
| github.com/ethereum/go-ethereum/crypto/keccak | v1.17.6 | BSD-3-Clause |
| github.com/ethereum/go-ethereum/metrics | v1.17.6 | BSD-2-Clause |
| github.com/ferranbt/fastssz | v0.1.4 | MIT |
| github.com/fjl/jsonw | v0.1.0 | MIT |
| github.com/gavinruff007/torchnode/p2p-helper | candidate | MIT (project source; combined binary carries dependency obligations) |
| github.com/go-logr/logr | v1.4.4 | Apache-2.0 |
| github.com/go-logr/stdr | v1.2.2 | Apache-2.0 |
| github.com/gofrs/flock | v0.12.1 | BSD-3-Clause |
| github.com/golang/snappy | v1.0.1-0.20260716114414-9ae09f520e93 | BSD-3-Clause |
| github.com/gorilla/websocket | v1.5.3 | BSD-2-Clause |
| github.com/holiman/bloomfilter/v2 | v2.0.3 | MIT |
| github.com/holiman/uint256 | v1.3.2 | BSD-3-Clause |
| github.com/huin/goupnp | v1.3.0 | BSD-2-Clause |
| github.com/jackpal/go-nat-pmp | v1.0.2 | Apache-2.0 (upstream LICENSE declaration and README) |
| github.com/klauspost/cpuid/v2 | v2.0.9 | MIT |
| github.com/minio/sha256-simd | v1.0.0 | Apache-2.0 |
| github.com/mitchellh/mapstructure | v1.4.1 | MIT |
| github.com/pion/dtls/v3 | v3.1.4 | MIT |
| github.com/pion/logging | v0.2.4 | MIT |
| github.com/pion/stun/v3 | v3.1.5 | MIT |
| github.com/pion/transport/v4 | v4.0.2 | MIT |
| github.com/shirou/gopsutil | v3.21.4-0.20210419000835-c7a38de76ee5+incompatible | BSD-3-Clause |
| github.com/syndtr/goleveldb/leveldb | v1.0.1-0.20210819022825-2ae1ddf74ef7 | BSD-2-Clause |
| github.com/tklauser/go-sysconf | v0.3.12 | BSD-3-Clause |
| github.com/tklauser/numcpus | v0.6.1 | Apache-2.0 |
| github.com/wlynxg/anet | v0.0.5 | BSD-3-Clause |
| go.opentelemetry.io/auto/sdk | v1.2.1 | Apache-2.0 |
| go.opentelemetry.io/otel | v1.47.0 | Apache-2.0 |
| go.opentelemetry.io/otel | v1.47.0 | BSD-3-Clause |
| go.opentelemetry.io/otel/log | v1.47.0 | Apache-2.0 |
| go.opentelemetry.io/otel/log | v1.47.0 | BSD-3-Clause |
| go.opentelemetry.io/otel/metric | v1.47.0 | Apache-2.0 |
| go.opentelemetry.io/otel/metric | v1.47.0 | BSD-3-Clause |
| go.opentelemetry.io/otel/trace | v1.47.0 | Apache-2.0 |
| go.opentelemetry.io/otel/trace | v1.47.0 | BSD-3-Clause |
| golang.org/x/crypto | v0.59.0 | BSD-3-Clause |
| golang.org/x/sync | v0.25.0 | BSD-3-Clause |
| golang.org/x/sys | v0.50.0 | BSD-3-Clause |
| gopkg.in/yaml.v2 | v2.4.0 | Apache-2.0 |

License/NOTICE texts are supplied in third-party/. Automated classifiers can miss per-file licensing. The GPL source declaration is preserved explicitly.

The bloomfilter v2 module source matches the tagged upstream v2 subtree; its repository-root MIT-LICENSE.txt and per-file copyright declarations are supplied. NAT-PMP has an Apache-2.0 declaration in LICENSE and README; its classifier unknown was reviewed against those files.
