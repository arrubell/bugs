package org.unichain.core.net.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import javafx.util.Pair;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.unichain.common.overlay.server.Channel.UnichainState;
import org.unichain.core.capsule.BlockCapsule;
import org.unichain.core.capsule.BlockCapsule.BlockId;
import org.unichain.core.config.Parameter.NodeConstant;
import org.unichain.core.exception.P2pException;
import org.unichain.core.exception.P2pException.TypeEnum;
import org.unichain.core.net.UnichainNetDelegate;
import org.unichain.core.net.message.BlockMessage;
import org.unichain.core.net.message.FetchInvDataMessage;
import org.unichain.core.net.message.SyncBlockChainMessage;
import org.unichain.core.net.peer.PeerConnection;
import org.unichain.protos.Protocol.Inventory.InventoryType;
import org.unichain.protos.Protocol.ReasonCode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.unichain.core.config.Parameter.NetConstants.MAX_BLOCK_FETCH_PER_PEER;

@Slf4j(topic = "net")
@Component
public class SyncService {
  @Autowired
  private UnichainNetDelegate unichainNetDelegate;

  private Map<BlockMessage, PeerConnection> blockWaitToProcess = new ConcurrentHashMap<>();

  private Map<BlockMessage, PeerConnection> blockJustReceived = new ConcurrentHashMap<>();

  private Cache<BlockId, Long> requestBlockIds = CacheBuilder.newBuilder().maximumSize(10_000)
      .expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(10_000)
      .recordStats().build();

  private ScheduledExecutorService fetchExecutor = Executors.newSingleThreadScheduledExecutor();

  private ScheduledExecutorService blockHandleExecutor = Executors.newSingleThreadScheduledExecutor();

  private volatile boolean handleFlag = false;

  @Setter
  private volatile boolean fetchFlag = false;

