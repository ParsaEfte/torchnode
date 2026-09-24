// TorchNode's isolated, one-peer RLPx inspector. All cryptography and encrypted
// framing are delegated to the pinned go-ethereum implementation.
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/eth/protocols/eth"
	"github.com/ethereum/go-ethereum/p2p"
	"github.com/ethereum/go-ethereum/p2p/rlpx"
	"github.com/ethereum/go-ethereum/rlp"
)

const (
	maxMessage      = 64 * 1024 // application messages; RLPx itself caps frames at 16 MiB
	maxCapabilities = 64
	maxClientID     = 256
	maxJavaLong     = uint64(1<<63 - 1)
)

var (
	tcpTimeout    = configuredTimeout("TORCHNODE_P2P_TCP_TIMEOUT_MS", 2000, 100, 5000)
	authTimeout   = configuredTimeout("TORCHNODE_P2P_AUTH_TIMEOUT_MS", 3000, 100, 5000)
	helloTimeout  = configuredTimeout("TORCHNODE_P2P_HELLO_TIMEOUT_MS", 2000, 100, 5000)
	statusTimeout = configuredTimeout("TORCHNODE_P2P_STATUS_TIMEOUT_MS", 3000, 100, 5000)
	totalTimeout  = configuredTimeout("TORCHNODE_P2P_TOTAL_TIMEOUT_MS", 12000, 2000, 12000)
)

func configuredTimeout(name string, fallback, minimum, maximum int) time.Duration {
	value, err := strconv.Atoi(os.Getenv(name))
	if err != nil || value < minimum || value > maximum {
		value = fallback
	}
	return time.Duration(value) * time.Millisecond
}

type request struct {
	IP      string `json:"ip"`
	TCPPort int    `json:"tcpPort"`
	NodeID  string `json:"nodeId"`
}

type stage struct {
	State      string `json:"state"`
	DurationMs *int64 `json:"durationMs,omitempty"`
	ReasonCode string `json:"reasonCode,omitempty"`
}

type capability struct {
	Name    string `json:"name"`
	Version uint   `json:"version"`
}

type helloInfo struct {
	ClientID             string       `json:"clientId"`
	Devp2pVersion        uint64       `json:"devp2pVersion"`
	ListenPort           uint64       `json:"listenPort"`
	NodeID               string       `json:"nodeId"`
	Capabilities         []capability `json:"capabilities"`
	SupportedEthVersions []uint       `json:"supportedEthVersions"`
	SnapSupport          bool         `json:"snapSupport"`
	NegotiatedEthVersion uint         `json:"negotiatedEthVersion,omitempty"`
}

type statusInfo struct {
	ProtocolVersion uint32 `json:"protocolVersion"`
	NetworkID       uint64 `json:"networkId"`
	GenesisHash     string `json:"genesisHash"`
	ForkHash        string `json:"forkHash"`
	ForkNext        uint64 `json:"forkNext"`
	EarliestBlock   uint64 `json:"earliestBlock"`
	LatestBlock     uint64 `json:"latestBlock"`
	LatestBlockHash string `json:"latestBlockHash"`
}

type result struct {
	TCP         stage        `json:"tcp"`
	Auth        stage        `json:"auth"`
	Hello       stage        `json:"hello"`
	Status      stage        `json:"status"`
	HelloInfo   *helloInfo   `json:"helloInfo,omitempty"`
	StatusInfo  *statusInfo  `json:"statusInfo,omitempty"`
	HelloTrace  *helloTrace  `json:"helloTrace,omitempty"`
	StatusTrace *statusTrace `json:"statusTrace,omitempty"`
}

type statusTrace struct {
	LocalStatusSent      bool            `json:"localStatusSent"`
	RemoteStatusReceived bool            `json:"remoteStatusReceived"`
	Disconnect           *disconnectInfo `json:"disconnect,omitempty"`
}

// Trace describes only observations accepted by go-ethereum's RLPx transport.
type helloTrace struct {
	LocalHelloSent  bool            `json:"localHelloSent"`
	FramesDecoded   int             `json:"framesDecoded"`
	LastMessageCode *uint64         `json:"lastMessageCode,omitempty"`
	Disconnect      *disconnectInfo `json:"disconnect,omitempty"`
}

