package main

import (
	"context"
	"crypto/ecdsa"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"strconv"
	"syscall"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/forkid"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/eth/protocols/eth"
	"github.com/ethereum/go-ethereum/p2p"
	"github.com/ethereum/go-ethereum/p2p/rlpx"
	"github.com/ethereum/go-ethereum/params"
	"github.com/ethereum/go-ethereum/rlp"
)

func key(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	k, err := crypto.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func fixture(t *testing.T, serve func(net.Conn, *ecdsa.PrivateKey) error) (request, <-chan error) {
	t.Helper()
	private := key(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	done := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(6 * time.Second))
		done <- serve(conn, private)
	}()
	port := listener.Addr().(*net.TCPAddr).Port
	return request{IP: "127.0.0.1", TCPPort: port,
		NodeID: hex.EncodeToString(crypto.FromECDSAPub(&private.PublicKey)[1:])}, done
}

func serverHello(conn net.Conn, private *ecdsa.PrivateKey, caps []p2p.Cap) (*rlpx.Conn, error) {
	transport := rlpx.NewConn(conn, nil)
	if _, err := transport.Handshake(private); err != nil {
		return nil, err
	}
	code, payload, _, err := transport.Read()
	if err != nil {
		return nil, err
	}
	if code != 0 {
		return nil, errors.New("client did not send Hello")
	}
	var greeting wireHello
	if err := rlp.DecodeBytes(payload, &greeting); err != nil {
		return nil, err
	}
	if greeting.Version != 5 || greeting.Name != "TorchNode Observatory/1.0" ||
		greeting.ListenPort != 0 || len(greeting.ID) != 64 ||
		len(greeting.Caps) != len(eth.ProtocolVersions) {
		return nil, errors.New("unexpected client capabilities")
	}
	for i, version := range eth.ProtocolVersions {
		if greeting.Caps[i].Name != "eth" || greeting.Caps[i].Version != version {
			return nil, errors.New("incorrect local ETH capability")
		}
	}
	encoded, err := rlp.EncodeToBytes(wireHello{Version: 5, Name: "Geth/v1.16.9/linux-amd64/go1.24",
		Caps: caps, ListenPort: 30303, ID: crypto.FromECDSAPub(&private.PublicKey)[1:]})
	if err != nil {
		return nil, err
	}
	if _, err := transport.Write(0, encoded); err != nil {
		return nil, err
	}
	transport.SetSnappy(true)
	return transport, nil
}

func TestAuthenticatedHelloAndETHStatus(t *testing.T) {
	old := localStatusProvider
	localStatusProvider = func(context.Context, uint) (any, string) {
		return &eth.StatusPacket{ProtocolVersion: 69, NetworkID: 560048,
			Genesis: params.HoodiGenesisHash, ForkID: forkid.ID{Hash: [4]byte{1, 2, 3, 4}},
			LatestBlock: 100, LatestBlockHash: common.HexToHash("0x01")}, ""
	}
	t.Cleanup(func() { localStatusProvider = old })
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport, err := serverHello(conn, private, []p2p.Cap{{Name: "eth", Version: 68},
			{Name: "eth", Version: 69}, {Name: "snap", Version: 1}})
		if err != nil {
			return err
		}
		code, payload, _, err := transport.Read()
		if err != nil {
			return err
		}
		if code != 0x10 {
			return errors.New("ETH Status was not sent")
		}
		var outbound eth.StatusPacket
		if err := rlp.DecodeBytes(payload, &outbound); err != nil {
			return err
		}
		if outbound.NetworkID != 560048 || outbound.ProtocolVersion != 69 || outbound.Genesis != params.HoodiGenesisHash {
			return errors.New("incorrect local Status")
		}
		peer := &eth.StatusPacket{ProtocolVersion: 69, NetworkID: 1,
			Genesis: params.MainnetGenesisHash, ForkID: forkid.ID{Hash: [4]byte{0xaa, 0xbb, 0xcc, 0xdd}, Next: 12},
			LatestBlock: 123, LatestBlockHash: common.HexToHash("0x1234")}
		encoded, err := rlp.EncodeToBytes(peer)
		if err != nil {
			return err
		}
		_, err = transport.Write(0x10, encoded)
		return err
	})
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	got := inspect(ctx, input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.TCP.State != "PASS" || got.Auth.State != "PASS" || got.Hello.State != "PASS" || got.Status.State != "PASS" {
		t.Fatalf("incorrect stages: %+v", got)
	}
	if got.StatusTrace == nil || !got.StatusTrace.LocalStatusSent || !got.StatusTrace.RemoteStatusReceived {
		t.Fatalf("bidirectional Status not observed: %+v", got.StatusTrace)
	}
	if got.HelloInfo.ClientID == "" || got.HelloInfo.NegotiatedEthVersion != 69 || !got.HelloInfo.SnapSupport {
		t.Fatalf("incorrect Hello: %+v", got.HelloInfo)
	}
	if got.StatusInfo.NetworkID != 1 || got.StatusInfo.ForkHash != "0xaabbccdd" || got.StatusInfo.ForkNext != 12 ||
		got.StatusInfo.EarliestBlock != 0 || got.StatusInfo.LatestBlock != 123 ||
		got.StatusInfo.LatestBlockHash != common.HexToHash("0x1234").Hex() {
		t.Fatalf("incorrect ETH Status: %+v", got.StatusInfo)
	}
}

