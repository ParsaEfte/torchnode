# Active IPv6 material changes

Paths relative to the repository root. `ipv4_client.go` was generalized into `endpoint_client.go`; the deleted path is shown for clarity.

- `README.md`
- `p2p-helper/cmd/discovery/ipv4_client.go`
- `p2p-helper/cmd/discovery/main.go`
- `p2p-helper/cmd/discovery/main_test.go`
- `p2p-helper/main.go`
- `p2p-helper/main_test.go`
- `src/main/java/io/github/gavinruff007/torchnode/daemon/ScanDaemon.java`
- `src/main/java/io/github/gavinruff007/torchnode/dashboard/DashboardServlet.java`
- `src/main/java/io/github/gavinruff007/torchnode/discovery/DiscoveredNode.java`
- `src/main/java/io/github/gavinruff007/torchnode/discovery/Discv4DiscoveryProvider.java`
- `src/main/java/io/github/gavinruff007/torchnode/discovery/Discv5DiscoveryProvider.java`
- `src/main/java/io/github/gavinruff007/torchnode/discovery/FindNodeSender.java`
- `src/main/java/io/github/gavinruff007/torchnode/discovery/P2PListener.java`
- `src/main/java/io/github/gavinruff007/torchnode/discovery/P2PSender.java`
- `src/main/java/io/github/gavinruff007/torchnode/enr/Discv4EnrClient.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/BeaconProber.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/GoEthereumP2pInspector.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/InspectionResult.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/InspectionService.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/NodeInspector.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/P2pInspectionResult.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/RpcProber.java`
- `src/main/java/io/github/gavinruff007/torchnode/model/DiscoveryObservation.java`
- `src/main/java/io/github/gavinruff007/torchnode/model/NodeEndpoint.java`
- `src/main/java/io/github/gavinruff007/torchnode/model/NodeRecord.java`
- `src/main/java/io/github/gavinruff007/torchnode/storage/SqliteNodeStore.java`
- `src/main/resources/webapp/WEB-INF/views/dashboard.jsp`
- `src/main/resources/webapp/WEB-INF/views/inspection.jsp`
- `src/test/java/io/github/gavinruff007/torchnode/dashboard/EnrDashboardTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/discovery/Discv5DiscoveryProviderTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/enr/Discv4EnrClientTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/enr/LoopbackEnrPeer.java`
- `docs/adr/active-ipv6-support.md`
- `p2p-helper/cmd/discovery/endpoint_client.go`
- `p2p-helper/cmd/discovery/ipv6_client_test.go`
- `src/main/java/io/github/gavinruff007/torchnode/model/EndpointAddress.java`
- `src/test/java/io/github/gavinruff007/torchnode/discovery/ActiveIpv6Discv4Test.java`
- `src/test/java/io/github/gavinruff007/torchnode/inspection/ActiveIpv6HttpTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/inspection/ActiveIpv6InspectionTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/model/EndpointAddressTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/storage/ActiveIpv6PersistenceTest.java`
- `tools/ValidateActiveIpv6.java`

- `docs/validation/active-ipv6-support.md` — closure evidence.
- `docs/validation/active-ipv6-files.md` — this manifest.
- `docs/validation/active-ipv6-public-evidence.json` — raw public evidence and measurements.
