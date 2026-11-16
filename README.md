# TorchNode Observatory

**Ethereum network observability below the RPC layer.**

[![CI](https://github.com/ParsaEfte/torchnode/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ParsaEfte/torchnode/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/ParsaEfte/torchnode)](https://github.com/ParsaEfte/torchnode/releases/tag/v1.0.0)
[![GitHub issues](https://img.shields.io/github/issues/ParsaEfte/torchnode)](https://github.com/ParsaEfte/torchnode/issues)

[**Get v1.0.0**](https://github.com/ParsaEfte/torchnode/releases/tag/v1.0.0) · [**Docker Hub**](https://hub.docker.com/r/parsa202089/torchnode/tags) · [**Deployment guide**](docs/deployment.md) · [**Architecture**](docs/architecture.md) · [**Contribute**](CONTRIBUTING.md)

TorchNode observes Ethereum peer discovery and protocol endpoints from **one observer's perspective**. It retains timestamped evidence in SQLite and presents discovery, inspection, history, changes, analytics, and reports through an authenticated web interface and read-only API. Its measurements **do not estimate the entire Ethereum network**.

## What it measures

- **Discovery:** discv4, discv5, cryptographic node identities, validated ENRs, IPv4/IPv6 endpoint claims and provenance.
- **Protocol inspection:** independent TCP, RLPx Auth, devp2p Hello, ETH Status, JSON-RPC and Beacon API evidence.
- **History and analytics:** durable observation occurrences, conservative derived changes, bounded network analytics and topology views.
- **Interface and API:** Dashboard, Deep Inspection, History, Changes, Reports and authenticated read-only `/api/v1` endpoints.
- **Local storage:** SQLite with provenance, source and timestamp distinctions; optional locally supplied GeoIP/ASN data.

> [!IMPORTANT]
> **v1.0.0 is publicly released, but Recovery is NOT VERIFIED.** Supported/tested installation scope is **new, empty non-production storage**. Do not attach an existing or production database, or treat this release as an approved production migration. Retained-checkpoint restore, crash/WAL recovery and rollback are not verified. These support and operational cautions do **not** restrict recipients' open-source license rights. See the [release checklist](docs/release-checklist-v1.0.md) and [recovery policy](docs/recovery-rollback-policy.md).

## Quick start

The published Docker image targets **linux/amd64**. For a new disposable installation, first read the [deployment guide](docs/deployment.md) for persistent volume setup, **interactive admin provisioning**, session authentication, secure cookies, TLS and networking. There are **no default credentials**.

```sh
docker pull --platform linux/amd64 parsa202089/torchnode:1.0.0
```

This pull command only downloads the image; it does not provision an administrator or start a service. Use a **new, empty** volume for testing and follow the documented provisioning sequence. Do not run a second SQLite writer against the same database.

For source builds, use Java 21+, Maven and the pinned Go toolchain. The Java JAR and both Go helpers are built separately; see [deployment](docs/deployment.md) and [configuration](docs/configuration.md).

## How observations flow

```text
discv4 / discv5 -> cryptographic identity -> ENR / endpoints
                                               |
                         +---------------------+--------------------+
                         |                     |                    |
                  TCP / RLPx / ETH         JSON-RPC             Beacon
                         +---------------------+--------------------+
                                               |
                             timestamped SQLite evidence
                                               |
                       Dashboard / History / Changes / Reports / API
```

An IP address is an endpoint, **not** a cryptographic identity. A reported neighbor is **not** proof of an active peer connection. Missing measurements remain unknown, and failures in one inspection stage do not erase independent evidence.

## Documentation

| Guide | Contents |
| --- | --- |
| [Deployment](docs/deployment.md) | New installations, provisioning, Docker Compose and upgrade boundaries |
| [Architecture](docs/architecture.md) | Discovery, identity and evidence model |
| [API](docs/api.md) | Authenticated read-only API and bounded exports |
| [Configuration](docs/configuration.md) | Runtime options and troubleshooting |
| [Security](SECURITY.md) | Privacy, safe disclosure and deployment security |
| [Release checklist](docs/release-checklist-v1.0.md) | Verified engineering gates and outstanding recovery gate |
| [Changelog](CHANGELOG.md) | Release history |
| [Contributing](CONTRIBUTING.md) | Local development and pull requests |

## Community

Found a reproducible bug? [Open a bug report](https://github.com/ParsaEfte/torchnode/issues/new/choose). Have an idea? [Suggest a feature](https://github.com/ParsaEfte/torchnode/issues/new/choose). Please avoid posting credentials, real observer state, peer-identifying data or database snapshots in public issues.

## Licensing and distribution

The repository contains a project [MIT grant](LICENSE), while the **covered application/geth-helper distribution** is conveyed under **GPLv3** with original component grants preserved. Do not assume the GitHub license badge describes the entire combined binary distribution. Read [distribution license scope](DISTRIBUTION-LICENSE.md), [third-party notices](THIRD_PARTY_NOTICES.md) and [corresponding source/relink delivery](SOURCE-DELIVERY.md). The v1.0.0 release includes matching source materials and notices.