func TestNoCompatibleCapability(t *testing.T) {
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		_, err := serverHello(conn, private, []p2p.Cap{{Name: "les", Version: 2}})
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Auth.State != "PASS" || got.Hello.State != "PASS" || got.Status.State != "NOT_TESTED" ||
		got.Status.ReasonCode != "NO_COMPATIBLE_ETH_CAPABILITY" {
		t.Fatalf("wrong pipeline: %+v", got)
	}
}

func TestHelloWithoutTrustedLocalStatusRPC(t *testing.T) {
	t.Setenv("TORCHNODE_P2P_STATUS_RPC_URL", "")
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		_, err := serverHello(conn, private, []p2p.Cap{{Name: "eth", Version: 69}})
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.TCP.State != "PASS" || got.Auth.State != "PASS" || got.Hello.State != "PASS" ||
		got.Status.State != "NOT_TESTED" || got.Status.ReasonCode != "LOCAL_STATUS_RPC_NOT_CONFIGURED" {
		t.Fatalf("unexpected local Status fallback: %+v", got)
	}
}

func TestMalformedHelloAndDownstreamNotTested(t *testing.T) {
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport := rlpx.NewConn(conn, nil)
		if _, err := transport.Handshake(private); err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		_, err := transport.Write(0, []byte{0xff})
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Auth.State != "PASS" || got.Hello.State != "FAILED" || got.Hello.ReasonCode != "HELLO_INVALID_RLP" ||
		got.Status.State != "NOT_TESTED" {
		t.Fatalf("wrong pipeline: %+v", got)
	}
}

func TestOversizedHelloAndInvalidEncryptedFrame(t *testing.T) {
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport := rlpx.NewConn(conn, nil)
		if _, err := transport.Handshake(private); err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		_, err := transport.Write(0, make([]byte, maxMessage+1))
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Auth.State != "PASS" || got.Hello.ReasonCode != "HELLO_OVERSIZED" || got.Status.State != "NOT_TESTED" {
		t.Fatalf("oversized Hello was not contained: %+v", got)
	}

	input, done = fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport := rlpx.NewConn(conn, nil)
		if _, err := transport.Handshake(private); err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		_, err := conn.Write(make([]byte, 32)) // corrupted encrypted header/MAC
		return err
	})
	got = inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Auth.State != "PASS" || got.Hello.State != "FAILED" || got.Hello.ReasonCode != "INVALID_MAC" ||
		got.Status.State != "NOT_TESTED" {
		t.Fatalf("invalid frame was not contained: %+v", got)
	}
}