type disconnectInfo struct {
	Code        uint8  `json:"code"`
	Name        string `json:"name"`
	Description string `json:"description"`
}

func parseDisconnect(payload []byte) (*disconnectInfo, error) {
	if len(payload) == 0 || len(payload) > 16 {
		return nil, errors.New("MALFORMED_DISCONNECT")
	}
	// Match the pinned go-ethereum devp2p decoder, including its legacy bare
	// reason form, while requiring exactly one reason and complete RLP input.
	stream := rlp.NewStream(bytes.NewReader(payload), uint64(len(payload)))
	kind, _, err := stream.Kind()
	if err != nil {
		return nil, errors.New("MALFORMED_DISCONNECT")
	}
	if kind == rlp.List {
		if _, err = stream.List(); err != nil {
			return nil, errors.New("MALFORMED_DISCONNECT")
		}
	}
	var reason p2p.DiscReason
	if err = stream.Decode(&reason); err != nil {
		return nil, errors.New("MALFORMED_DISCONNECT")
	}
	if kind == rlp.List {
		if err = stream.ListEnd(); err != nil {
			return nil, errors.New("MALFORMED_DISCONNECT")
		}
	}
	if _, _, err = stream.Kind(); !errors.Is(err, io.EOF) {
		return nil, errors.New("MALFORMED_DISCONNECT")
	}
	return &disconnectInfo{Code: uint8(reason), Name: disconnectName(reason),
		Description: reason.String()}, nil
}

func disconnectName(reason p2p.DiscReason) string {
	switch reason {
	case p2p.DiscRequested:
		return "REQUESTED"
	case p2p.DiscNetworkError:
		return "NETWORK_ERROR"
	case p2p.DiscProtocolError:
		return "PROTOCOL_ERROR"
	case p2p.DiscUselessPeer:
		return "USELESS_PEER"
	case p2p.DiscTooManyPeers:
		return "TOO_MANY_PEERS"
	case p2p.DiscAlreadyConnected:
		return "ALREADY_CONNECTED"
	case p2p.DiscIncompatibleVersion:
		return "INCOMPATIBLE_VERSION"
	case p2p.DiscInvalidIdentity:
		return "INVALID_IDENTITY"
	case p2p.DiscQuitting:
		return "QUITTING"
	case p2p.DiscUnexpectedIdentity:
		return "UNEXPECTED_IDENTITY"
	case p2p.DiscSelf:
		return "SELF"
	case p2p.DiscReadTimeout:
		return "READ_TIMEOUT"
	case p2p.DiscSubprotocolError:
		return "SUBPROTOCOL_ERROR"
	default:
		return "UNKNOWN"
	}
}

var localStatusProvider = loadLocalStatus

func notTested(code string) stage { return stage{State: "NOT_TESTED", ReasonCode: code} }
func passed(start time.Time) stage {
	ms := time.Since(start).Milliseconds()
	return stage{State: "PASS", DurationMs: &ms}
}
func failed(start time.Time, code string, err error) stage {
	state := "FAILED"
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() || errors.Is(err, context.DeadlineExceeded) {
		state = "TIMEOUT"
	}
	// Elapsed time on a failed check is deliberately not reported as latency.
	return stage{State: state, ReasonCode: code}
}

type wireHello struct {
	Version    uint64
	Name       string
	Caps       []p2p.Cap
	ListenPort uint64
	ID         []byte
	Rest       []rlp.RawValue `rlp:"tail"`
}

