package main

// The upstream Node prefers a more global IPv6 address over a private IPv4 address.
// This narrow request adapter selects the signed ENR's IPv4 tuple explicitly while
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

type ipv4Client struct {
	local *enode.LocalNode
	conn  *guardedConn
	codec *v5wire.Codec
}

func newIPv4Client(ln *enode.LocalNode, key *ecdsa.PrivateKey) (*ipv4Client, error) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero})
	if err != nil {
		return nil, err
	}
	return &ipv4Client{ln, &guardedConn{UDPConn: conn, replay: make(map[[32]byte]int64)}, v5wire.NewCodec(ln, key, mclock.System{}, nil)}, nil
}
func (c *ipv4Client) close() { c.conn.Close() }
func (c *ipv4Client) request(ctx context.Context, n *enode.Node, p v5wire.Packet, wanted byte) ([]v5wire.Packet, error) {
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	addr, ok := ipv4Endpoint(n)
	if !ok {
		return nil, errors.New("no advertised IPv4 endpoint")
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
	var replies []v5wire.Packet
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
			continue
		}
		id, _, packet, err := c.codec.Decode(buf[:size], addr.String())
		if err != nil {
			continue
		}
		if challenge, ok := packet.(*v5wire.Whoareyou); ok {
			if challengeSeen || challenge.Nonce != nonce {
				continue
			}
			challengeSeen = true
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
			continue
		}
		if unknown, ok := packet.(*v5wire.Unknown); ok {
			// Reciprocal handshake is allowed, but only this authenticated request target/source.
			challenge := &v5wire.Whoareyou{Nonce: unknown.Nonce, Node: n, RecordSeq: n.Seq()}
			rand.Read(challenge.IDNonce[:])
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
			continue
		}
		if nodes, ok := packet.(*v5wire.Nodes); ok {
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
			return replies, nil
		}
	}
	return replies, errors.New("IPv4 request timeout")
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
func requestID() []byte { id := make([]byte, 8); rand.Read(id); return id }
func (c *ipv4Client) ping(ctx context.Context, n *enode.Node, seq uint64) error {
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
func (c *ipv4Client) findnode(ctx context.Context, n *enode.Node, distances []uint) ([]*enode.Node, error) {
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
	from, _ := ipv4Endpoint(n)
	for _, reply := range replies {
		for _, r := range reply.(*v5wire.Nodes).Nodes {
			candidate, validation := enode.New(enode.ValidSchemes, r)
			if validation != nil {
				return result, errors.New("invalid ENR in NODES")
			}
			if seen[candidate.ID()] || !allowed[uint(enode.LogDist(n.ID(), candidate.ID()))] {
				continue
			}
			ep, ok := ipv4Endpoint(candidate)
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