func TestDisconnectReasonAndHelloTrace(t *testing.T) {
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport := rlpx.NewConn(conn, nil)
		if _, err := transport.Handshake(private); err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		payload, err := rlp.EncodeToBytes([]p2p.DiscReason{p2p.DiscTooManyPeers})
		if err != nil {
			return err
		}
		_, err = transport.Write(1, payload)
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Auth.State != "PASS" || got.Hello.ReasonCode != "DISCONNECT_RECEIVED" ||
		got.Status.State != "NOT_TESTED" || got.HelloTrace == nil || !got.HelloTrace.LocalHelloSent ||
		got.HelloTrace.FramesDecoded != 1 ||
		got.HelloTrace.Disconnect == nil || got.HelloTrace.Disconnect.Code != 4 ||
		got.HelloTrace.Disconnect.Name != "TOO_MANY_PEERS" {
		t.Fatalf("disconnect not observed correctly: %+v", got)
	}
	if _, err := parseDisconnect([]byte{0xc2, 0x01, 0x02}); err == nil {
		t.Fatal("accepted malformed disconnect")
	}
	if parsed, err := parseDisconnect([]byte{0xc1, 0x04}); err != nil || parsed.Code != 4 {
		t.Fatalf("valid geth Disconnect payload rejected: %+v %v", parsed, err)
	}
}

func TestHelloEOFAndTimeoutRemainDistinct(t *testing.T) {
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport := rlpx.NewConn(conn, nil)
		if _, err := transport.Handshake(private); err != nil {
			return err
		}
		_, _, _, err := transport.Read() // receive local Hello, then close without a devp2p message
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Auth.State != "PASS" || got.Hello.ReasonCode != "REMOTE_EOF" ||
		got.HelloTrace == nil || !got.HelloTrace.LocalHelloSent || got.HelloTrace.FramesDecoded != 0 ||
		got.Status.State != "NOT_TESTED" {
		t.Fatalf("clean EOF: %+v", got)
	}

	input, done = fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport := rlpx.NewConn(conn, nil)
		if _, err := transport.Handshake(private); err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		_, _ = io.Copy(io.Discard, conn)
		return nil
	})
	got = inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Hello.State != "TIMEOUT" || got.Hello.ReasonCode != "HELLO_TIMEOUT" ||
		got.Status.State != "NOT_TESTED" {
		t.Fatalf("Hello timeout: %+v", got)
	}
}

func TestTransportFailureClassification(t *testing.T) {
	cases := []struct {
		err  error
		want string
	}{
		{io.EOF, "REMOTE_EOF"},
		{io.ErrUnexpectedEOF, "MALFORMED_FRAME"},
		{syscall.ECONNRESET, "CONNECTION_RESET"},
		{errors.New("bad frame MAC"), "INVALID_MAC"},
		{errors.New("invalid message code"), "HELLO_PROTOCOL_ERROR"},
	}
	for _, tc := range cases {
		if got := classifyProtocol("HELLO", tc.err); got != tc.want {
			t.Errorf("%v: got %s, want %s", tc.err, got, tc.want)
		}
	}
}

func TestPinnedGethServerHello(t *testing.T) {
	t.Setenv("TORCHNODE_P2P_STATUS_RPC_URL", "")
	private := key(t)
	server := &p2p.Server{Config: p2p.Config{PrivateKey: private, MaxPeers: 4,
		NoDiscovery: true, NoDial: true, ListenAddr: "127.0.0.1:0", Name: "Geth test peer"}}
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(server.Stop)
	_, portText, err := net.SplitHostPort(server.ListenAddr)
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	got := inspect(context.Background(), request{IP: "127.0.0.1", TCPPort: port,
		NodeID: hex.EncodeToString(crypto.FromECDSAPub(&private.PublicKey)[1:])})
	if got.Auth.State != "PASS" || got.Hello.State != "PASS" || got.HelloInfo.ClientID != "Geth test peer" ||
		got.HelloTrace == nil || !got.HelloTrace.LocalHelloSent || got.HelloTrace.FramesDecoded < 1 ||
		got.Status.State != "NOT_TESTED" {
		t.Fatalf("pinned geth interop failed: %+v", got)
	}
}