func parseHello(payload []byte, expectedID []byte) (*helloInfo, error) {
	if len(payload) > maxMessage {
		return nil, errors.New("HELLO_OVERSIZED")
	}
	var hello wireHello
	if err := rlp.DecodeBytes(payload, &hello); err != nil {
		return nil, errors.New("HELLO_INVALID_RLP")
	}
	if hello.Version < 4 || hello.Version > 5 {
		return nil, errors.New("UNSUPPORTED_DEVP2P_VERSION")
	}
	if len(hello.Name) > maxClientID || len(hello.Caps) > maxCapabilities || hello.ListenPort > 65535 || len(hello.ID) != 64 {
		return nil, errors.New("HELLO_MALFORMED")
	}
	if !equalBytes(hello.ID, expectedID) {
		return nil, errors.New("HELLO_IDENTITY_MISMATCH")
	}
	info := &helloInfo{ClientID: hello.Name, Devp2pVersion: hello.Version, ListenPort: hello.ListenPort,
		NodeID: "0x" + hex.EncodeToString(hello.ID), Capabilities: make([]capability, 0, len(hello.Caps)),
		SupportedEthVersions: []uint{}}
	for _, cap := range hello.Caps {
		if len(cap.Name) == 0 || len(cap.Name) > 32 || cap.Version > 1024 {
			return nil, errors.New("HELLO_MALFORMED")
		}
		info.Capabilities = append(info.Capabilities, capability{cap.Name, cap.Version})
		if cap.Name == "eth" {
			info.SupportedEthVersions = append(info.SupportedEthVersions, cap.Version)
		}
		if cap.Name == "snap" && cap.Version == 1 {
			info.SnapSupport = true
		}
	}
	info.NegotiatedEthVersion = negotiateETH(info.SupportedEthVersions)
	return info, nil
}

