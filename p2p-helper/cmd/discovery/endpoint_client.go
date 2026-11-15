package main

// The upstream Node prefers a more global IPv6 address over a private IPv4 address.
// This bounded request adapter selects a signed ENR family tuple explicitly while
// delegating ALL packet/handshake/session cryptography to geth's public v5wire.Codec.
import (
	"context"
	"crypto/ecdsa"
	"crypto/rand"
	"errors"
	"net"
	"net/netip"
	"time"

	"github.com/ethereum/go-ethereum/common/mclock"
	"github.com/ethereum/go-ethereum/p2p/discover/v5wire"
	"github.com/ethereum/go-ethereum/p2p/enode"
	"github.com/ethereum/go-ethereum/p2p/enr"
	"github.com/ethereum/go-ethereum/p2p/netutil"
)

func ipv4Endpoint(n *enode.Node) (netip.AddrPort, bool) {
	var ip enr.IPv4Addr
	var port enr.UDP
	if n.Load(&ip) != nil || n.Load(&port) != nil || !netip.Addr(ip).Is4() || netip.Addr(ip).IsUnspecified() || netip.Addr(ip).IsMulticast() || port == 0 {
		return netip.AddrPort{}, false
	}
	return netip.AddrPortFrom(netip.Addr(ip), uint16(port)), true
}

func ipv6Endpoint(n *enode.Node) (netip.AddrPort, bool) {
	var ip enr.IPv6
	var port enr.UDP6
	if n.Load(&ip) != nil {
		return netip.AddrPort{}, false
	}
	addr, ok := netip.AddrFromSlice(net.IP(ip))
	if err := n.Load(&port); err != nil {
		if !enr.IsNotFound(err) {
			return netip.AddrPort{}, false
		}
		var fallback enr.UDP
		if n.Load(&fallback) != nil {
			return netip.AddrPort{}, false
		}
		port = enr.UDP6(fallback)
	}
	if !ok || !addr.Is6() || addr.Is4In6() || addr.IsUnspecified() || addr.IsMulticast() || addr.Zone() != "" || port == 0 {
		return netip.AddrPort{}, false
	}
	return netip.AddrPortFrom(addr, uint16(port)), true
}
func (c *endpointClient) endpoint(n *enode.Node) (netip.AddrPort, bool) {
	if c.ipv6 {
		return ipv6Endpoint(n)
	}
	return ipv4Endpoint(n)
}

type endpointClient struct {
	ipv6      bool
	sink      *sink
	local     *enode.LocalNode
	conn      *guardedConn
	codec     *v5wire.Codec
	contextID string
}