  public void init() {
    fetchExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (fetchFlag) {
          fetchFlag = false;
          startFetchSyncBlock();
        }
      } catch (Throwable t) {
        logger.error("Fetch sync block error.", t);
      }
    }, 10, 1, TimeUnit.SECONDS);

    blockHandleExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (handleFlag) {
          handleFlag = false;
          handleSyncBlock();
        }
      } catch (Throwable t) {
        logger.error("Handle sync block error.", t);
      }
    }, 10, 1, TimeUnit.SECONDS);
  }

  public void close() {
    fetchExecutor.shutdown();
    blockHandleExecutor.shutdown();
  }

  public void startSync(PeerConnection peer) {
    peer.setUnichainState(UnichainState.SYNCING);
    peer.setNeedSyncFromPeer(true);
    peer.getSyncBlockToFetch().clear();
    peer.setRemainNum(0);
    peer.setBlockBothHave(unichainNetDelegate.getGenesisBlockId());
    syncNext(peer);
  }

  /**
   *  Build chain sync summary & send request sync of blocks to peer
   */
  public void syncNext(PeerConnection peer) {
    try {
      if (peer.getSyncChainRequested() != null) {
        logger.warn("Peer {} is in sync.", peer.getNode().getHost());
        return;
      }
      LinkedList<BlockId> chainSummary = getBlockChainSummary(peer);
      peer.setSyncChainRequested(new Pair<>(chainSummary, System.currentTimeMillis()));
      peer.sendMessage(new SyncBlockChainMessage(chainSummary));
    } catch (Exception e) {
      logger.error("Peer {} sync failed, reason: {}", peer.getInetAddress(), e.getMessage());
      peer.disconnect(ReasonCode.SYNC_FAIL);
    }
  }

  public void processBlock(PeerConnection peer, BlockMessage blockMessage) {
    synchronized (blockJustReceived) {
      blockJustReceived.put(blockMessage, peer);
    }
    handleFlag = true;
    if (peer.isIdle()) {
      if (peer.getRemainNum() > 0 && peer.getSyncBlockToFetch().size() <= NodeConstant.SYNC_FETCH_BATCH_NUM) {
        syncNext(peer);
      } else {
        fetchFlag = true;
      }
    }
  }

  public void onDisconnect(PeerConnection peer) {
    if (!peer.getSyncBlockRequested().isEmpty()) {
      peer.getSyncBlockRequested().keySet().forEach(blockId -> invalid(blockId));
    }
  }

  private void invalid(BlockId blockId) {
    requestBlockIds.invalidate(blockId);
    fetchFlag = true;
  }

  private LinkedList<BlockId> getBlockChainSummary(PeerConnection peer) throws Exception {

    BlockId beginBlockId = peer.getBlockBothHave();
    List<BlockId> blockIds = new ArrayList<>(peer.getSyncBlockToFetch());
    LinkedList<BlockId> forkList = new LinkedList<>();
    LinkedList<BlockId> summary = new LinkedList<>();
    long syncBeginNumber = unichainNetDelegate.getSyncBeginNumber();
    long low = syncBeginNumber < 0 ? 0 : syncBeginNumber;
    long highNoFork;
    long high;

    if (beginBlockId.getNum() == 0) {
      highNoFork = high = unichainNetDelegate.getHeadBlockId().getNum();
    } else {
      if (unichainNetDelegate.containBlockInMainChain(beginBlockId)) {
        highNoFork = high = beginBlockId.getNum();
      } else {
        forkList = unichainNetDelegate.getBlockChainHashesOnFork(beginBlockId);
        if (forkList.isEmpty()) {
          throw new P2pException(TypeEnum.SYNC_FAILED, "can't find blockId: " + beginBlockId.getString());
        }
        highNoFork = forkList.peekLast().getNum();
        forkList.pollLast();
        Collections.reverse(forkList);
        high = highNoFork + forkList.size();
      }
    }

    if (low > highNoFork) {
      throw new P2pException(TypeEnum.SYNC_FAILED, "low: " + low + " gt highNoFork: " + highNoFork);
    }

    long realHigh = high + blockIds.size();

    logger.info("Get block chain summary, low: {}, highNoFork: {}, high: {}, realHigh: {}", low, highNoFork, high, realHigh);

    while (low <= realHigh) {
      if (low <= highNoFork) {
        summary.offer(unichainNetDelegate.getBlockIdByNum(low));
      } else if (low <= high) {
        summary.offer(forkList.get((int) (low - highNoFork - 1)));
      } else {
        summary.offer(blockIds.get((int) (low - high - 1)));
      }
      low += (realHigh - low + 2) / 2;
    }

    return summary;
  }

  private void startFetchSyncBlock() {
    HashMap<PeerConnection, List<BlockId>> send = new HashMap<>();

    unichainNetDelegate.getActivePeer().stream()
        .filter(peer -> peer.isNeedSyncFromPeer() && peer.isIdle())
        .forEach(peer -> {
          if (!send.containsKey(peer)) {
            send.put(peer, new LinkedList<>());
          }
          for (BlockId blockId : peer.getSyncBlockToFetch()) {
            if (requestBlockIds.getIfPresent(blockId) == null) {
              requestBlockIds.put(blockId, System.currentTimeMillis());
              peer.getSyncBlockRequested().put(blockId, System.currentTimeMillis());
              send.get(peer).add(blockId);
              if (send.get(peer).size() >= MAX_BLOCK_FETCH_PER_PEER) {
                break;
              }
            }
          }
        });

    send.forEach((peer, blockIds) -> {
      if (!blockIds.isEmpty()) {
        peer.sendMessage(new FetchInvDataMessage(new LinkedList<>(blockIds), InventoryType.BLOCK));
      }
    });
  }

  private synchronized void handleSyncBlock() {
    synchronized (blockJustReceived) {
      blockWaitToProcess.putAll(blockJustReceived);
      blockJustReceived.clear();
    }

    final boolean[] isProcessed = {true};

    while (isProcessed[0]) {
      isProcessed[0] = false;
      synchronized (unichainNetDelegate.getBlockLock()) {
        blockWaitToProcess.forEach((msg, peerConnection) -> {
          if (peerConnection.isDisconnect()) {
            blockWaitToProcess.remove(msg);
            invalid(msg.getBlockId());
            return;
          }
          final boolean[] isFound = {false};
          unichainNetDelegate.getActivePeer().stream()
              .filter(peer -> msg.getBlockId().equals(peer.getSyncBlockToFetch().peek()))
              .forEach(peer -> {
                //remove block id to fetch
                peer.getSyncBlockToFetch().pop();
                //mark as in-process
                peer.getSyncBlockInProcess().add(msg.getBlockId());
                isFound[0] = true;
              });
          if (isFound[0]) {
            blockWaitToProcess.remove(msg);
            isProcessed[0] = true;
            processSyncBlock(msg.getBlockCapsule());
          }
        });
      }
    }
  }

  private void processSyncBlock(BlockCapsule block) {
    boolean flag = true;
    BlockId blockId = block.getBlockId();
    try {
      unichainNetDelegate.processBlock(block);
    } catch (Exception e) {
      logger.error("Process sync block {} failed.", blockId.getString(), e);
      flag = false;
    }
    for (PeerConnection peer : unichainNetDelegate.getActivePeer()) {
      if (peer.getSyncBlockInProcess().remove(blockId)) {
        if (flag) {
          peer.setBlockBothHave(blockId);
          if (peer.getSyncBlockToFetch().isEmpty()) {
            //ignite new sync round
            syncNext(peer);
          }
        } else {
          peer.disconnect(ReasonCode.BAD_BLOCK);
        }
      }
    }
  }
}