func equalBytes(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func negotiateETH(offered []uint) uint {
	for _, supported := range eth.ProtocolVersions {
		for _, version := range offered {
			if version == supported {
				return version
			}
		}
	}
	return 0
}

func publicKey(nodeID string) (*ecdsa.PublicKey, []byte, error) {
	value := strings.TrimPrefix(strings.ToLower(nodeID), "0x")
	if len(value) != 128 {
		return nil, nil, errors.New("INVALID_NODE_ID")
	}
	bytes, err := hex.DecodeString(value)
	if err != nil {
		return nil, nil, errors.New("INVALID_NODE_ID")
	}
	pub, err := crypto.UnmarshalPubkey(append([]byte{4}, bytes...))
	if err != nil {
		return nil, nil, errors.New("INVALID_NODE_ID")
	}
	return pub, bytes, nil
}

func inspect(ctx context.Context, input request) result {
	res := result{TCP: notTested("NO_TCP_ENDPOINT"), Auth: notTested("TCP_NOT_CONNECTED"),
		Hello: notTested("AUTH_NOT_COMPLETED"), Status: notTested("HELLO_NOT_COMPLETED")}
	if net.ParseIP(input.IP) == nil || input.TCPPort < 1 || input.TCPPort > 65535 {
		return res
	}
	remoteKey, remoteID, keyErr := publicKey(input.NodeID)
	started := time.Now()
	dialer := net.Dialer{Timeout: tcpTimeout}
	conn, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(input.IP, strconv.Itoa(input.TCPPort)))
	if err != nil {
		res.TCP = failed(started, classifyTCP(err), err)
		return res
	}
	defer conn.Close()
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			_ = conn.Close()
		case <-done:
		}
	}()
	res.TCP = passed(started)
	if keyErr != nil {
		res.Auth = notTested("INVALID_NODE_ID")
		return res
	}
	localKey, err := crypto.GenerateKey()
	if err != nil {
		res.Auth = notTested("LOCAL_KEY_GENERATION_FAILED")
		return res
	}
	transport := rlpx.NewConn(conn, remoteKey)
	started = time.Now()
	_ = transport.SetDeadline(deadline(ctx, authTimeout))
	authenticatedKey, err := transport.Handshake(localKey)
	if err != nil {
		res.Auth = protocolFailure(ctx, started, "RLPX_AUTH", err)
		return res
	}
	if !equalBytes(crypto.FromECDSAPub(authenticatedKey)[1:], remoteID) {
		res.Auth = failed(started, "RLPX_AUTH_IDENTITY_MISMATCH", errors.New("identity mismatch"))
		return res
	}
	res.Auth = passed(started)
	res.HelloTrace = &helloTrace{}
	started = time.Now()
	_ = transport.SetDeadline(deadline(ctx, helloTimeout))
	localHello := wireHello{Version: 5, Name: "TorchNode Observatory/1.0", ListenPort: 0,
		ID: crypto.FromECDSAPub(&localKey.PublicKey)[1:]}
	for _, version := range eth.ProtocolVersions {
		localHello.Caps = append(localHello.Caps, p2p.Cap{Name: "eth", Version: version})
	}
	encoded, err := rlp.EncodeToBytes(localHello)
	if err != nil {
		res.Hello = failed(started, "LOCAL_HELLO_ENCODING_FAILED", err)
		return res
	}
	if _, err = transport.Write(0, encoded); err != nil {
		res.Hello = protocolFailure(ctx, started, "HELLO", err)
		return res
	}
	res.HelloTrace.LocalHelloSent = true
	var peerHello *helloInfo
	for attempts := 0; attempts < 4; attempts++ {
		var code uint64
		var payload []byte
		code, payload, _, err = transport.Read()
		if err != nil {
			break
		}
		res.HelloTrace.FramesDecoded++
		res.HelloTrace.LastMessageCode = &code
		if len(payload) > maxMessage {
			err = errors.New("HELLO_OVERSIZED")
			break
		}
		switch code {
		case 0:
			peerHello, err = parseHello(payload, remoteID)
		case 1:
			res.HelloTrace.Disconnect, err = parseDisconnect(payload)
			if err == nil {
				err = errors.New("DISCONNECT_RECEIVED")
			}
		case 2:
			_, err = transport.Write(3, []byte{0xc0})
		default:
			err = errors.New("HELLO_UNEXPECTED_MESSAGE")
		}
		if peerHello != nil || err != nil {
			break
		}
	}
	if peerHello == nil {
		if err == nil {
			err = errors.New("HELLO_TOO_MANY_MESSAGES")
		}
		res.Hello = protocolFailure(ctx, started, "HELLO", err)
		return res
	}
	res.Hello = passed(started)
	res.HelloInfo = peerHello
	if peerHello.Devp2pVersion >= 5 {
		transport.SetSnappy(true)
	}
	if peerHello.NegotiatedEthVersion == 0 {
		res.Status = notTested("NO_COMPATIBLE_ETH_CAPABILITY")
		return res
	}

	// ETH Status is bidirectional. Without independent, canonical local chain
	// context, stop here rather than inventing a genesis/head/fork ID.
	statusCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	local, reason := localStatusProvider(statusCtx, peerHello.NegotiatedEthVersion)
	cancel()
	if reason != "" {
		res.Status = notTested(reason)
		return res
	}
	started = time.Now()
	_ = transport.SetDeadline(deadline(ctx, statusTimeout))
	res.StatusTrace = &statusTrace{}
	encoded, err = rlp.EncodeToBytes(local)
	if err != nil {
		res.Status = failed(started, "LOCAL_STATUS_ENCODING_FAILED", err)
		return res
	}
	// We advertise only ETH capabilities, so the negotiated ETH offset is 0x10.
	if _, err = transport.Write(0x10, encoded); err != nil {
		res.Status = protocolFailure(ctx, started, "ETH_STATUS", err)
		return res
	}
	res.StatusTrace.LocalStatusSent = true
	for attempts := 0; attempts < 4; attempts++ {
		var code uint64
		var payload []byte
		code, payload, _, err = transport.Read()
		if err != nil {
			break
		}
		if len(payload) > maxMessage {
			err = errors.New("STATUS_OVERSIZED")
			break
		}
		switch code {
		case 0x10:
			res.StatusInfo, err = parseStatus(payload, peerHello.NegotiatedEthVersion)
			if err == nil {
				res.StatusTrace.RemoteStatusReceived = true
			}
		case 1:
			res.StatusTrace.Disconnect, err = parseDisconnect(payload)
			if err == nil {
				err = errors.New("DISCONNECT_RECEIVED")
			}
		case 2:
			_, err = transport.Write(3, []byte{0xc0})
		default:
			err = errors.New("STATUS_UNEXPECTED_MESSAGE")
		}
		if res.StatusInfo != nil || err != nil {
			break
		}
	}
	if res.StatusInfo == nil {
		if err == nil {
			err = errors.New("STATUS_TOO_MANY_MESSAGES")
		}
		res.Status = protocolFailure(ctx, started, "ETH_STATUS", err)
		return res
	}
	res.Status = passed(started)
	return res
}

func deadline(ctx context.Context, timeout time.Duration) time.Time {
	limit := time.Now().Add(timeout)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(limit) {
		return ctxDeadline
	}
	return limit
}