func TestMalformedAuthResponse(t *testing.T) {
	input, done := fixture(t, func(conn net.Conn, _ *ecdsa.PrivateKey) error {
		_, err := conn.Write([]byte{0, 1, 0})
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.TCP.State != "PASS" || got.Auth.State == "PASS" || got.Hello.State != "NOT_TESTED" {
		t.Fatalf("malformed auth response was not contained: %+v", got)
	}
}

func TestETHStatusTimeoutAndMalformedResponse(t *testing.T) {
	old := localStatusProvider
	localStatusProvider = func(context.Context, uint) (any, string) {
		return &eth.StatusPacket{ProtocolVersion: 69, NetworkID: 1,
			Genesis: params.MainnetGenesisHash, LatestBlock: 10,
			LatestBlockHash: common.HexToHash("0x01")}, ""
	}
	t.Cleanup(func() { localStatusProvider = old })
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport, err := serverHello(conn, private, []p2p.Cap{{Name: "eth", Version: 69}})
		if err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		_, _ = io.Copy(io.Discard, conn)
		return nil
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Hello.State != "PASS" || got.Status.State != "TIMEOUT" ||
		got.Status.ReasonCode != "ETH_STATUS_TIMEOUT" {
		t.Fatalf("wrong status timeout: %+v", got)
	}

	input, done = fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport, err := serverHello(conn, private, []p2p.Cap{{Name: "eth", Version: 69}})
		if err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		_, err = transport.Write(0x10, []byte{0xff})
		return err
	})
	got = inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Hello.State != "PASS" || got.Status.State != "FAILED" ||
		got.Status.ReasonCode != "STATUS_INVALID_RLP" {
		t.Fatalf("malformed Status escaped: %+v", got)
	}
}

func TestDisconnectDuringETHStatusRetainsReason(t *testing.T) {
	old := localStatusProvider
	localStatusProvider = func(context.Context, uint) (any, string) {
		return &eth.StatusPacket{ProtocolVersion: 69, NetworkID: 1,
			Genesis: params.MainnetGenesisHash, LatestBlock: 10,
			LatestBlockHash: common.HexToHash("0x01")}, ""
	}
	t.Cleanup(func() { localStatusProvider = old })
	input, done := fixture(t, func(conn net.Conn, private *ecdsa.PrivateKey) error {
		transport, err := serverHello(conn, private, []p2p.Cap{{Name: "eth", Version: 69}})
		if err != nil {
			return err
		}
		if _, _, _, err := transport.Read(); err != nil {
			return err
		}
		payload, err := rlp.EncodeToBytes([]p2p.DiscReason{p2p.DiscUselessPeer})
		if err != nil {
			return err
		}
		_, err = transport.Write(1, payload)
		return err
	})
	got := inspect(context.Background(), input)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got.Hello.State != "PASS" || got.Status.State != "FAILED" ||
		got.Status.ReasonCode != "DISCONNECT_RECEIVED" || got.StatusTrace == nil ||
		!got.StatusTrace.LocalStatusSent || got.StatusTrace.RemoteStatusReceived ||
		got.StatusTrace.Disconnect == nil || got.StatusTrace.Disconnect.Name != "USELESS_PEER" {
		t.Fatalf("Status Disconnect not retained: %+v", got)
	}
}

