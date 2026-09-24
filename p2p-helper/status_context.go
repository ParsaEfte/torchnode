package main

import (
	"context"
	"math/big"
	"net/url"
	"os"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/forkid"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/ethereum/go-ethereum/eth/protocols/eth"
	"github.com/ethereum/go-ethereum/ethclient"
	"github.com/ethereum/go-ethereum/params"
	"github.com/ethereum/go-ethereum/rpc"
)

type chainProfile struct {
	networkID uint64
	genesis   common.Hash
	config    *params.ChainConfig
}

func profile(chainID uint64) (chainProfile, bool) {
	switch chainID {
	case 1:
		return chainProfile{1, params.MainnetGenesisHash, params.MainnetChainConfig}, true
	case 11155111:
		return chainProfile{11155111, params.SepoliaGenesisHash, params.SepoliaChainConfig}, true
	case 560048:
		return chainProfile{560048, params.HoodiGenesisHash, params.HoodiChainConfig}, true
	default:
		return chainProfile{}, false
	}
}

// loadLocalStatus only consumes read-only RPC calls to an explicitly configured,
// trusted execution node. It never derives our status from the peer being inspected.
func loadLocalStatus(ctx context.Context, version uint) (any, string) {
	rawURL := os.Getenv("TORCHNODE_P2P_STATUS_RPC_URL")
	if rawURL == "" {
		return nil, "LOCAL_STATUS_RPC_NOT_CONFIGURED"
	}
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Scheme != "http" && parsed.Scheme != "https" || parsed.Host == "" {
		return nil, "LOCAL_STATUS_RPC_INVALID_URL"
	}
	rpcClient, err := rpc.DialContext(ctx, rawURL)
	if err != nil {
		return nil, "LOCAL_STATUS_RPC_UNAVAILABLE"
	}
	defer rpcClient.Close()
	client := ethclient.NewClient(rpcClient)
	chainID, err := client.ChainID(ctx)
	if err != nil || chainID == nil || !chainID.IsUint64() {
		return nil, "LOCAL_CHAIN_ID_UNAVAILABLE"
	}
	chain, known := profile(chainID.Uint64())
	if !known {
		return nil, "UNSUPPORTED_LOCAL_CHAIN"
	}
	networkID, err := client.NetworkID(ctx)
	if err != nil || networkID == nil || !networkID.IsUint64() || networkID.Uint64() != chain.networkID {
		return nil, "LOCAL_NETWORK_ID_MISMATCH"
	}
	genesis, err := client.HeaderByNumber(ctx, big.NewInt(0))
	if err != nil || genesis == nil || genesis.Hash() != chain.genesis {
		return nil, "LOCAL_GENESIS_MISMATCH"
	}
	head, err := client.HeaderByNumber(ctx, nil)
	if err != nil || head == nil || head.Number == nil || !head.Number.IsUint64() ||
		head.Hash() == (common.Hash{}) {
		return nil, "LOCAL_HEAD_UNAVAILABLE"
	}
	// A stale RPC head is not a defensible local ETH Status.
	now := uint64(time.Now().Unix())
	if head.Time > now+120 || head.Time <= now && now-head.Time > 30*60 {
		return nil, "LOCAL_HEAD_STALE"
	}
	fork := forkid.NewID(chain.config, types.NewBlockWithHeader(genesis), head.Number.Uint64(), head.Time)
	if version >= 69 && version <= 72 {
		// ETH/69 advertises a block-availability range. Verify the current block
		// body is actually retrievable, then conservatively claim only that block;
		// a public RPC cannot prove the provider's archive/pruning boundary.
		block, err := client.BlockByNumber(ctx, head.Number)
		if err != nil || block == nil || block.Hash() != head.Hash() {
			return nil, "LOCAL_BLOCK_BODY_UNAVAILABLE"
		}
		return &eth.StatusPacket{ProtocolVersion: uint32(version), NetworkID: networkID.Uint64(),
			Genesis: chain.genesis, ForkID: fork, EarliestBlock: head.Number.Uint64(),
			LatestBlock: head.Number.Uint64(), LatestBlockHash: head.Hash()}, ""
	}
	return nil, "UNSUPPORTED_ETH_VERSION"
}