func newIPv4Client(ln *enode.LocalNode, key *ecdsa.PrivateKey) (*endpointClient, error) {
	return newFamilyClient(ln, key, false)
}
func newFamilyClient(ln *enode.LocalNode, key *ecdsa.PrivateKey, ipv6 bool) (*endpointClient, error) {
	network, ip := "udp4", net.IPv4zero
	if ipv6 {
		network, ip = "udp6", net.IPv6zero
	}
	conn, err := net.ListenUDP(network, &net.UDPAddr{IP: ip})
	if err != nil {
		return nil, err
	}
	return &endpointClient{ipv6: ipv6, local: ln, conn: &guardedConn{UDPConn: conn, replay: make(map[[32]byte]int64)}, codec: v5wire.NewCodec(ln, key, mclock.System{}, nil), contextID: captureToken()}, nil
}
func (c *endpointClient) close() { c.conn.Close() }
func (c *endpointClient) request(ctx context.Context, n *enode.Node, p v5wire.Packet, wanted byte) (replies []v5wire.Packet, requestError error) {
	trace := c.beginTrace(n, p)
	defer func() { c.finishTrace(trace, requestError) }()
	seenPackets := make(map[[32]byte]bool)
	if len(p.RequestID()) == 0 || len(p.RequestID()) > 8 {
		return nil, errors.New("invalid request ID")
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	addr, ok := c.endpoint(n)
	if !ok {
		return nil, errors.New("no advertised endpoint for selected family")
	}
	wire, nonce, err := c.codec.Encode(n.ID(), addr.String(), p, nil)
	if err != nil {
		return nil, err
	}
	if _, err = c.conn.WriteToUDPAddrPort(wire, addr); err != nil {
		return nil, err
	}
	deadline := time.Now().Add(700 * time.Millisecond)
	challengeSeen := false
	total := -1
	var buf [1280]byte
	for time.Now().Before(deadline) {
		if ctx.Err() != nil {
			return replies, ctx.Err()
		}
		c.conn.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
		size, from, err := c.conn.ReadFromUDPAddrPort(buf[:])
		if err != nil {
			if e, ok := err.(net.Error); ok && e.Timeout() {
				continue
			}
			return replies, err
		}
		if from != addr {
			trace.packet(buf[:size], enode.ID{}, nil, false, "UNEXPECTED_ENDPOINT")
			continue
		}
		id, _, packet, err := c.codec.Decode(buf[:size], addr.String())
		if err != nil {
			trace.packet(buf[:size], enode.ID{}, nil, false, "DECODE_FAILED")
			continue
		}
		if challenge, ok := packet.(*v5wire.Whoareyou); ok {
			if challengeSeen || challenge.Nonce != nonce {
				continue
			}
			challengeSeen = true
			if c.sink != nil {
				c.sink.diagnostic("WHOAREYOU_RECEIVED", addr.String())
			}
			challenge.Node = n
			wire, _, err = c.codec.Encode(n.ID(), addr.String(), p, challenge)
			if err != nil {
				return replies, err
			}
			if _, err = c.conn.WriteToUDPAddrPort(wire, addr); err != nil {
				return replies, err
			}
			deadline = time.Now().Add(700 * time.Millisecond)
			continue
		}
		if id != n.ID() {
			trace.packet(buf[:size], id, nil, false, "RESPONDER_MISMATCH")
			continue
		}
		if unknown, ok := packet.(*v5wire.Unknown); ok {
			trace.packet(buf[:size], id, nil, false, "UNAUTHENTICATED_MESSAGE")
			// Reciprocal handshake is allowed, but only this authenticated request target/source.
			challenge := &v5wire.Whoareyou{Nonce: unknown.Nonce, Node: n, RecordSeq: n.Seq()}
			if _, err = rand.Read(challenge.IDNonce[:]); err != nil {
				return replies, err
			}
			wire, _, err = c.codec.Encode(n.ID(), addr.String(), challenge, nil)
			if err == nil {
				c.conn.WriteToUDPAddrPort(wire, addr)
			}
			continue
		}
		if ping, ok := packet.(*v5wire.Ping); ok {
			answer := &v5wire.Pong{ReqID: ping.ReqID, ENRSeq: c.local.Node().Seq(), ToIP: from.Addr().AsSlice(), ToPort: from.Port()}
			wire, _, err = c.codec.Encode(n.ID(), addr.String(), answer, nil)
			if err == nil {
				c.conn.WriteToUDPAddrPort(wire, addr)
			}
			continue
		}
		if query, ok := packet.(*v5wire.Findnode); ok {
			answer := &v5wire.Nodes{ReqID: query.ReqID, RespCount: 1}
			for _, d := range query.Distances {
				if d == 0 {
					answer.Nodes = []*enr.Record{c.local.Node().Record()}
					break
				}
			}
			wire, _, err = c.codec.Encode(n.ID(), addr.String(), answer, nil)
			if err == nil {
				c.conn.WriteToUDPAddrPort(wire, addr)
			}
			continue
		}
		if packet.Kind() != wanted || !equalID(packet.RequestID(), p.RequestID()) {
			if nodes, ok := packet.(*v5wire.Nodes); ok {
				trace.packet(buf[:size], id, nodes, true, "REQUEST_ID_MISMATCH")
			}
			continue
		}
		if nodes, ok := packet.(*v5wire.Nodes); ok {
			fingerprint := nodesFingerprint(nodes)
			if seenPackets[fingerprint] {
				trace.packet(buf[:size], id, nodes, true, "DUPLICATE_NODES")
				continue
			}
			seenPackets[fingerprint] = true
			trace.packet(buf[:size], id, nodes, true, "MATCHED_NODES")
			if len(nodes.Nodes) > 16 {
				return replies, errors.New("excessive ENRs in NODES packet")
			}
			if nodes.RespCount == 0 || nodes.RespCount > 5 {
				return replies, errors.New("excessive NODES total")
			}
			if total < 0 {
				total = int(nodes.RespCount)
			}
			if total != int(nodes.RespCount) {
				return replies, errors.New("inconsistent NODES total")
			}
		} else {
			total = 1
		}
		replies = append(replies, packet)
		if len(replies) >= total {
			if c.sink != nil {
				c.sink.diagnostic("AUTHENTICATED_RESPONSE", addr.String())
			}
			return replies, nil
		}
	}
	return replies, errors.New("endpoint request timeout")
}
func equalID(a, b []byte) bool {
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
func requestID() []byte {
	id := make([]byte, 8)
	if _, err := rand.Read(id); err != nil {
		panic("discovery entropy unavailable")
	}
	return id
}
func (c *endpointClient) ping(ctx context.Context, n *enode.Node, seq uint64) error {
	replies, err := c.request(ctx, n, &v5wire.Ping{ReqID: requestID(), ENRSeq: seq}, v5wire.PongMsg)
	if err != nil {
		return err
	}
	p := replies[0].(*v5wire.Pong)
	if (len(p.ToIP) != 4 && len(p.ToIP) != 16) || p.ToPort == 0 {
		return errors.New("malformed PONG endpoint")
	}
	return nil
}
func (c *endpointClient) findnode(ctx context.Context, n *enode.Node, distances []uint) ([]*enode.Node, error) {
	for _, d := range distances {
		if d > 256 {
			return nil, errors.New("invalid distance")
		}
	}
	replies, err := c.request(ctx, n, &v5wire.Findnode{ReqID: requestID(), Distances: distances}, v5wire.NodesMsg)
	var result []*enode.Node
	seen := make(map[enode.ID]bool)
	allowed := make(map[uint]bool)
	for _, d := range distances {
		allowed[d] = true
	}
	from, _ := c.endpoint(n)
	for _, reply := range replies {
		for _, r := range reply.(*v5wire.Nodes).Nodes {
			candidate, validation := enode.New(enode.ValidSchemes, r)
			if validation != nil {
				if err == nil {
					err = errors.New("invalid ENR in NODES")
				}
				continue
			}
			if seen[candidate.ID()] || !allowed[uint(enode.LogDist(n.ID(), candidate.ID()))] {
				continue
			}
			ep, ok := c.endpoint(candidate)
			if ok && netutil.CheckRelayAddr(from.Addr(), ep.Addr()) != nil {
				continue
			}
			if ok && ep.Port() <= 1024 {
				continue
			}
			seen[candidate.ID()] = true
			result = append(result, candidate)
			if len(result) > 16 {
				return nil, errors.New("excessive ENRs")
			}
		}
	}
	return result, err
}