func TestAuthTimeoutAndCancellation(t *testing.T) {
	input, done := fixture(t, func(conn net.Conn, _ *ecdsa.PrivateKey) error {
		_, _ = conn.Read(make([]byte, 1)) // client closes on timeout
		<-time.After(3500 * time.Millisecond)
		return nil
	})
	got := inspect(context.Background(), input)
	if got.TCP.State != "PASS" || got.Auth.State != "TIMEOUT" || got.Hello.State != "NOT_TESTED" {
		t.Fatalf("wrong timeout pipeline: %+v", got)
	}
	<-done

	input2, done2 := fixture(t, func(conn net.Conn, _ *ecdsa.PrivateKey) error {
		_, _ = io.Copy(io.Discard, conn)
		return nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(100*time.Millisecond, cancel)
	started := time.Now()
	got = inspect(ctx, input2)
	if time.Since(started) > time.Second || got.Auth.ReasonCode != "CANCELLED" {
		t.Fatalf("cancellation did not close the socket: %+v", got)
	}
	<-done2
}

func TestConnectionRefused(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	private := key(t)
	got := inspect(context.Background(), request{IP: "127.0.0.1", TCPPort: port,
		NodeID: hex.EncodeToString(crypto.FromECDSAPub(&private.PublicKey)[1:])})
	if got.TCP.State != "FAILED" || got.TCP.ReasonCode != "TCP_CONNECTION_REFUSED" || got.Auth.State != "NOT_TESTED" {
		t.Fatalf("wrong connection pipeline: %+v", got)
	}
}

func TestPayloadValidation(t *testing.T) {
	private := key(t)
	id := crypto.FromECDSAPub(&private.PublicKey)[1:]
	for _, payload := range [][]byte{{0xff}, make([]byte, maxMessage+1)} {
		if _, err := parseHello(payload, id); err == nil {
			t.Fatal("accepted malformed Hello")
		}
		if _, err := parseStatus(payload, 69); err == nil {
			t.Fatal("accepted malformed Status")
		}
	}
	oversizedCaps := wireHello{Version: 5, ID: id, Caps: make([]p2p.Cap, maxCapabilities+1)}
	encoded, _ := rlp.EncodeToBytes(oversizedCaps)
	if _, err := parseHello(encoded, id); err == nil {
		t.Fatal("accepted oversized capabilities")
	}
	if negotiateETH([]uint{67, 69, 72}) != 72 || negotiateETH([]uint{67, 68}) != 0 {
		t.Fatal("bad negotiation")
	}
	status72, _ := rlp.EncodeToBytes(&eth.StatusPacket{ProtocolVersion: 72, NetworkID: 1,
		Genesis: params.MainnetGenesisHash, LatestBlock: 100,
		LatestBlockHash: common.HexToHash("0x01")})
	parsed, err := parseStatus(status72, 72)
	if err != nil || parsed.NetworkID != 1 || parsed.ProtocolVersion != 72 {
		t.Fatalf("ETH/72 parse: %v %+v", err, parsed)
	}
}

func TestEveryAdvertisedETHVersionHasCompleteStatusCodec(t *testing.T) {
	for _, version := range eth.ProtocolVersions {
		if version < 69 || version > 72 {
			t.Fatalf("advertised ETH/%d without reviewed Status semantics", version)
		}
		packet := &eth.StatusPacket{ProtocolVersion: uint32(version), NetworkID: 1,
			Genesis:       params.MainnetGenesisHash,
			ForkID:        forkid.ID{Hash: [4]byte{0xaa, 0xbb, 0xcc, 0xdd}, Next: 100},
			EarliestBlock: 10, LatestBlock: 20,
			LatestBlockHash: common.HexToHash("0x1234")}
		encoded, err := rlp.EncodeToBytes(packet)
		if err != nil {
			t.Fatal(err)
		}
		observed, err := parseStatus(encoded, version)
		if err != nil || observed.ProtocolVersion != uint32(version) ||
			observed.EarliestBlock != 10 || observed.LatestBlock != 20 ||
			observed.LatestBlockHash != packet.LatestBlockHash.Hex() ||
			observed.ForkHash != "0xaabbccdd" || observed.ForkNext != 100 {
			t.Fatalf("ETH/%d Status fields: %+v, %v", version, observed, err)
		}
	}
}

func TestLocalStatusRequiresExplicitTrustedRPC(t *testing.T) {
	t.Setenv("TORCHNODE_P2P_STATUS_RPC_URL", "")
	if _, reason := loadLocalStatus(context.Background(), 69); reason != "LOCAL_STATUS_RPC_NOT_CONFIGURED" {
		t.Fatalf("missing RPC: %s", reason)
	}
	t.Setenv("TORCHNODE_P2P_STATUS_RPC_URL", "file:///tmp/untrusted")
	if _, reason := loadLocalStatus(context.Background(), 69); reason != "LOCAL_STATUS_RPC_INVALID_URL" {
		t.Fatalf("invalid RPC URL: %s", reason)
	}
}