func classifyTCP(err error) string {
	var op *net.OpError
	if errors.As(err, &op) && op.Timeout() {
		return "TCP_TIMEOUT"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "TCP_TIMEOUT"
	}
	if errors.Is(err, context.Canceled) {
		return "CANCELLED"
	}
	if strings.Contains(strings.ToLower(err.Error()), "refused") {
		return "TCP_CONNECTION_REFUSED"
	}
	return "TCP_CONNECTION_FAILED"
}

func classifyProtocol(prefix string, err error) string {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() || errors.Is(err, context.DeadlineExceeded) {
		return prefix + "_TIMEOUT"
	}
	if errors.Is(err, context.Canceled) {
		return "CANCELLED"
	}
	if errors.Is(err, syscall.ECONNRESET) {
		return "CONNECTION_RESET"
	}
	if errors.Is(err, io.ErrUnexpectedEOF) {
		return "MALFORMED_FRAME"
	}
	if errors.Is(err, io.EOF) {
		return "REMOTE_EOF"
	}
	if errors.Is(err, net.ErrClosed) {
		return "CONNECTION_CLOSED"
	}
	message := strings.ToLower(err.Error())
	if strings.Contains(message, "bad header mac") || strings.Contains(message, "bad frame mac") {
		return "INVALID_MAC"
	}
	if prefix == "RLPX_AUTH" && (strings.Contains(message, "invalid") ||
		strings.Contains(message, "decrypt") || strings.Contains(message, "size")) {
		return "RLPX_AUTH_MALFORMED_RESPONSE"
	}
	if strings.Contains(message, "snappy") || strings.Contains(message, "frame") {
		return "MALFORMED_FRAME"
	}
	if strings.HasPrefix(err.Error(), "HELLO_") || strings.HasPrefix(err.Error(), "STATUS_") ||
		err.Error() == "DISCONNECT_RECEIVED" || err.Error() == "MALFORMED_DISCONNECT" ||
		err.Error() == "UNSUPPORTED_DEVP2P_VERSION" {
		return err.Error()
	}
	return prefix + "_PROTOCOL_ERROR"
}

func protocolFailure(ctx context.Context, started time.Time, prefix string, err error) stage {
	if errors.Is(ctx.Err(), context.Canceled) {
		return failed(started, "CANCELLED", ctx.Err())
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return failed(started, prefix+"_TIMEOUT", ctx.Err())
	}
	return failed(started, classifyProtocol(prefix, err), err)
}

func parseStatus(payload []byte, version uint) (*statusInfo, error) {
	if len(payload) > maxMessage {
		return nil, errors.New("STATUS_OVERSIZED")
	}
	if version >= 69 && version <= 72 {
		var packet eth.StatusPacket
		if err := rlp.DecodeBytes(payload, &packet); err != nil {
			return nil, errors.New("STATUS_INVALID_RLP")
		}
		if packet.ProtocolVersion != uint32(version) || packet.LatestBlock < packet.EarliestBlock ||
			packet.NetworkID > maxJavaLong || packet.LatestBlock > maxJavaLong {
			return nil, errors.New("STATUS_MALFORMED")
		}
		return &statusInfo{ProtocolVersion: packet.ProtocolVersion, NetworkID: packet.NetworkID,
			GenesisHash: packet.Genesis.Hex(), ForkHash: fmt.Sprintf("0x%x", packet.ForkID.Hash),
			ForkNext: packet.ForkID.Next, EarliestBlock: packet.EarliestBlock,
			LatestBlock: packet.LatestBlock, LatestBlockHash: packet.LatestBlockHash.Hex()}, nil
	}
	return nil, errors.New("UNSUPPORTED_ETH_VERSION")
}

func main() {
	res := result{TCP: notTested("INVALID_REQUEST"), Auth: notTested("TCP_NOT_CONNECTED"),
		Hello: notTested("AUTH_NOT_COMPLETED"), Status: notTested("HELLO_NOT_COMPLETED")}
	defer func() { _ = json.NewEncoder(os.Stdout).Encode(res) }()
	input := bufio.NewReader(io.LimitReader(os.Stdin, 2048))
	var req request
	if err := json.NewDecoder(input).Decode(&req); err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), totalTimeout)
	defer cancel()
	res = inspect(ctx, req)
}
